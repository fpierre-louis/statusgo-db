package io.sitprep.sitprepapi.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.AlertModeState;
import io.sitprep.sitprepapi.service.AlertModeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only mode-state lookup. Spec:
 * {@code docs/SPONSORED_AND_ALERT_MODE.md} "Endpoints needed".
 *
 * <pre>
 *   GET /api/alert-mode?lat=&amp;lng=
 *     → { state, since, triggers[], hysteresisExpiresAt, zipBucket }
 * </pre>
 *
 * <p>Unauthenticated — mode is a public-facing signal that any FE
 * surface can react to (CrisisBand boost, sponsored suppression,
 * verified-publisher pinning). It carries no user-specific data.</p>
 *
 * <p>Lazy-create semantics: a brand-new cell with no row yet returns
 * a synthetic {@code calm} response without persisting anything; the
 * cron tick creates the persistent row on its next pass when there's
 * actually trigger evidence.</p>
 */
@RestController
@RequestMapping("/api/alert-mode")
public class AlertModeResource {

    private final AlertModeService modeService;
    private final ObjectMapper json = new ObjectMapper();

    public AlertModeResource(AlertModeService modeService) {
        this.modeService = modeService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng
    ) {
        AlertModeState s = modeService.getForLatLng(lat, lng);
        Map<String, Object> body = new HashMap<>();
        body.put("zipBucket", s.getZipBucket());
        body.put("state", s.getState());
        body.put("since", s.getEnteredAt());
        body.put("hysteresisExpiresAt", s.getHysteresisExpiresAt());
        body.put("triggers", parseTriggers(s.getTriggersJson()));
        body.put("lastTriggerSeen", s.getLastTriggerSeen());
        return ResponseEntity.ok(body);
    }

    private List<JsonNode> parseTriggers(String triggersJson) {
        if (triggersJson == null || triggersJson.isBlank()) return List.of();
        try {
            JsonNode node = json.readTree(triggersJson);
            if (!node.isArray()) return List.of();
            List<JsonNode> out = new java.util.ArrayList<>(node.size());
            node.forEach(out::add);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unused")
    private static String safeStringify(Instant i) {
        return i == null ? null : i.toString();
    }
}
