package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.UserAlertPreferenceDto;
import io.sitprep.sitprepapi.service.PushPolicyService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-user push + inbox preferences. Backs the FE settings page at
 * {@code /me/alert-preferences}. Auth-gated to the caller's own
 * record — no admin-impersonate path; this is purely "my prefs."
 *
 * <p>GET returns the full {@link UserAlertPreferenceDto} (auto-creating
 * the default record if one doesn't exist). PATCH merges only present
 * fields, so the FE can send single-field updates as toggles flip
 * rather than echoing the whole record on every change.</p>
 */
@RestController
@RequestMapping("/api/userinfo/me/alert-preferences")
public class UserAlertPreferenceResource {

    private final PushPolicyService pushPolicyService;

    public UserAlertPreferenceResource(PushPolicyService pushPolicyService) {
        this.pushPolicyService = pushPolicyService;
    }

    @GetMapping
    public ResponseEntity<UserAlertPreferenceDto> get() {
        String email = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(
                UserAlertPreferenceDto.fromEntity(pushPolicyService.getOrCreate(email))
        );
    }

    @PatchMapping
    public ResponseEntity<UserAlertPreferenceDto> patch(@RequestBody UserAlertPreferenceDto patch) {
        String email = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(
                UserAlertPreferenceDto.fromEntity(pushPolicyService.updatePreferences(email, patch))
        );
    }
}
