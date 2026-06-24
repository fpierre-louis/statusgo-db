package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.PlanTier;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.AdminBillingAgencyDto;
import io.sitprep.sitprepapi.dto.AdminBillingOperationsDto;
import io.sitprep.sitprepapi.dto.AdminBillingUserDto;
import io.sitprep.sitprepapi.dto.SaveAgencyBillingOverrideRequest;
import io.sitprep.sitprepapi.dto.SaveUserBillingOverrideRequest;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class AdminBillingService {

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final AdminAuditLogService adminAuditLogService;
    private final BillingService billingService;
    private final StripeWebhookEventService stripeWebhookEventService;

    public AdminBillingService(GroupRepo groupRepo,
                               UserInfoRepo userInfoRepo,
                               AdminAuditLogService adminAuditLogService,
                               BillingService billingService,
                               StripeWebhookEventService stripeWebhookEventService) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.adminAuditLogService = adminAuditLogService;
        this.billingService = billingService;
        this.stripeWebhookEventService = stripeWebhookEventService;
    }

    @Transactional(readOnly = true)
    public List<AdminBillingAgencyDto> listAgencies() {
        Instant now = Instant.now();
        return groupRepo.findAuthorizedAgencies().stream()
                .map(group -> AdminBillingAgencyDto.from(group, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminBillingOperationsDto operations() {
        boolean groupPrice = billingService.isPriceConfigured(PlanTier.GROUP);
        boolean businessPrice = billingService.isPriceConfigured(PlanTier.BUSINESS);
        return new AdminBillingOperationsDto(
                billingService.isConfigured(),
                billingService.isWebhookConfigured(),
                billingService.stripeMode(),
                billingService.isConfigured() && (groupPrice || businessPrice),
                List.of(
                        new AdminBillingOperationsDto.PriceConfiguration("GROUP", groupPrice),
                        new AdminBillingOperationsDto.PriceConfiguration("BUSINESS", businessPrice),
                        new AdminBillingOperationsDto.PriceConfiguration("AGENCY", false),
                        new AdminBillingOperationsDto.PriceConfiguration("PREMIUM_AGENCY", false)
                ),
                stripeWebhookEventService.health(billingService.isWebhookConfigured()),
                stripeWebhookEventService.recent(40)
        );
    }

    @Transactional
    public AdminBillingAgencyDto saveAgencyOverride(String groupId,
                                                    SaveAgencyBillingOverrideRequest req,
                                                    String actorEmail) {
        Group group = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agency not found"));
        PlanTier tier = PlanTier.fromWire(req == null ? null : req.tier());
        if (tier == PlanTier.FREE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a paid tier for temporary access");
        }
        Instant expiresAt = req == null ? null : req.expiresAt();
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expiration must be in the future");
        }
        group.setSubscriptionOverrideTier(tier.name());
        group.setSubscriptionOverrideExpiresAt(expiresAt);
        group.setSubscriptionOverrideReason(trim(req == null ? null : req.reason(), 500));
        group.setSubscriptionOverrideBy(actorEmail);
        group.setSubscriptionOverrideAt(Instant.now());
        group.setUpdatedAt(Instant.now());
        Group saved = groupRepo.save(group);
        adminAuditLogService.record(
                actorEmail,
                "BILLING_OVERRIDE_GRANTED",
                "group",
                saved.getGroupId(),
                "tier=" + tier.name() + "; expiresAt=" + expiresAt);
        return AdminBillingAgencyDto.from(saved, Instant.now());
    }

    @Transactional
    public AdminBillingAgencyDto clearAgencyOverride(String groupId, String actorEmail) {
        Group group = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agency not found"));
        group.setSubscriptionOverrideTier(null);
        group.setSubscriptionOverrideExpiresAt(null);
        group.setSubscriptionOverrideReason(null);
        group.setSubscriptionOverrideBy(null);
        group.setSubscriptionOverrideAt(null);
        group.setUpdatedAt(Instant.now());
        Group saved = groupRepo.save(group);
        adminAuditLogService.record(
                actorEmail,
                "BILLING_OVERRIDE_CLEARED",
                "group",
                saved.getGroupId(),
                "agency=" + saved.getGroupName());
        return AdminBillingAgencyDto.from(saved, Instant.now());
    }

    @Transactional
    public AdminBillingUserDto saveUserOverride(String email,
                                                SaveUserBillingOverrideRequest req,
                                                String actorEmail) {
        String normalized = normalizeEmail(email);
        UserInfo user = userInfoRepo.findByUserEmailIgnoreCase(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String packageName = trim(req == null ? null : req.packageName(), 64);
        if (packageName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Package is required");
        }
        Instant expiresAt = req == null ? null : req.expiresAt();
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expiration must be in the future");
        }
        user.setSubscriptionOverridePackage(packageName.toUpperCase(Locale.ROOT));
        user.setSubscriptionOverrideExpiresAt(expiresAt);
        user.setSubscriptionOverrideReason(trim(req == null ? null : req.reason(), 500));
        user.setSubscriptionOverrideBy(actorEmail);
        user.setSubscriptionOverrideAt(Instant.now());
        UserInfo saved = userInfoRepo.save(user);
        adminAuditLogService.record(
                actorEmail,
                "USER_PROMOTION_GRANTED",
                "user",
                saved.getUserEmail(),
                "package=" + saved.getSubscriptionOverridePackage() + "; expiresAt=" + expiresAt);
        return AdminBillingUserDto.from(saved, Instant.now());
    }

    @Transactional
    public AdminBillingUserDto clearUserOverride(String email, String actorEmail) {
        String normalized = normalizeEmail(email);
        UserInfo user = userInfoRepo.findByUserEmailIgnoreCase(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setSubscriptionOverridePackage(null);
        user.setSubscriptionOverrideExpiresAt(null);
        user.setSubscriptionOverrideReason(null);
        user.setSubscriptionOverrideBy(null);
        user.setSubscriptionOverrideAt(null);
        UserInfo saved = userInfoRepo.save(user);
        adminAuditLogService.record(
                actorEmail,
                "USER_PROMOTION_CLEARED",
                "user",
                saved.getUserEmail(),
                "promotion cleared");
        return AdminBillingUserDto.from(saved, Instant.now());
    }

    private static String normalizeEmail(String email) {
        String value = trim(email, 320);
        if (value == null || !value.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid email required");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String trim(String raw, int max) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
