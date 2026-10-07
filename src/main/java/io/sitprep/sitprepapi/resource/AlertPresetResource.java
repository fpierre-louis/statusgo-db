package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.AlertPresetDtos.AlertPresetsResponse;
import io.sitprep.sitprepapi.dto.AlertPresetDtos.ApplyAlertPresetRequest;
import io.sitprep.sitprepapi.service.AlertPresetService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alert presets (CONTRACT §6). Self only: the caller's verified email is the
 * only record either endpoint reads or writes. Unknown preset key → 404.
 */
@RestController
@RequestMapping("/api/userinfo/me/alert-presets")
public class AlertPresetResource {

    private final AlertPresetService presets;

    public AlertPresetResource(AlertPresetService presets) {
        this.presets = presets;
    }

    @GetMapping
    public ResponseEntity<AlertPresetsResponse> list() {
        String email = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(presets.list(email));
    }

    @PostMapping("/{key}/apply")
    public ResponseEntity<AlertPresetsResponse> apply(
            @PathVariable String key,
            @RequestBody(required = false) ApplyAlertPresetRequest body) {
        String email = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(presets.apply(email, key, body == null ? null : body.timezone()));
    }
}
