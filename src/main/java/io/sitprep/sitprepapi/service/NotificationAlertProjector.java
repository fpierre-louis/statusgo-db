package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.AlertInterpretationSnapshot;
import io.sitprep.sitprepapi.domain.AlertSourceRevision;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.NotificationInboxRowDto;
import io.sitprep.sitprepapi.notifications.NotificationEventType;
import io.sitprep.sitprepapi.repo.AlertInterpretationSnapshotRepo;
import io.sitprep.sitprepapi.repo.AlertSourceRevisionRepo;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.AlertIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code row.alert} for an inbox page (S2 contract §5).
 *
 * <p>Identity and the delivered interpretation come from the row; the
 * lifecycle is the alert's state NOW. A whole page costs a fixed number of
 * statements however many rows it holds: one {@link AlertRecordService#lifecycleFor}
 * (zero statements when every alert is live, else three), one read of the
 * delivered revisions, one of the delivered snapshots. Nothing is read per row.</p>
 *
 * <p><b>Primary action.</b> Only while the alert is listed and current
 * ({@link AlertRecordService.Lifecycle#actionable}) AND the row delivered the
 * revision the lifecycle was judged on. A row about an older revision of a
 * still-live alert, an ended alert, or an alert no longer listed carries none:
 * a row is a record of what was said, and only the newest word may tell
 * anyone what to do. The text is the snapshot's, verbatim — nothing is
 * composed here.</p>
 *
 * <p><b>Legacy rows</b> (written before V110: no {@code alert_key}) resolve
 * their {@code referenceId} ({@code NWS-<id>}) through {@link AlertIdentity}.
 * Nothing resolvable → {@code alert} is null and the row stays a plainly
 * attributed record. Agency alerts have no canonical record → null.</p>
 */
@Service
public class NotificationAlertProjector {

    private static final Logger log = LoggerFactory.getLogger(NotificationAlertProjector.class);

    private final AlertRecordService records;
    private final AlertSourceRevisionRepo revisions;
    private final AlertInterpretationSnapshotRepo snapshots;
    private final AlertDispatchService dispatch;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public NotificationAlertProjector(AlertRecordService records,
                                      AlertSourceRevisionRepo revisions,
                                      AlertInterpretationSnapshotRepo snapshots,
                                      AlertDispatchService dispatch) {
        this.records = records;
        this.revisions = revisions;
        this.snapshots = snapshots;
        this.dispatch = dispatch;
    }

    /** row id → {@code alert}, for the rows that are about an official alert. */
    @Transactional(readOnly = true)
    public Map<Long, NotificationInboxRowDto.AlertRow> forRows(Collection<NotificationLog> rows) {
        Map<Long, NotificationInboxRowDto.AlertRow> out = new HashMap<>();
        if (rows == null || rows.isEmpty()) return out;

        // 1. Which rows are about which alert.
        Map<Long, String> refOf = new LinkedHashMap<>();       // row → the ref lifecycleFor is asked
        Map<Long, String> keyOf = new HashMap<>();             // row → dedupeKey
        for (NotificationLog r : rows) {
            if (r == null || r.getId() == null || !isOfficialAlertRow(r)) continue;
            String key = dedupeKeyOf(r);
            if (key == null) continue;
            keyOf.put(r.getId(), key);
            refOf.put(r.getId(), r.getAlertRevisionRef() != null ? r.getAlertRevisionRef() : key);
        }
        if (refOf.isEmpty()) return out;

        // 2. Lifecycle now — one batched call for the page.
        Map<String, AlertRecordService.Lifecycle> lifecycles;
        try {
            lifecycles = records.lifecycleFor(new LinkedHashSet<>(refOf.values()));
        } catch (Exception e) {
            log.warn("NotificationAlertProjector: lifecycle lookup failed: {}", e.getMessage());
            lifecycles = Map.of();
        }

        // 3. The delivered revisions (a legacy row: the newest held) and
        //    snapshots — one read each.
        Set<String> revisionRefs = new LinkedHashSet<>();
        Set<UUID> snapshotIds = new LinkedHashSet<>();
        for (NotificationLog r : rows) {
            if (r == null || !refOf.containsKey(r.getId())) continue;
            String delivered = r.getAlertRevisionRef();
            if (delivered == null) {
                AlertRecordService.Lifecycle lc = lifecycles.get(refOf.get(r.getId()));
                delivered = lc == null ? null : lc.revisionRef();
            }
            if (delivered != null) revisionRefs.add(delivered);
            if (r.getInterpretationSnapshotId() != null) snapshotIds.add(r.getInterpretationSnapshotId());
        }
        Map<String, NormalizedAlert> payloads = new HashMap<>();
        if (!revisionRefs.isEmpty()) {
            for (AlertSourceRevision rev : revisions.findAllById(revisionRefs)) {
                NormalizedAlert a = parse(rev.getPayload(), rev.getRevisionRef());
                if (a != null) payloads.put(rev.getRevisionRef(), a);
            }
        }
        Map<UUID, JsonNode> contents = new HashMap<>();
        if (!snapshotIds.isEmpty()) {
            for (AlertInterpretationSnapshot s : snapshots.findAllById(snapshotIds)) {
                JsonNode c = readTree(s.getContentJson());
                if (c != null) contents.put(s.getId(), c);
            }
        }

        // 4. Assemble.
        for (NotificationLog r : rows) {
            if (r == null || !refOf.containsKey(r.getId())) continue;
            AlertRecordService.Lifecycle lc = lifecycles.get(refOf.get(r.getId()));
            String revisionRef = r.getAlertRevisionRef();
            NormalizedAlert a = payloads.get(revisionRef != null ? revisionRef : lc == null ? null : lc.revisionRef());
            JsonNode content = r.getInterpretationSnapshotId() == null ? null
                    : contents.get(r.getInterpretationSnapshotId());
            out.put(r.getId(), assemble(r, keyOf.get(r.getId()), lc, a, content));
        }
        return out;
    }

    NotificationInboxRowDto.AlertRow assemble(NotificationLog r, String dedupeKey,
                                              AlertRecordService.Lifecycle lc,
                                              NormalizedAlert a, JsonNode content) {
        String revisionRef = r.getAlertRevisionRef();
        String threadKey = r.getAlertThreadKey() != null ? r.getAlertThreadKey()
                : lc != null && lc.threadKey() != null ? lc.threadKey()
                : a != null ? AlertThreads.threadKeyOf(a) : null;

        NotificationInboxRowDto.Lifecycle lifecycle = lc == null || lc.info() == null ? null
                : new NotificationInboxRowDto.Lifecycle(lc.state(), lc.current(), lc.info().reason());

        boolean onCurrentRevision = lc != null && revisionRef != null && revisionRef.equals(lc.revisionRef());
        AlertCardDto.PrimaryAction primary = lc != null && lc.actionable() && onCurrentRevision
                ? primaryActionOf(content) : null;

        DispatchTemplate tpl = a == null ? null : dispatch.matchForAlert(a).orElse(null);
        String source = a != null ? a.source() : sourceOf(dedupeKey);
        String issuer = textAt(content, "sourceIdentity", "name");
        if (issuer == null && source != null) issuer = TrustedSourceRegistry.of(source).name();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dedupeKey", dedupeKey);
        if (revisionRef != null) params.put("revisionRef", revisionRef);
        params.put("context", "current");
        List<AlertCardDto.Action> actions = List.of(new AlertCardDto.Action("OPEN_ALERT_DETAIL",
                java.util.Collections.unmodifiableMap(params),
                "/hazards?alert=" + URLEncoder.encode(dedupeKey, StandardCharsets.UTF_8)));

        return new NotificationInboxRowDto.AlertRow(
                dedupeKey,
                revisionRef,
                threadKey,
                r.getInterpretationSnapshotId() == null ? null : r.getInterpretationSnapshotId().toString(),
                a == null ? null : eventLabelOf(a),
                a == null ? null : AlertFeedService.tierOf(a, tpl),
                issuer,
                lifecycle,
                primary,
                a == null ? null : areaLabelOf(a.area()),
                a == null ? null : firstNonBlank(a.ends(), a.expires(), a.endsAt()),
                actions);
    }

    /** A hazard row from NWS / USGS / FEMA — never an agency alert, never a legacy {@code type='alert'} check-in. */
    static boolean isOfficialAlertRow(NotificationLog r) {
        if (!NotificationService.TYPE_HAZARD_ALERT.equals(r.getType())) return false;
        String ref = r.getReferenceId();
        if (ref != null && ref.startsWith(NotificationEventType.AGENCY_ALERT_REF_PREFIX)) return false;
        String key = r.getAlertKey();
        return key == null || !key.startsWith(AgencyAlertDispatchService.AGENCY_KEY_PREFIX);
    }

    /** The row's alert key, else its referenceId parsed ({@code NWS-<id>} → {@code NWS:<id>}). */
    static String dedupeKeyOf(NotificationLog r) {
        if (r.getAlertKey() != null && !r.getAlertKey().isBlank()) return r.getAlertKey();
        AlertIdentity.Resolved resolved = AlertIdentity.resolve(r.getReferenceId(), null);
        return resolved.isResolved() ? resolved.dedupeKey() : null;
    }

    private static String sourceOf(String dedupeKey) {
        int colon = dedupeKey == null ? -1 : dedupeKey.indexOf(':');
        return colon > 0 ? dedupeKey.substring(0, colon) : null;
    }

    private static AlertCardDto.PrimaryAction primaryActionOf(JsonNode content) {
        JsonNode p = content == null ? null : content.path("interpreter").path("primaryAction");
        if (p == null || !p.isObject()) return null;
        String text = text(p.get("text"));
        if (text == null) return null;
        return new AlertCardDto.PrimaryAction(text, text(p.get("kind")), text(p.get("attribution")));
    }

    /** The issuer's product name, else its headline. */
    static String eventLabelOf(NormalizedAlert a) {
        String e = a.event() == null ? null : a.event().trim();
        if (e != null && !e.isEmpty()) return e;
        return a.headline() == null || a.headline().isBlank() ? null : a.headline().trim();
    }

    /** {@code "Salt Lake; Davis; Weber"} → {@code "Salt Lake + 2 more"} — the issuer's names, shortened, never rewritten. */
    static String areaLabelOf(String areaDesc) {
        if (areaDesc == null || areaDesc.isBlank()) return null;
        String[] parts = areaDesc.split(";");
        List<String> names = new java.util.ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) names.add(t);
        }
        if (names.isEmpty()) return null;
        return names.size() == 1 ? names.get(0) : names.get(0) + " + " + (names.size() - 1) + " more";
    }

    private static String textAt(JsonNode content, String obj, String field) {
        return content == null ? null : text(content.path(obj).get(field));
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull() || !n.isValueNode()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private NormalizedAlert parse(String payload, String ref) {
        if (payload == null) return null;
        try {
            return json.readValue(payload, NormalizedAlert.class);
        } catch (Exception e) {
            log.warn("NotificationAlertProjector: unreadable revision payload {}: {}", ref, e.getMessage());
            return null;
        }
    }

    private JsonNode readTree(String s) {
        if (s == null) return null;
        try {
            return json.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }
}
