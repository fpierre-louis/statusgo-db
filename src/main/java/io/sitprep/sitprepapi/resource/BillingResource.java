package io.sitprep.sitprepapi.resource;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.constant.PlanTier;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.BillingAccountStatusDto;
import io.sitprep.sitprepapi.service.BillingService;
import io.sitprep.sitprepapi.service.GroupService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Stripe billing endpoints — Phase 4 of docs/BUSINESS_MODEL.md.
 *
 * <ul>
 *   <li>{@code POST /api/billing/checkout} — owner starts a subscription;
 *       returns the hosted Checkout URL.</li>
 *   <li>{@code POST /api/billing/portal} — owner opens the Stripe
 *       Customer Portal (update card / cancel / invoices).</li>
 *   <li>{@code POST /api/billing/webhook} — Stripe → us. No auth header;
 *       the Stripe-Signature HMAC is the authentication.</li>
 * </ul>
 *
 * <p>Checkout + portal are <b>owner only</b> ({@code MANAGE_PLAN} is
 * owner-gated — the plan is a billing commitment). When Stripe isn't
 * configured the two owner endpoints 503 cleanly.</p>
 */
@RestController
@RequestMapping("/api/billing")
public class BillingResource {

    private static final Logger log = LoggerFactory.getLogger(BillingResource.class);

    private final BillingService billing;
    private final GroupService groupService;

    public BillingResource(BillingService billing, GroupService groupService) {
        this.billing = billing;
        this.groupService = groupService;
    }

    /** Start a subscription. Body: {@code {"groupId": "...", "planTier": "GROUP"}}. */
    @PostMapping("/checkout")
    public ResponseEntity<Map<String, String>> checkout(@RequestBody Map<String, String> body) {
        requireConfigured();
        String caller = AuthUtils.requireAuthenticatedEmail();
        Group group = requireOwner(body == null ? null : body.get("groupId"), caller);
        PlanTier tier = PlanTier.fromWire(body == null ? null : body.get("planTier"));
        try {
            String url = billing.createCheckoutSession(group, tier, caller);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (StripeException e) {
            log.error("Stripe checkout failed for group {}", group.getGroupId(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe error");
        }
    }

    /** Owner-safe billing state for the plan sheet. No Stripe ids or secrets. */
    @GetMapping("/status")
    public ResponseEntity<BillingAccountStatusDto> status(@RequestParam String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Group group = requireOwner(groupId, caller);
        return ResponseEntity.ok(billing.accountStatus(group));
    }

    /** Open the Customer Portal. Body: {@code {"groupId": "..."}}. */
    @PostMapping("/portal")
    public ResponseEntity<Map<String, String>> portal(@RequestBody Map<String, String> body) {
        requireConfigured();
        String caller = AuthUtils.requireAuthenticatedEmail();
        Group group = requireOwner(body == null ? null : body.get("groupId"), caller);
        try {
            return ResponseEntity.ok(Map.of("url", billing.createPortalSession(group)));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (StripeException e) {
            log.error("Stripe portal failed for group {}", group.getGroupId(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe error");
        }
    }

    /**
     * Stripe webhook receiver. Reachable without an auth token — the
     * {@code Stripe-Signature} HMAC, verified against the webhook
     * secret, is the authentication. Always 2xx on a handled event so
     * Stripe doesn't retry; 400 on a bad signature.
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        try {
            BillingService.WebhookReceipt receipt = billing.handleWebhook(payload, signature);
            log.info("Stripe webhook receipt event={} type={} status={} duplicate={}",
                    receipt.eventId(), receipt.eventType(), receipt.status(), receipt.duplicate());
            return ResponseEntity.ok("ok");
        } catch (SignatureVerificationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad Stripe signature");
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Stripe webhook handling failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook error");
        }
    }

    // ---------- helpers ----------

    private void requireConfigured() {
        if (!billing.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Billing is not configured yet.");
        }
    }

    /** Resolve the group and assert the caller is its owner. 400/403/404. */
    private Group requireOwner(String groupId, String caller) {
        if (groupId == null || groupId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupId required");
        }
        Group group;
        try {
            group = groupService.getGroupByPublicId(groupId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }
        if (GroupRole.fromGroup(group, caller) != GroupRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the group owner can manage billing");
        }
        return group;
    }
}
