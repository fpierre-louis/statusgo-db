package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * A hazard report as the map, the feed card and the router read it.
 *
 * <p>Deliberately NO reporter field — not an email, not a name, not an id.
 * Neighbors see how many people confirmed and how recently; never who
 * reported (the share-feature audit's biggest risk was exactly this leak).</p>
 *
 * @param id              the post id
 * @param state           {@code reported | confirmed | official} on the read path
 *                        ({@code cleared | expired} only in a response to the
 *                        action that ended it)
 * @param confirmations   distinct people who said "still there" in the last 60 min
 * @param viewerVote      the signed-in viewer's own vote, else null
 * @param photoUrl        the report's first photo as a public CDN URL, else null.
 *                        The map draws it on the report's card (map pins v2,
 *                        2026-10-10). The post's photos are already public on
 *                        the feed card; the URL carries no reporter identity.
 */
public record HazardDto(
        Long id,
        String category,
        String label,
        boolean blocksRoutes,
        Double lat,
        Double lng,
        int radiusM,
        String state,
        long confirmations,
        Instant lastConfirmedAt,
        Instant reportedAt,
        Instant expiresAt,
        boolean hasPhoto,
        String note,
        String viewerVote,
        String photoUrl
) {}
