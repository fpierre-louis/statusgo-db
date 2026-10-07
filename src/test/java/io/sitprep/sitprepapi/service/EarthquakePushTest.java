package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.AlertPost;
import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.AlertPostRepo;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M6.0+ quakes push to people near the epicenter (EXEC-N, 2026-10-07).
 *
 * <p>Before: the USGS template's {@code dispatchMode: attention} meant no
 * earthquake ever reached {@code pushSevereAlert} — feed post only, and the
 * {@code USGS_QUAKE_MAJOR} quiet-hours bypass was unreachable. The template is
 * NOT edited (signed-off safety copy); {@code AlertDispatchService.isMajorQuakePush}
 * is the rule. Real templates, real {@link PushPolicyService}, mocked
 * repositories and a mocked {@link NotificationService}.</p>
 */
class EarthquakePushTest {

    /** Epicenter of the fixture quake (USGS Point is [lon, lat, depth]). */
    private static final double LAT = 34.0, LNG = -118.2;

    private final Map<String, UserAlertPreference> prefs = new HashMap<>();
    private AlertIngestService ingest;
    private AlertPostRepo alertPostRepo;
    private PostService posts;
    private UserInfoRepo users;
    private NominatimGeocodeService geocode;
    private NotificationService notifications;
    private AlertDispatchService dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        UserAlertPreferenceRepo prefRepo = mock(UserAlertPreferenceRepo.class);
        when(prefRepo.findByEmail(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(prefs.get((String) inv.getArgument(0))));
        when(prefRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RateLimiterService rateLimiter = mock(RateLimiterService.class);
        when(rateLimiter.tryConsume(any(), any())).thenReturn(true);
        PushPolicyService policy = new PushPolicyService(prefRepo, rateLimiter);

        ingest = mock(AlertIngestService.class);
        alertPostRepo = mock(AlertPostRepo.class);
        posts = mock(PostService.class);
        users = mock(UserInfoRepo.class);
        geocode = mock(NominatimGeocodeService.class);
        notifications = mock(NotificationService.class);
        dispatcher = new AlertDispatchService(ingest, alertPostRepo, posts, users, geocode,
                notifications, null, policy);
        dispatcher.loadTemplates();

        when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(
                new NominatimGeocodeService.Place(null, "Los Angeles", null, "CA", "us", "90012", "90012"));
        when(posts.create(any(), anyString())).thenReturn(postDto(101L));
        when(alertPostRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    /** PostDto is a ~90-component record (and records can't be mocked here): id set, rest defaulted. */
    private static PostDto postDto(Long id) throws Exception {
        var comps = PostDto.class.getRecordComponents();
        Class<?>[] types = new Class<?>[comps.length];
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            Class<?> t = comps[i].getType();
            types[i] = t;
            if ("id".equals(comps[i].getName())) args[i] = id;
            else if (t == boolean.class) args[i] = false;
            else if (t == int.class) args[i] = 0;
            else if (t == long.class) args[i] = 0L;
            else if (t == double.class) args[i] = 0d;
            else if (t.isPrimitive()) throw new IllegalStateException("unhandled primitive " + t);
        }
        return PostDto.class.getDeclaredConstructor(types).newInstance(args);
    }

    private static NormalizedAlert quake(double mag) throws Exception {
        return quake(mag, LAT, 600);
    }

    /** Same USGS id for the same magnitude, so a call with another lat is a revision of it. */
    private static NormalizedAlert quake(double mag, double lat, long ageSeconds) throws Exception {
        String json = "{\"type\":\"Feature\",\"id\":\"us7000exn" + (int) (mag * 10) + "\","
                + "\"properties\":{\"mag\":" + mag + ",\"place\":\"10 km N of Somewhere, CA\","
                + "\"time\":" + Instant.now().minusSeconds(ageSeconds).toEpochMilli() + "},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[" + LNG + "," + lat + ",10.0]}}";
        return new AlertIngestService(new NwsZoneService()).normalizeUsgs(new ObjectMapper().readTree(json));
    }

    private UserInfo user(String email, double lat, double lng, boolean asleep) {
        UserAlertPreference p = new UserAlertPreference();
        p.setUserEmail(email);
        if (asleep) {
            LocalTime now = LocalTime.now(ZoneOffset.UTC);
            p.setQuietHoursEnabled(true);
            p.setTimezone("UTC");
            p.setQuietStart(now.minusHours(2));
            p.setQuietEnd(now.plusHours(2));
        }
        prefs.put(email, p);
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setFcmtoken("tok-" + email);
        u.setLastKnownLat(lat);
        u.setLastKnownLng(lng);
        return u;
    }

    private void snapshot(NormalizedAlert a) {
        when(ingest.getSnapshot()).thenReturn(
                new AlertIngestService.Snapshot(List.of(a), Instant.now(), Instant.now()));
    }

    @SuppressWarnings("unchecked")
    private List<UserInfo> pushedOnce(ArgumentCaptor<String> title) {
        ArgumentCaptor<List<UserInfo>> c = ArgumentCaptor.forClass(List.class);
        verify(notifications).sendHazardAlertBatch(c.capture(), title.capture(), anyString(), anyString(),
                anyString(), any(), anyBoolean());
        return c.getValue();
    }

    // ── the rule ─────────────────────────────────────────────────────────

    @Test
    void anM62NearbyIsPushed_andStaysLaneAInsideQuietHours() throws Exception {
        NormalizedAlert m62 = quake(6.2);
        assertThat(m62.severity()).isEqualTo("Severe");
        snapshot(m62);
        UserInfo sleeper = user("near@x.com", LAT + 0.1, LNG, /* asleep */ true);   // ~11 km
        when(users.findPushablesWithLocation(any())).thenReturn(List.of(sleeper));

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        assertThat(pushedOnce(title)).extracting(UserInfo::getUserEmail).containsExactly("near@x.com");
        DispatchTemplate tpl = dispatcher.matchForAlert(m62).orElseThrow();
        assertThat(title.getValue())
                .as("the template's approved headline, unchanged")
                .isEqualTo(tpl.headline);
    }

    @Test
    void anM62FarAwayIsNotPushed() throws Exception {
        snapshot(quake(6.2));
        UserInfo far = user("far@x.com", LAT + 1.35, LNG, false);                     // ~150 km
        when(users.findPushablesWithLocation(any())).thenReturn(List.of(far));

        assertThat(dispatcher.dispatchOnce()).as("the feed post is still created").isEqualTo(1);

        verify(notifications, never()).sendHazardAlertBatch(anyList(), anyString(), anyString(),
                anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void anM58NearbyIsFeedOnly() throws Exception {
        NormalizedAlert m58 = quake(5.8);
        assertThat(m58.severity()).isEqualTo("Moderate");
        snapshot(m58);
        when(users.findPushablesWithLocation(any()))
                .thenReturn(List.of(user("near@x.com", LAT, LNG, false)));

        assertThat(dispatcher.dispatchOnce()).as("the feed post is created").isEqualTo(1);

        verify(posts).create(any(), anyString());
        verify(users, never()).findPushablesWithLocation(any());
        verify(notifications, never()).sendHazardAlertBatch(anyList(), anyString(), anyString(),
                anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void dedupePerAlertAndGeocellStillHolds() throws Exception {
        NormalizedAlert m62 = quake(6.2);
        snapshot(m62);
        when(users.findPushablesWithLocation(any()))
                .thenReturn(List.of(user("near@x.com", LAT, LNG, false)));
        AlertPost existing = new AlertPost();
        existing.setAlertId("USGS-" + m62.id());
        existing.setGeocellId("90012");
        when(alertPostRepo.findByAlertIdAndGeocellId(eq("USGS-" + m62.id()), eq("90012")))
                .thenReturn(Optional.empty(), Optional.of(existing));

        dispatcher.dispatchOnce();
        dispatcher.dispatchOnce();

        verify(posts, times(1)).create(any(), anyString());
        verify(notifications, times(1)).sendHazardAlertBatch(anyList(), anyString(), anyString(),
                anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void theRuleOnlyEscalatesAttention_andOnlyForUsgs() throws Exception {
        NormalizedAlert m62 = quake(6.2);
        DispatchTemplate tpl = dispatcher.matchForAlert(m62).orElseThrow();
        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(m62, tpl);
        assertThat(decision.dispatchMode())
                .as("the reviewed template decision is untouched")
                .isEqualTo(AlertSafetyPolicy.DispatchMode.ATTENTION);
        assertThat(AlertDispatchService.isMajorQuakePush(m62, tpl, decision)).isTrue();

        AlertSafetyPolicy.Decision suppressed = new AlertSafetyPolicy.Decision(
                AlertSafetyPolicy.DispatchMode.SUPPRESS, decision.guidanceMode(), decision.compatibility(),
                decision.capActions(), decision.movementDirective(), "test");
        assertThat(AlertDispatchService.isMajorQuakePush(m62, tpl, suppressed)).isFalse();

        NormalizedAlert severeNws = TestAlerts.nws("Flood Warning").severity("Severe").build();
        assertThat(AlertDispatchService.isMajorQuakePush(severeNws, tpl, decision)).isFalse();
    }

    // ── review fixes (2026-10-07) ────────────────────────────────────────

    /**
     * USGS revises an epicenter in place (same id). A revision that crosses
     * into another zip bucket passes the (alertId, geocell) dedupe — before the
     * once-per-alert guard it created a second AlertPost AND pushed the same
     * quake again, bypassing quiet hours both times.
     */
    @Test
    void aRevisedEpicenterInAnotherGeocellDoesNotPushTheSameQuakeAgain() throws Exception {
        NormalizedAlert first = quake(6.2, LAT, 600);
        NormalizedAlert revised = quake(6.2, LAT + 0.05, 300);
        assertThat(revised.id()).isEqualTo(first.id());
        when(users.findPushablesWithLocation(any()))
                .thenReturn(List.of(user("near@x.com", LAT, LNG, /* asleep */ true)));

        snapshot(first);
        dispatcher.dispatchOnce();

        // Next tick: the revised point reverse-geocodes to a different zip.
        when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(
                new NominatimGeocodeService.Place(null, "Los Angeles", null, "CA", "us", "90013", "90013"));
        when(alertPostRepo.existsByAlertId("USGS-" + first.id())).thenReturn(true);
        snapshot(revised);
        dispatcher.dispatchOnce();

        verify(notifications, times(1)).sendHazardAlertBatch(anyList(), anyString(), anyString(),
                anyString(), anyString(), any(), anyBoolean());
        verify(notifications, never()).logHazardAlertInboxOnly(anyList(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any());
    }

    /**
     * The 4.5_day feed keeps a quake for 24 hours. A dispatch that first
     * succeeds hours late (geocoder outage) must not wake people for it: the
     * M6 bypass exists because the push cannot wait, and this one already did.
     */
    @Test
    void anM62FirstDispatchedHoursLateIsFeedOnly() throws Exception {
        snapshot(quake(6.2, LAT, 3 * 3600));
        when(users.findPushablesWithLocation(any()))
                .thenReturn(List.of(user("near@x.com", LAT, LNG, true)));

        assertThat(dispatcher.dispatchOnce()).as("the feed post is still created").isEqualTo(1);

        verify(users, never()).findPushablesWithLocation(any());
        verify(notifications, never()).sendHazardAlertBatch(anyList(), anyString(), anyString(),
                anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void theFreshnessWindowIsTwoHoursFromOrigin_andUnknownOriginNeverPushes() throws Exception {
        NormalizedAlert m62 = quake(6.2);
        DispatchTemplate tpl = dispatcher.matchForAlert(m62).orElseThrow();
        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(m62, tpl);
        Instant origin = Instant.parse(m62.startedAt());

        assertThat(AlertDispatchService.isMajorQuakePush(m62, tpl, decision,
                origin.plus(AlertDispatchService.MAJOR_QUAKE_PUSH_MAX_AGE))).isTrue();
        assertThat(AlertDispatchService.isMajorQuakePush(m62, tpl, decision,
                origin.plus(AlertDispatchService.MAJOR_QUAKE_PUSH_MAX_AGE).plusSeconds(1))).isFalse();

        NormalizedAlert noTime = new AlertIngestService(new NwsZoneService()).normalizeUsgs(new ObjectMapper().readTree(
                "{\"type\":\"Feature\",\"id\":\"us7000notime\",\"properties\":{\"mag\":6.4,"
                        + "\"place\":\"Somewhere\"},\"geometry\":{\"type\":\"Point\","
                        + "\"coordinates\":[" + LNG + "," + LAT + ",10.0]}}"));
        assertThat(noTime.startedAt()).isNull();
        assertThat(AlertDispatchService.isMajorQuakePush(noTime, tpl, decision)).isFalse();
    }
}
