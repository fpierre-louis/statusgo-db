package io.sitprep.sitprepapi.dto;

import java.util.List;

/**
 * Wire shapes for the alert-preset endpoints (CONTRACT §6, Ready for More B2).
 * A preset is a convenience bundle over {@link UserAlertPreferenceDto}; the
 * preference fields stay the truth and the preset label is never persisted.
 */
public final class AlertPresetDtos {

    private AlertPresetDtos() {}

    /** One preset as the FE renders it. {@code active} is derived on every read. */
    public record AlertPresetDto(
            String key,
            String title,
            String description,
            String safetyNote,
            boolean active
    ) {}

    /** {@code GET /api/userinfo/me/alert-presets} and the apply response. */
    public record AlertPresetsResponse(
            List<AlertPresetDto> presets,
            UserAlertPreferenceDto current
    ) {}

    /** Optional body of {@code POST …/{key}/apply}. Only QUIET_HOURS reads it. */
    public record ApplyAlertPresetRequest(String timezone) {}
}
