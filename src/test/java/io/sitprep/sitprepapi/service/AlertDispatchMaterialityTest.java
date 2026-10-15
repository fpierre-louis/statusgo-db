package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.AlertPost;
import io.sitprep.sitprepapi.domain.AlertSourceRevision;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.AlertInterpretationSnapshotRepo;
import io.sitprep.sitprepapi.repo.AlertPostRepo;
import io.sitprep.sitprepapi.repo.AlertSourceRevisionRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.NotificationService.AlertRowRefs;
import io.sitprep.sitprepapi.util.AlertSourceFingerprint;
import io.sitprep.sitprepapi.util.Vtec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2 C5 — R14 materiality in dispatch (contract §5), tick by tick.
 *
 * <p>Real templates, real safety policy, real {@link PushPolicyService}; the
 * notification writer, the posts and every repository are in-memory fakes.
 * The fake writer records each row it was asked to write — with the alert
 * columns the dispatcher handed it — and the fake log answers the
 * dispatcher's "what did this thread last deliver" from those rows, the way
 * the real {@code notification_log} would.</p>
 */
class AlertDispatchMaterialityTest {

    private static final double LAT = 33.45, LNG = -112.07;
    /** A small square around the push point (GeoJSON is [lon, lat]). */
    private static final Map<String, Object> POLYGON = polygon(LNG, LAT, 0.05);

    private final Map<String, UserAlertPreference> prefs = new HashMap<>();
    private final List<NotificationLog> rows = new ArrayList<>();
    private final Map<String, NormalizedAlert> stored = new HashMap<>();   // revisionRef -> payload
    private final Map<String, AlertPost> posts = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final ObjectMapper json = new ObjectMapper();

