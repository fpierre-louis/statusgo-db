package io.sitprep.sitprepapi.service;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import io.sitprep.sitprepapi.constant.PlanTier;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.BillingAccountStatusDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stripe subscription billing for organization plans — Phase 4 of
 * docs/BUSINESS_MODEL.md.
 *
 * <p>Flow (hosted, minimal PCI surface — no card fields in SitPrep):</p>
 * <ol>
 *   <li>A group <b>owner</b> picks a paid tier → {@link #createCheckoutSession}
 *       builds a Stripe Checkout Session; the FE redirects to the
 *       hosted page.</li>
 *   <li>Stripe fires webhooks → {@link #handleWebhook} keeps
 *       {@code Group.planTier} + the Stripe ids in sync with the
 *       subscription lifecycle. Stripe is the source of truth.</li>
 *   <li>{@link #createPortalSession} opens the Stripe Customer Portal
 *       for the owner to update card / cancel / view invoices.</li>
 * </ol>
 *
 * <p>Config (stripe.* — env vars or application-local.yml). When the
 * secret key is unset the service is <b>dormant</b>: {@link #isConfigured()}
 * is false and the resource layer 503s rather than the app failing to
 * start. Test-mode (sandbox) keys exercise the entire flow; going live
 * is just a key swap.</p>
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final GroupRepo groupRepo;
    private final StripeWebhookEventService webhookEvents;

    private final String secretKey;
    private final String webhookSecret;
    private final String priceGroup;
    private final String priceBusiness;
    private final String frontendBaseUrl;

    public BillingService(
            GroupRepo groupRepo,
            StripeWebhookEventService webhookEvents,
            @Value("${stripe.secret-key:}") String secretKey,
            @Value("${stripe.webhook-secret:}") String webhookSecret,
            @Value("${stripe.price.group:}") String priceGroup,
            @Value("${stripe.price.business:}") String priceBusiness,
            @Value("${app.frontend-base-url:https://sitprep.app}") String frontendBaseUrl) {
        this.groupRepo = groupRepo;
        this.webhookEvents = webhookEvents;
        this.secretKey = trim(secretKey);
        this.webhookSecret = trim(webhookSecret);
        this.priceGroup = trim(priceGroup);
        this.priceBusiness = trim(priceBusiness);
        this.frontendBaseUrl = frontendBaseUrl;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    @PostConstruct
    void init() {
        if (!secretKey.isEmpty()) {
            Stripe.apiKey = secretKey;
            log.info("BillingService: Stripe configured (org-plan billing live).");
        } else {
            log.info("BillingService: stripe.secret-key unset — billing endpoints dormant.");
        }
    }

    /** True once a Stripe secret key is configured. */
    public boolean isConfigured() {
        return !secretKey.isEmpty();
    }

    public boolean isWebhookConfigured() {
        return !webhookSecret.isEmpty();
    }

    public String stripeMode() {
        if (!isConfigured()) return "UNCONFIGURED";
        if (secretKey.startsWith("sk_live_")) return "LIVE";
        if (secretKey.startsWith("sk_test_")) return "TEST";
        return "CONFIGURED";
    }

    public boolean isPriceConfigured(PlanTier tier) {
        return priceIdFor(tier) != null;
    }

    public BillingAccountStatusDto accountStatus(Group group) {
        Instant now = Instant.now();
        boolean overrideActive = group.getSubscriptionOverrideTier() != null
                && group.getSubscriptionOverrideExpiresAt() != null
                && group.getSubscriptionOverrideExpiresAt().isAfter(now);
        String baseTier = PlanTier.fromWire(group.getPlanTier()).name();
        String effectiveTier = overrideActive
                ? PlanTier.fromWire(group.getSubscriptionOverrideTier()).name()
                : baseTier;
        boolean customerPresent = hasText(group.getStripeCustomerId());
        boolean subscriptionPresent = hasText(group.getStripeSubscriptionId())
                && !"canceled".equalsIgnoreCase(group.getSubscriptionStatus());
        List<String> available = List.of(PlanTier.GROUP, PlanTier.BUSINESS).stream()
                .filter(this::isPriceConfigured)
                .map(Enum::name)
                .toList();
        return new BillingAccountStatusDto(
                isConfigured(),
                stripeMode(),
                available,
                customerPresent,
                subscriptionPresent,
                isConfigured() && customerPresent,
                group.getSubscriptionStatus(),
                baseTier,
                effectiveTier,
                group.getSubscriptionOverrideTier(),
                group.getSubscriptionOverrideExpiresAt(),
                overrideActive
        );
    }

    /** Stripe Price id for a paid tier, or null if the tier isn't self-serve. */
    public String priceIdFor(PlanTier tier) {
        if (tier == PlanTier.GROUP) return priceGroup.isEmpty() ? null : priceGroup;
        if (tier == PlanTier.BUSINESS) return priceBusiness.isEmpty() ? null : priceBusiness;
        // FREE = no charge; AGENCY / PREMIUM_AGENCY are sales-led, not Checkout.
        return null;
    }

    private PlanTier tierForPriceId(String priceId) {
        if (priceId == null || priceId.isBlank()) return null;
        if (priceId.equals(priceGroup)) return PlanTier.GROUP;
        if (priceId.equals(priceBusiness)) return PlanTier.BUSINESS;
        return null;
    }

    // ---------------------------------------------------------------------
    // Checkout + Customer Portal
    // ---------------------------------------------------------------------

    /**
     * Create a Stripe Checkout Session (subscription mode) for a group
     * to subscribe to a paid tier. Returns the hosted-checkout URL the
     * frontend redirects the owner to. The groupId + tier ride along as
     * metadata on both the session and the resulting subscription so
     * the webhook can resolve the group.
     */
    public String createCheckoutSession(Group group, PlanTier tier, String ownerEmail)
            throws StripeException {
        if (hasText(group.getStripeSubscriptionId())
                && !"canceled".equalsIgnoreCase(group.getSubscriptionStatus())) {
            throw new IllegalStateException(
                    "This agency already has a Stripe subscription. Open Manage billing instead.");
        }
        String priceId = priceIdFor(tier);
        if (priceId == null) {
            throw new IllegalArgumentException(
                    "No self-serve Stripe price configured for tier " + tier);
        }
        String customerId = ensureCustomer(group, ownerEmail);
        String base = frontendBaseUrl + "/groups/" + group.getGroupId();

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .setSuccessUrl(base + "?billing=success")
                .setCancelUrl(base + "?billing=cancelled")
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(priceId)
                        .setQuantity(1L)
                        .build())
                .putMetadata("groupId", group.getGroupId())
                .putMetadata("planTier", tier.name())
                // Copy the same metadata onto the Subscription so the
                // subscription.updated / .deleted webhooks can resolve
                // the group without a Stripe-id lookup table.
                .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                        .putMetadata("groupId", group.getGroupId())
                        .putMetadata("planTier", tier.name())
                        .build())
                .build();

        return Session.create(params).getUrl();
    }

    /**
     * Create a Stripe Customer Portal session so the owner can update
     * their card, change plan, or cancel. Requires the group to already
     * have a Stripe customer (i.e. it has subscribed at least once).
     */
    public String createPortalSession(Group group) throws StripeException {
        String customerId = group.getStripeCustomerId();
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalStateException(
                    "This group has no billing account yet — subscribe to a plan first.");
        }
        com.stripe.param.billingportal.SessionCreateParams params =
                com.stripe.param.billingportal.SessionCreateParams.builder()
                        .setCustomer(customerId)
                        .setReturnUrl(frontendBaseUrl + "/groups/" + group.getGroupId())
                        .build();
        return com.stripe.model.billingportal.Session.create(params).getUrl();
    }

    /** Reuse the group's Stripe customer, or create one keyed to the group. */
    private String ensureCustomer(Group group, String ownerEmail) throws StripeException {
        if (group.getStripeCustomerId() != null && !group.getStripeCustomerId().isBlank()) {
            return group.getStripeCustomerId();
        }
        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(ownerEmail)
                .setName(group.getGroupName())
                .putMetadata("groupId", group.getGroupId())
                .build();
        Customer customer = Customer.create(params);
        group.setStripeCustomerId(customer.getId());
        group.setUpdatedAt(Instant.now());
        groupRepo.save(group);
        return customer.getId();
    }

    // ---------------------------------------------------------------------
    // Webhook — Stripe is the source of truth for planTier
    // ---------------------------------------------------------------------

    /**
     * Verify a Stripe webhook signature and apply the event. Drives
     * {@code Group.planTier} + the Stripe ids off the subscription
     * lifecycle. Unrecognized event types are ignored.
     */
    public WebhookReceipt handleWebhook(String payload, String sigHeader)
            throws SignatureVerificationException {
        if (webhookSecret.isEmpty()) {
            throw new IllegalStateException("stripe.webhook-secret not configured");
        }
        Event event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        var begin = webhookEvents.begin(event);
        if (begin.duplicate()) {
            return new WebhookReceipt(event.getId(), event.getType(), "DUPLICATE",
                    begin.event().getGroupId(), true);
        }
        try {
            ApplyResult result = switch (event.getType()) {
                case "checkout.session.completed" -> onCheckoutCompleted(event);
                case "customer.subscription.updated" -> onSubscriptionUpdated(event);
                case "customer.subscription.deleted" -> onSubscriptionDeleted(event);
                case "invoice.payment_failed" -> onInvoicePaymentFailed(event);
                case "invoice.paid" -> onInvoicePaid(event);
                default -> {
                    log.debug("Stripe webhook: ignoring event type {}", event.getType());
                    yield new ApplyResult(StripeWebhookEventService.IGNORED, null,
                            "Event type does not change SitPrep billing state");
                }
            };
            webhookEvents.complete(event.getId(), result.status(), result.groupId(), result.detail());
            return new WebhookReceipt(event.getId(), event.getType(), result.status(),
                    result.groupId(), false);
        } catch (RuntimeException e) {
            webhookEvents.complete(event.getId(), StripeWebhookEventService.FAILED, null,
                    e.getClass().getSimpleName() + ": " + trim(e.getMessage()));
            throw e;
        }
    }

    private ApplyResult onCheckoutCompleted(Event event) {
        Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
        if (obj.isEmpty() || !(obj.get() instanceof Session session)) {
            return ignored("Checkout payload could not be deserialized");
        }
        Map<String, String> md = session.getMetadata();
        String groupId = md == null ? null : md.get("groupId");
        if (!hasText(groupId)) return ignored("Checkout session has no groupId metadata");
        Optional<Group> group = groupRepo.findByGroupId(groupId);
        if (group.isEmpty()) return ignored("No group found for checkout metadata", groupId);
        group.ifPresent(g -> {
            g.setStripeCustomerId(session.getCustomer());
            g.setStripeSubscriptionId(session.getSubscription());
            g.setSubscriptionStatus("active");
            String tier = md.get("planTier");
            if (tier != null) g.setPlanTier(PlanTier.fromWire(tier).name());
            g.setUpdatedAt(Instant.now());
            groupRepo.save(g);
            log.info("Stripe: group {} subscribed — tier={}", groupId, g.getPlanTier());
        });
        return processed(groupId, "Checkout completed");
    }

    private ApplyResult onSubscriptionUpdated(Event event) {
        Optional<Subscription> subscription = subscriptionOf(event);
        if (subscription.isEmpty()) return ignored("Subscription payload could not be deserialized");
        Subscription sub = subscription.get();
        Optional<Group> group = groupOf(sub);
        if (group.isEmpty()) return ignored("No group found for subscription metadata");
        Group g = group.get();
        g.setStripeSubscriptionId(sub.getId());
        g.setSubscriptionStatus(sub.getStatus());
        // Only an active / trialing subscription confers a paid
        // tier. past_due / unpaid keep the current tier so a
        // transient card failure doesn't instantly downgrade — a
        // real cancellation arrives as subscription.deleted.
        PlanTier tier = tierForSubscription(sub);
        if (tier != null
                && ("active".equals(sub.getStatus()) || "trialing".equals(sub.getStatus()))) {
            g.setPlanTier(tier.name());
        }
        g.setUpdatedAt(Instant.now());
        groupRepo.save(g);
        log.info("Stripe: group {} subscription {} — status={}",
                g.getGroupId(), sub.getId(), sub.getStatus());
        return processed(g.getGroupId(), "Subscription status=" + sub.getStatus());
    }

    private ApplyResult onSubscriptionDeleted(Event event) {
        Optional<Subscription> subscription = subscriptionOf(event);
        if (subscription.isEmpty()) return ignored("Subscription payload could not be deserialized");
        Subscription sub = subscription.get();
        Optional<Group> group = groupOf(sub);
        if (group.isEmpty()) return ignored("No group found for subscription metadata");
        Group g = group.get();
        g.setPlanTier(PlanTier.FREE.name());
        g.setStripeSubscriptionId(null);
        g.setSubscriptionStatus("canceled");
        g.setUpdatedAt(Instant.now());
        groupRepo.save(g);
        log.info("Stripe: subscription ended for group {} — reverted to FREE",
                g.getGroupId());
        return processed(g.getGroupId(), "Subscription canceled; base plan reverted to FREE");
    }

    private ApplyResult onInvoicePaymentFailed(Event event) {
        return invoiceOf(event)
                .flatMap(invoice -> groupRepo.findByStripeCustomerId(invoice.getCustomer()))
                .map(group -> {
                    group.setSubscriptionStatus("past_due");
                    group.setUpdatedAt(Instant.now());
                    groupRepo.save(group);
                    return processed(group.getGroupId(), "Invoice payment failed");
                })
                .orElseGet(() -> ignored("No group found for invoice customer"));
    }

    private ApplyResult onInvoicePaid(Event event) {
        return invoiceOf(event)
                .flatMap(invoice -> groupRepo.findByStripeCustomerId(invoice.getCustomer()))
                .map(group -> {
                    if (hasText(group.getStripeSubscriptionId())) {
                        group.setSubscriptionStatus("active");
                        group.setUpdatedAt(Instant.now());
                        groupRepo.save(group);
                    }
                    return processed(group.getGroupId(), "Invoice paid");
                })
                .orElseGet(() -> ignored("No group found for invoice customer"));
    }

    private Optional<Subscription> subscriptionOf(Event event) {
        Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
        if (obj.isPresent() && obj.get() instanceof Subscription sub) {
            return Optional.of(sub);
        }
        return Optional.empty();
    }

    private Optional<Invoice> invoiceOf(Event event) {
        Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
        if (obj.isPresent() && obj.get() instanceof Invoice invoice) {
            return Optional.of(invoice);
        }
        return Optional.empty();
    }

    /** Resolve the group from the subscription's {@code groupId} metadata. */
    private Optional<Group> groupOf(Subscription sub) {
        Map<String, String> md = sub.getMetadata();
        String groupId = md == null ? null : md.get("groupId");
        if (hasText(groupId)) {
            Optional<Group> byMetadata = groupRepo.findByGroupId(groupId);
            if (byMetadata.isPresent()) return byMetadata;
        }
        if (hasText(sub.getId())) return groupRepo.findByStripeSubscriptionId(sub.getId());
        return Optional.empty();
    }

    private PlanTier tierForSubscription(Subscription sub) {
        try {
            String priceId = sub.getItems().getData().get(0).getPrice().getId();
            return tierForPriceId(priceId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ApplyResult processed(String groupId, String detail) {
        return new ApplyResult(StripeWebhookEventService.PROCESSED, groupId, detail);
    }

    private static ApplyResult ignored(String detail) {
        return ignored(detail, null);
    }

    private static ApplyResult ignored(String detail, String groupId) {
        return new ApplyResult(StripeWebhookEventService.IGNORED, groupId, detail);
    }

    private record ApplyResult(String status, String groupId, String detail) {}

    public record WebhookReceipt(
            String eventId,
            String eventType,
            String status,
            String groupId,
            boolean duplicate
    ) {}
}
