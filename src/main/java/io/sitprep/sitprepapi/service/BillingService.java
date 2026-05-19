package io.sitprep.sitprepapi.service;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import io.sitprep.sitprepapi.constant.PlanTier;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
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

    private final String secretKey;
    private final String webhookSecret;
    private final String priceGroup;
    private final String priceBusiness;
    private final String frontendBaseUrl;

    public BillingService(
            GroupRepo groupRepo,
            @Value("${stripe.secret-key:}") String secretKey,
            @Value("${stripe.webhook-secret:}") String webhookSecret,
            @Value("${stripe.price.group:}") String priceGroup,
            @Value("${stripe.price.business:}") String priceBusiness,
            @Value("${app.frontend-base-url:https://sitprep.app}") String frontendBaseUrl) {
        this.groupRepo = groupRepo;
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
    public void handleWebhook(String payload, String sigHeader)
            throws SignatureVerificationException {
        if (webhookSecret.isEmpty()) {
            throw new IllegalStateException("stripe.webhook-secret not configured");
        }
        Event event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        switch (event.getType()) {
            case "checkout.session.completed" -> onCheckoutCompleted(event);
            case "customer.subscription.updated" -> onSubscriptionUpdated(event);
            case "customer.subscription.deleted" -> onSubscriptionDeleted(event);
            default -> log.debug("Stripe webhook: ignoring event type {}", event.getType());
        }
    }

    private void onCheckoutCompleted(Event event) {
        Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
        if (obj.isEmpty() || !(obj.get() instanceof Session session)) return;
        Map<String, String> md = session.getMetadata();
        String groupId = md == null ? null : md.get("groupId");
        if (groupId == null) return;
        groupRepo.findByGroupId(groupId).ifPresent(g -> {
            g.setStripeCustomerId(session.getCustomer());
            g.setStripeSubscriptionId(session.getSubscription());
            g.setSubscriptionStatus("active");
            String tier = md.get("planTier");
            if (tier != null) g.setPlanTier(PlanTier.fromWire(tier).name());
            g.setUpdatedAt(Instant.now());
            groupRepo.save(g);
            log.info("Stripe: group {} subscribed — tier={}", groupId, g.getPlanTier());
        });
    }

    private void onSubscriptionUpdated(Event event) {
        subscriptionOf(event).ifPresent(sub -> groupOf(sub).ifPresent(g -> {
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
        }));
    }

    private void onSubscriptionDeleted(Event event) {
        subscriptionOf(event).ifPresent(sub -> groupOf(sub).ifPresent(g -> {
            g.setPlanTier(PlanTier.FREE.name());
            g.setStripeSubscriptionId(null);
            g.setSubscriptionStatus("canceled");
            g.setUpdatedAt(Instant.now());
            groupRepo.save(g);
            log.info("Stripe: subscription ended for group {} — reverted to FREE",
                    g.getGroupId());
        }));
    }

    private Optional<Subscription> subscriptionOf(Event event) {
        Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
        if (obj.isPresent() && obj.get() instanceof Subscription sub) {
            return Optional.of(sub);
        }
        return Optional.empty();
    }

    /** Resolve the group from the subscription's {@code groupId} metadata. */
    private Optional<Group> groupOf(Subscription sub) {
        Map<String, String> md = sub.getMetadata();
        String groupId = md == null ? null : md.get("groupId");
        if (groupId == null || groupId.isBlank()) return Optional.empty();
        return groupRepo.findByGroupId(groupId);
    }

    private PlanTier tierForSubscription(Subscription sub) {
        try {
            String priceId = sub.getItems().getData().get(0).getPrice().getId();
            return tierForPriceId(priceId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
