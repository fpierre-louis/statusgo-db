package io.sitprep.sitprepapi.notifications;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * needs_response, server side (S2 C5): the per-event rule
 * {@link NotificationInboxFilter#needsResponse} (whose SQL twin
 * {@code NotificationInboxPostgresIT} holds to it) agrees with the frontend
 * classifier over the presentation the builder REALLY writes — for every
 * registered type and category, with and without a reference. And official
 * alerts are never in it.
 */
class NotificationInboxFilterTest {

    private final NotificationPresentationBuilder builder = new NotificationPresentationBuilder(
            mock(UserInfoRepo.class), mock(GroupRepo.class), mock(GroupPostRepo.class), mock(PostRepo.class));

    @Test
    void theServerRuleMatchesTheClientClassifierForEveryEvent() {
        List<NotificationLog> rows = new ArrayList<>();
        for (String type : NotificationInboxFilter.registeredTypes()) {
            rows.add(row(type, null, null, null, null));
            rows.add(row(type, null, "ref-1", null, null));
        }
        for (Category c : Category.values()) {
            rows.add(row(null, c.name(), null, null, null));
            rows.add(row(null, c.name(), "ref-1", null, null));
        }
        rows.add(row("pending_member", null, null, "/household/h1/invite-requests/r1", null));
        rows.add(row("pending_member", null, "g1", null, "requester@x.com"));
        rows.add(row("pending_member", null, "g1", null, "{\"json\":true}"));
        rows.add(row("hazard_alert", "AGENCY_ALERT", "agency-alert:4", null, null));

        for (NotificationLog r : rows) {
            NotificationPresentation p = builder.forRow(r);
            assertThat(NotificationInboxFilter.needsResponse(r))
                    .as("type=%s category=%s ref=%s target=%s data=%s", r.getType(), r.getCategory(),
                            r.getReferenceId(), r.getTargetUrl(), r.getAdditionalData())
                    .isEqualTo(NotificationInboxFilter.clientClassifier(r, p));
        }
    }

    @Test
    void officialAndAgencyAlertsNeverNeedAResponse_andReadRowsNeverDo() {
        assertThat(NotificationInboxFilter.needsResponse(row("hazard_alert", "NWS_SEVERE_EXTREME", "NWS-w1", "/hazards", null)))
                .isFalse();
        assertThat(NotificationInboxFilter.needsResponse(row("hazard_alert", "AGENCY_ALERT", "agency-alert:1", null, null)))
                .isFalse();
        NotificationLog read = row("check_in_request", null, "g1", null, null);
        assertThat(NotificationInboxFilter.needsResponse(read)).isTrue();
        read.setReadAt(Instant.now());
        assertThat(NotificationInboxFilter.needsResponse(read)).isFalse();
    }

    @Test
    void filterParsing() {
        assertThat(NotificationInboxFilter.parse(null)).isEqualTo(NotificationInboxFilter.ALL);
        assertThat(NotificationInboxFilter.parse(" Needs_Response ")).isEqualTo(NotificationInboxFilter.NEEDS_RESPONSE);
        assertThat(NotificationInboxFilter.parse("alerts")).isEqualTo(NotificationInboxFilter.ALERTS);
        assertThat(NotificationInboxFilter.parse("nope")).isNull();
    }

    private static NotificationLog row(String type, String category, String ref, String target, String data) {
        NotificationLog n = new NotificationLog("me@x.com", type, null, "Title", "Body", ref, target, data,
                Instant.parse("2026-10-14T12:00:00Z"), true, null);
        n.setId(1L);
        n.setCategory(category);
        return n;
    }
}
