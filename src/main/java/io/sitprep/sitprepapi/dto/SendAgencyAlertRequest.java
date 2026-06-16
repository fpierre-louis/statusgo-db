package io.sitprep.sitprepapi.dto;

import java.util.List;

/**
 * Agency geo-alert send request (Phase 5 Slice D).
 *
 * @param affectedZips  zips to target. Server CLAMPS to the group's claimed
 *                      jurisdiction (can't blast zips you don't own); empty
 *                      ⇒ the full jurisdiction.
 * @param idempotencyKey client-minted key (one per send action) — the
 *                       double-tap guard. Optional; absent ⇒ server dedups
 *                       on content + a 10-minute window.
 */
public record SendAgencyAlertRequest(
        String title,
        String body,
        String officialTier,
        List<String> affectedZips,
        String idempotencyKey
) {}
