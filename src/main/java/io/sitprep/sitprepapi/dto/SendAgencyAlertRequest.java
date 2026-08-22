package io.sitprep.sitprepapi.dto;

import java.time.Instant;
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
 * @param effectiveUntil when the advisory stops being in effect (V60).
 *                       OPTIONAL — null renders as "until further notice",
 *                       which is the honest reading of an advisory with no
 *                       stated end, and is the correct answer for a standing
 *                       notice. A dispatched NWS alert derives this from the
 *                       feed instead; only a human-composed alert states it.
 *                       Distinct from the pin window, which is placement.
 */
public record SendAgencyAlertRequest(
        String title,
        String body,
        String officialTier,
        List<String> affectedZips,
        String idempotencyKey,
        Instant effectiveUntil
) {}
