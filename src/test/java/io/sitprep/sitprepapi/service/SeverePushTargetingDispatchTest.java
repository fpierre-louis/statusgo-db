package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AlertPushTargetingAudit;
import io.sitprep.sitprepapi.domain.GeoPoint;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.AlertPushTargetingAuditRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R19 through the real severe-push path: who {@code pushSevereAlert} hands to
 * the notification batch, and with which title.
 */
class SeverePushTargetingDispatchTest {

    private final Map<String, UserAlertPreference> prefs = new HashMap<>();
    private NotificationService notifications;
    private GroupRepo groups;
    private UserInfoRepo users;
    private AlertPushTargetingAuditRepo audits;
    private AlertDispatchService dispatcher;
    private final List<UserInfo> directory = new ArrayList<>();

    @BeforeEach
    void setUp() {
        UserAlertPreferenceRepo prefRepo = mock(UserAlertPreferenceRepo.class);
        when(prefRepo.findByEmail(anyString())).thenAnswer(inv -> {
            String email = inv.getArgument(0);
            return Optional.of(prefs.computeIfAbsent(email, e -> { var p = new UserAlertPreference(); p.setUserEmail(e); return p; }));
        });
        when(prefRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RateLimiterService rate = mock(RateLimiterService.class);
        when(rate.tryConsume(any(), any())).thenReturn(true);

        notifications = mock(NotificationService.class);
        users = mock(UserInfoRepo.class);
        when(users.findByUserEmailLowerIn(any())).thenAnswer(inv -> {
            Collection<String> wanted = inv.getArgument(0);
            return directory.stream().filter(u -> wanted.contains(u.getUserEmail().toLowerCase())).toList();
        });
        groups = mock(GroupRepo.class);
        audits = mock(AlertPushTargetingAuditRepo.class);
        dispatcher = new AlertDispatchService(null, null, null, users, null, notifications, null,
                new PushPolicyService(prefRepo, rate));
        dispatcher.loadTemplates();
        dispatcher.setTargetingSupport(groups, audits);
    }

    private UserInfo person(String email, double lat, double lng) {
        UserInfo u = PushTargetingTest.at(email, lat, lng);
        directory.add(u);
        return u;
    }

    @SuppressWarnings("unchecked")
    @Test
    void aWarningWithAnOutlinePushesInsideNotTheOldRadius_andHouseholdsAtHomeWithTheirOwnTitle() {
        NormalizedAlert tornado = TestAlerts.nws("Tornado Warning").response("Shelter")
                .geometry(PushTargetingTest.SQUARE).build();
        DispatchTemplate tpl = dispatcher.matchForAlert(tornado).orElseThrow();

        UserInfo inside = person("inside@x", 40.40, -111.85);
        UserInfo near = person("near@x", 40.40, -111.60);           // ~17 km out: the old rule pushed this
        UserInfo travelling = person("mom@x", 45.0, -100.0);        // not a candidate here; home is inside
        Group home = new Group();
        home.setGroupType("Household");
        home.setHomeLocation(new GeoPoint(40.41, -111.86));
        home.setOwnerEmail("mom@x");
        when(groups.findHouseholdsWithHomeInBounds(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(home));

        dispatcher.pushSevereAlert(tornado, tpl, AlertSafetyPolicy.evaluate(tornado, tpl),
                PushTargetingTest.COORD, List.of(inside, near));

        ArgumentCaptor<List<UserInfo>> to = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(2)).sendHazardAlertBatch(to.capture(), title.capture(), anyString(), anyString(),
                anyString(), any(), anyBoolean(), any(), any(), any());
        assertThat(PushTargetingTest.emails(to.getAllValues().get(0))).containsExactly("inside@x");
        assertThat(title.getAllValues().get(0)).doesNotStartWith(AlertDispatchService.AT_HOME_PREFIX);
        assertThat(PushTargetingTest.emails(to.getAllValues().get(1))).containsExactly("mom@x");
        assertThat(title.getAllValues().get(1)).startsWith(AlertDispatchService.AT_HOME_PREFIX);

        ArgumentCaptor<AlertPushTargetingAudit> row = ArgumentCaptor.forClass(AlertPushTargetingAudit.class);
        verify(audits).save(row.capture());
        assertThat(row.getValue().getBasis()).isEqualTo("outline+5km");
        assertThat(row.getValue().getReachedHere()).isEqualTo(1);
        assertThat(row.getValue().getReachedHome()).isEqualTo(1);
        assertThat(row.getValue().getDroppedVsProximity()).isEqualTo(1);
        assertThat(travelling).isNotNull();
    }

    // Earthquakes stay on the 80 km rule: EarthquakePushTest (unchanged, green)
    // drives real USGS payloads through the same path. NWS-only targeting is
    // structural (the branch in pushSevereAlert), so a quake writes no audit row.
}
