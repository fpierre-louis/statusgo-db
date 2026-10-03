package io.sitprep.sitprepapi.gamification;

/**
 * What the client renders for one token. Copy ships with the backend so the
 * frontend never hardcodes a token's name or criteria.
 *
 * @param description   the earned line; also the unlock toast / inbox body
 * @param lockedHint    what a locked tile says
 * @param iconKey       the glyph name in the frontend's token glyph set
 * @param nextStepRoute where a locked tile's button goes, or null for no button
 *                      (tokens that depend on someone else, or that must not
 *                      invite status-seeking — a hazard report)
 */
public record TokenDefinition(
        TokenKey key,
        TokenScope scope,
        String name,
        String description,
        String lockedHint,
        String iconKey,
        String nextStepRoute,
        int order
) {}
