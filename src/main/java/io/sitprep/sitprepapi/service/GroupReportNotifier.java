package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.CommunityReport;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.notifications.NotificationRoutes;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * FD-01: tell a group's owner and admins that a member reported a post or
 * comment, as {@code group_report} (Lane B: an inbox row, no interruptive push).
 *
 * <p>Privacy: the notice never names the reporter or quotes the content. The
 * reporter and the reported author are left out of the audience. It goes through
 * {@link NotificationService#deliverPresenceAwareForGroup} so an admin's group
 * mute and quiet hours apply (a suppressed notice still lands in the inbox).
 * Households have no admin home, so a household group sends nothing. A failure
 * here never fails the report.</p>
 */
@Service
public class GroupReportNotifier {

    public static final String TYPE = "group_report";
    private static final Logger log = LoggerFactory.getLogger(GroupReportNotifier.class);

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;
    private final TransactionTemplate newTx;

    public GroupReportNotifier(GroupRepo groupRepo, UserInfoRepo userInfoRepo,
                               NotificationService notificationService,
                               PlatformTransactionManager txManager) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
        // afterCommit runs inside the already-committed transaction, so writes there
        // are never committed; the inbox rows need their own transaction.
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs after the report commits (or now, outside a transaction). */
    public void notifyAdmins(String groupId, CommunityReport.TargetType targetType,
                             String reporterEmail, String targetAuthorEmail) {
        if (groupId == null || groupId.isBlank()) return;
        Runnable send = () -> {
            try {
                newTx.executeWithoutResult(st -> send(groupId, targetType, reporterEmail, targetAuthorEmail));
            } catch (Exception e) {
                log.warn("group_report notice failed for group={}: {}", groupId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void send(String groupId, CommunityReport.TargetType targetType,
                      String reporterEmail, String targetAuthorEmail) {
        Group g = groupRepo.findByGroupId(groupId).orElse(null);
        if (g == null || HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType())) return;

        Set<String> audience = new LinkedHashSet<>();
        add(audience, g.getOwnerEmail());
        if (g.getAdminEmails() != null) g.getAdminEmails().forEach(e -> add(audience, e));
        audience.remove(norm(reporterEmail));
        audience.remove(norm(targetAuthorEmail));
        if (audience.isEmpty()) return;

        String name = g.getGroupName() == null || g.getGroupName().isBlank() ? "your group" : g.getGroupName().trim();
        String what = targetType == CommunityReport.TargetType.GROUP_COMMENT ? "comment" : "post";
        String title = "Report in " + name;
        String body = "A member reported a " + what + " for review.";
        String targetUrl = NotificationRoutes.groupReports(groupId);
        for (String email : audience) {
            UserInfo u = userInfoRepo.findByUserEmailIgnoreCase(email).orElse(null);
            notificationService.deliverPresenceAwareForGroup(
                    email, title, body, name, null, TYPE,
                    groupId, targetUrl, null,
                    u == null ? null : u.getFcmtoken(),
                    groupId, null, null);
        }
    }

    private static void add(Set<String> set, String email) {
        String n = norm(email);
        if (n != null && !n.isEmpty()) set.add(n);
    }

    private static String norm(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