    private AlertIngestService ingest;
    private NotificationService notifications;
    private NotificationLogRepo log;
    private InterpretationRecorder recorder;
    private AlertDispatchService dispatcher;
    private UserInfoRepo users;

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
        AlertPostRepo alertPostRepo = mock(AlertPostRepo.class);
        when(alertPostRepo.findByAlertIdAndGeocellId(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(posts.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(alertPostRepo.existsByAlertId(anyString()))
                .thenAnswer(inv -> posts.keySet().stream().anyMatch(k -> k.startsWith(inv.getArgument(0) + "|")));
        when(alertPostRepo.save(any())).thenAnswer(inv -> {
            AlertPost ap = inv.getArgument(0);
            posts.put(ap.getAlertId() + "|" + ap.getGeocellId(), ap);
            return ap;
        });
        PostService postService = mock(PostService.class);
        when(postService.create(any(), anyString())).thenReturn(postDto(101L));
        users = mock(UserInfoRepo.class);
        NominatimGeocodeService geocode = mock(NominatimGeocodeService.class);
        when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(
                new NominatimGeocodeService.Place(null, "Phoenix", null, "AZ", "us", "85004", "85004"));

        notifications = mock(NotificationService.class);
        when(notifications.sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString(),
                any(), anyBoolean(), any(), any(), any())).thenAnswer(inv -> {
                    record(inv.getArgument(0), inv.getArgument(1), inv.getArgument(3), inv.getArgument(9), "A");
                    return new NotificationService.HazardBatchResult(1, 1, 0, null);
                });
        when(notifications.logHazardAlertInboxOnly(anyList(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any())).thenAnswer(inv -> {
                    List<UserInfo> to = inv.getArgument(0);
                    record(to, inv.getArgument(1), inv.getArgument(3), inv.getArgument(8), "B");
                    return to.size();
                });

        log = mock(NotificationLogRepo.class);
        when(log.findLatestDeliveredInThread(any(), any(), any(), any())).thenAnswer(inv -> matching(
                inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)).stream()
                .max(Comparator.comparing(NotificationLog::getTimestamp).thenComparing(NotificationLog::getId)));
        when(log.findThreadRecipients(any(), any(), any(), any())).thenAnswer(inv -> matching(
                inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)).stream()
                .map(NotificationLog::getRecipientEmail).distinct().toList());

        AlertSourceRevisionRepo revisionRepo = mock(AlertSourceRevisionRepo.class);
        when(revisionRepo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(
                revisionRow(inv.getArgument(0))));
        when(revisionRepo.findByDedupeKeyIn(any())).thenAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            return stored.keySet().stream()
                    .filter(ref -> keys.contains(ref.substring(0, ref.lastIndexOf('@'))))
                    .map(this::revisionRow).toList();
        });
        AlertInterpretationSnapshotRepo snapshotRepo = mock(AlertInterpretationSnapshotRepo.class);
        when(snapshotRepo.findById(any())).thenReturn(Optional.empty());

        recorder = mock(InterpretationRecorder.class);
        when(recorder.liveSnapshotId(any(), any(), any())).thenAnswer(inv -> {
            NormalizedAlert a = inv.getArgument(0);
            return UUID.nameUUIDFromBytes(AlertSourceFingerprint.revisionRef(a).getBytes()).toString();
        });

        dispatcher = new AlertDispatchService(ingest, alertPostRepo, postService, users, geocode,
                notifications, new NwsZoneService(), policy);
        dispatcher.loadTemplates();
        dispatcher.setDeliveryRecords(log, revisionRepo, snapshotRepo);
        dispatcher.setInterpretationRecorder(recorder);

        when(users.findPushablesWithLocation(any())).thenReturn(List.of(
                user("near@x.com", LAT, LNG), user("also@x.com", LAT + 0.02, LNG)));
    }

    // ── fakes ────────────────────────────────────────────────────────────

    private void record(List<UserInfo> to, String title, String referenceId, AlertRowRefs refs, String lane) {
        for (UserInfo u : to) {
            NotificationLog row = new NotificationLog(u.getUserEmail(), NotificationService.TYPE_HAZARD_ALERT,
                    null, title, "body", referenceId, "/hazards", null,
                    Instant.ofEpochMilli(1_800_000_000_000L + ids.get()), true, null);
            row.setId(ids.incrementAndGet());
            row.setLane(lane);
            if (refs != null) refs.applyTo(row);
            rows.add(row);
        }
    }

    private List<NotificationLog> matching(String threadKey, Collection<String> keys, Collection<String> legacy) {
        List<NotificationLog> out = new ArrayList<>();
        for (NotificationLog r : rows) {
            boolean hit = (threadKey != null && threadKey.equals(r.getAlertThreadKey()))
                    || (keys != null && r.getAlertKey() != null && keys.contains(r.getAlertKey()))
                    || (legacy != null && r.getAlertKey() == null && legacy.contains(r.getReferenceId()));
            if (hit) out.add(r);
        }
        return out;
    }

    private AlertSourceRevision revisionRow(String ref) {
        NormalizedAlert a = stored.get(ref);
        if (a == null) return null;
        AlertSourceRevision r = new AlertSourceRevision();
        r.setRevisionRef(ref);
        r.setDedupeKey(ref.substring(0, ref.lastIndexOf('@')));
        try {
            r.setPayload(json.writeValueAsString(a));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        r.setFirstSeenAt(Instant.now());
        r.setLastSeenAt(Instant.now());
        return r;
    }

    /** One ingest + dispatch tick: the recorder has stored every listed revision, as it does after a poll. */
    private void tick(NormalizedAlert... listed) {
        for (NormalizedAlert a : listed) stored.put(AlertSourceFingerprint.revisionRef(a), a);
        when(ingest.getSnapshot()).thenReturn(
                new AlertIngestService.Snapshot(List.of(listed), Instant.now(), Instant.now()));
        dispatcher.dispatchOnce();
    }

    private void pushes(int n) {
        verify(notifications, times(n)).sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(),
                anyString(), any(), anyBoolean(), any(), any(), any());
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static final String EVENT_BEGINS = "2026-10-14T18:00:00Z";

    private static Vtec vtec(String action) {
        return new Vtec(action, "KPSR", "TO", "W", "0042", EVENT_BEGINS, "2099-10-14T19:00:00Z", 2026);
    }

    /** A live Tornado Warning: critical push, shelter, the event KPSR.TO.W.0042.2026. */
    private static TestAlerts.Builder tornado(String id, String action) {
        return TestAlerts.nws("Tornado Warning")
                .id(id)
                .response("Shelter")
                .messageType("NEW".equals(action) ? "Alert" : "Update")
                .geometry(POLYGON)
                .ugc(List.of("AZC013"))
                .sent("2026-10-14T18:00:00Z")
                .ends("2099-10-14T19:00:00Z")
                .expires("2099-10-14T19:00:00Z")
                .endsAt("2099-10-14T19:00:00Z")
                .vtec(List.of(vtec(action)));
    }

    private static NormalizedAlert quake(String id, double mag, String pager, int tsunami, double lat) throws Exception {
        String js = "{\"type\":\"Feature\",\"id\":\"" + id + "\","
                + "\"properties\":{\"mag\":" + mag + ",\"place\":\"10 km N of Phoenix, AZ\","
                + "\"time\":" + Instant.now().minusSeconds(600).toEpochMilli() + ","
                + (pager == null ? "" : "\"alert\":\"" + pager + "\",")
                + "\"tsunami\":" + tsunami + "},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[" + LNG + "," + lat + ",10.0]}}";
        return new AlertIngestService(new NwsZoneService()).normalizeUsgs(new ObjectMapper().readTree(js));
    }

    // ── first delivery: the V110 columns ────────────────────────────────

    @Test
    void theFirstDeliveryStampsKeyRevisionThreadSnapshotAndCategory() {
        NormalizedAlert w1 = tornado("urn:oid:w1", "NEW").build();
        tick(w1);

        ArgumentCaptor<AlertRowRefs> refs = ArgumentCaptor.forClass(AlertRowRefs.class);
        ArgumentCaptor<PushPolicyService.Category> category = ArgumentCaptor.forClass(PushPolicyService.Category.class);
        verify(notifications).sendHazardAlertBatch(anyList(), anyString(), anyString(), anyString(), anyString(),
                any(), anyBoolean(), any(), category.capture(), refs.capture());
        assertThat(category.getValue()).isEqualTo(PushPolicyService.Category.NWS_SEVERE_EXTREME);
        AlertRowRefs r = refs.getValue();
        assertThat(r).isNotNull();
        assertThat(r.alertKey()).isEqualTo("NWS:urn:oid:w1");
        assertThat(r.revisionRef()).isEqualTo(AlertSourceFingerprint.revisionRef(w1));
        assertThat(r.threadKey()).isEqualTo("VTEC:KPSR.TO.W.0042.2026");
        assertThat(r.snapshotId()).isEqualTo(
                UUID.nameUUIDFromBytes(AlertSourceFingerprint.revisionRef(w1).getBytes()).toString());
    }

    // ── NWS updates ─────────────────────────────────────────────────────

    @Test
    void aConWithNoChange_writesNoRowAndSendsNoPush() {
        tick(tornado("urn:oid:w1", "NEW").build());
        tick(tornado("urn:oid:w2", "CON").references(List.of("urn:oid:w1")).build());
        pushes(1);
        verify(notifications, never()).logHazardAlertInboxOnly(anyList(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any());
    }

    @Test
    void anExtOfMoreThanFifteenMinutes_isMaterial() {
        tick(tornado("urn:oid:w1", "NEW").build());
        tick(tornado("urn:oid:w2", "EXT").references(List.of("urn:oid:w1"))
                .ends("2099-10-14T19:45:00Z").expires("2099-10-14T19:45:00Z").endsAt("2099-10-14T19:45:00Z")
                .build());
        pushes(2);
    }

    @Test
    void anEndMovedFifteenMinutesOrLess_isNotMaterial() {
        tick(tornado("urn:oid:w1", "NEW").build());
        tick(tornado("urn:oid:w2", "EXT").references(List.of("urn:oid:w1"))
                .ends("2099-10-14T19:15:00Z").expires("2099-10-14T19:15:00Z").endsAt("2099-10-14T19:15:00Z")
                .build());
        pushes(1);
    }

    @Test
    void anAreaChange_isMaterial_zonesOrGeometry() {
        tick(tornado("urn:oid:w1", "NEW").build());
        tick(tornado("urn:oid:w2", "EXA").references(List.of("urn:oid:w1"))
                .ugc(List.of("AZC013", "AZC021")).build());
        pushes(2);
        tick(tornado("urn:oid:w3", "CON").references(List.of("urn:oid:w2"))
                .ugc(List.of("AZC013", "AZC021")).geometry(polygon(LNG, LAT, 0.08)).build());
        pushes(3);
    }

    @Test
    void aSeverityOrUrgencyChange_isMaterial() {
        tick(tornado("urn:oid:w1", "NEW").build());
        tick(tornado("urn:oid:w2", "CON").references(List.of("urn:oid:w1")).severity("Extreme").build());
        pushes(2);
    }

    @Test
    void theSameRevisionNeverDeliversTwice_evenAfterARestart() {
        NormalizedAlert w1 = tornado("urn:oid:w1", "NEW").build();
        tick(w1);
        tick(w1);
        pushes(1);

        // A fresh process (cold in-memory state) over the same delivery records.
        AlertDispatchService restarted = rebuild();
        when(ingest.getSnapshot()).thenReturn(new AlertIngestService.Snapshot(List.of(w1), Instant.now(), Instant.now()));
        restarted.dispatchOnce();
        pushes(1);
    }

    @Test
    void aTemplateCopyEdit_triggersNothing() {
        NormalizedAlert w1 = tornado("urn:oid:w1", "NEW").build();
        tick(w1);

        // The Tornado Warning template's headline, body and steps rewritten —
        // what a copy review changes — and nothing else.
        AlertDispatchService edited = rebuild();
        @SuppressWarnings("unchecked")
        List<AlertDispatchService.DispatchTemplate> original =
                (List<AlertDispatchService.DispatchTemplate>) ReflectionTestUtils.getField(edited, "templates");
        List<AlertDispatchService.DispatchTemplate> copyEdited = new ArrayList<>();
        for (AlertDispatchService.DispatchTemplate t : original) {
            copyEdited.add(t.eventAny != null && t.eventAny.contains("Tornado Warning") ? copyEdit(t) : t);
        }
        ReflectionTestUtils.setField(edited, "templates", List.copyOf(copyEdited));
        assertThat(edited.matchForAlert(w1).orElseThrow().headline).isEqualTo("EDITED headline");

        when(ingest.getSnapshot()).thenReturn(new AlertIngestService.Snapshot(List.of(w1), Instant.now(), Instant.now()));
        edited.dispatchOnce();
        NormalizedAlert w2 = tornado("urn:oid:w2", "CON").references(List.of("urn:oid:w1")).build();
        stored.put(AlertSourceFingerprint.revisionRef(w2), w2);
        when(ingest.getSnapshot()).thenReturn(new AlertIngestService.Snapshot(List.of(w2), Instant.now(), Instant.now()));
        edited.dispatchOnce();
        pushes(1);
    }

    // ── verified endings ────────────────────────────────────────────────

    @Test
    void aVerifiedCancel_writesAnInboxRowForEveryoneTheThreadReached_andNoPush() {
        tick(tornado("urn:oid:w1", "NEW").build());
        NormalizedAlert can = tornado("urn:oid:w2", "CAN").references(List.of("urn:oid:w1"))
                .headline("The Tornado Warning has been cancelled.")
                .description("The storm which prompted the warning has weakened.")
                .build();
        assertThat(AlertDispatchService.isStillInForce(can)).isFalse();
        tick(can);

        pushes(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserInfo>> to = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AlertRowRefs> refs = ArgumentCaptor.forClass(AlertRowRefs.class);
        verify(notifications).logHazardAlertInboxOnly(to.capture(), title.capture(), body.capture(), anyString(),
                anyString(), any(), any(), any(), refs.capture());
        assertThat(to.getValue()).extracting(UserInfo::getUserEmail)
                .containsExactlyInAnyOrder("near@x.com", "also@x.com");
        assertThat(title.getValue()).isEqualTo("Tornado Warning cancelled");
        assertThat(body.getValue()).as("the issuer's words, verbatim")
                .isEqualTo("The storm which prompted the warning has weakened.");
        assertThat(refs.getValue().threadKey()).isEqualTo("VTEC:KPSR.TO.W.0042.2026");
        assertThat(refs.getValue().alertKey()).isEqualTo("NWS:urn:oid:w2");

        // And only once.
        tick(can);
        verify(notifications, times(1)).logHazardAlertInboxOnly(anyList(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any());
    }

    @Test
    void anExpiry_isNotAnEndingRow() {
        tick(tornado("urn:oid:w1", "NEW").build());
        tick(tornado("urn:oid:w2", "EXP").references(List.of("urn:oid:w1")).build());
        pushes(1);
        verify(notifications, never()).logHazardAlertInboxOnly(anyList(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any());
    }

    @Test
    void aCancelForAThreadNobodyWasToldAbout_writesNothing() {
        tick(tornado("urn:oid:w9", "CAN").build());
        verify(notifications, never()).logHazardAlertInboxOnly(anyList(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any());
    }

    // ── USGS same-id revisions ──────────────────────────────────────────

    @Test
    void aUsgsRevisionFrom5_9To6_2_isARow() throws Exception {
        tick(quake("us7000mat", 5.9, null, 0, LAT));
        pushes(0);
        tick(quake("us7000mat", 6.2, null, 0, LAT));
        pushes(1);
    }

    @Test
    void aUsgsMagnitudeStepUnder0_3_isNot_butAtLeast0_3_is() throws Exception {
        tick(quake("us7000mag", 6.2, null, 0, LAT));
        tick(quake("us7000mag", 6.4, null, 0, LAT));
        pushes(1);
        tick(quake("us7000mag", 6.5, null, 0, LAT));
        pushes(2);
    }

    @Test
    void aUsgsPagerOrTsunamiChange_isMaterial() throws Exception {
        tick(quake("us7000pg", 6.2, "green", 0, LAT));
        tick(quake("us7000pg", 6.2, "yellow", 0, LAT));
        pushes(2);
        tick(quake("us7000pg", 6.2, "yellow", 1, LAT));
        pushes(3);
    }

    @Test
    void aUsgsEpicentreRefinementAlone_isNotMaterial() throws Exception {
        tick(quake("us7000epi", 6.2, null, 0, LAT));
        tick(quake("us7000epi", 6.2, null, 0, LAT + 0.01));
        pushes(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private AlertDispatchService rebuild() {
        AlertDispatchService d = new AlertDispatchService(ingest,
                (AlertPostRepo) ReflectionTestUtils.getField(dispatcher, "alertPostRepo"),
                (PostService) ReflectionTestUtils.getField(dispatcher, "taskService"),
                users,
                (NominatimGeocodeService) ReflectionTestUtils.getField(dispatcher, "geocode"),
                notifications, new NwsZoneService(),
                (PushPolicyService) ReflectionTestUtils.getField(dispatcher, "pushPolicyService"));
        d.loadTemplates();
        d.setDeliveryRecords(log,
                (AlertSourceRevisionRepo) ReflectionTestUtils.getField(dispatcher, "revisions"),
                (AlertInterpretationSnapshotRepo) ReflectionTestUtils.getField(dispatcher, "snapshots"));
        d.setInterpretationRecorder(recorder);
        return d;
    }

    private static AlertDispatchService.DispatchTemplate copyEdit(AlertDispatchService.DispatchTemplate t) {
        return new AlertDispatchService.DispatchTemplate(t.source, t.eventAny, t.tier, t.incidentTypeAny,
                t.minMag, t.fallback, t.hazardType, "EDITED headline", "EDITED body.",
                List.of("EDITED step."), t.askTag, t.protectiveAction, t.compatibleResponseTypes,
                t.incompatibleResponseTypes, t.sitprep, t.evidence, t.safetyReview);
    }

    private UserInfo user(String email, double lat, double lng) {
        UserAlertPreference p = new UserAlertPreference();
        p.setUserEmail(email);
        prefs.put(email, p);
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setFcmtoken("tok-" + email);
        u.setLastKnownLat(lat);
        u.setLastKnownLng(lng);
        return u;
    }

    private static Map<String, Object> polygon(double lng, double lat, double d) {
        List<List<Double>> ring = List.of(
                List.of(lng - d, lat - d), List.of(lng + d, lat - d), List.of(lng + d, lat + d),
                List.of(lng - d, lat + d), List.of(lng - d, lat - d));
        return Map.of("type", "Polygon", "coordinates", List.of(ring));
    }

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

    @SuppressWarnings("unused")
    private static Set<String> set(String... v) {
        return new LinkedHashSet<>(List.of(v));
    }
}
