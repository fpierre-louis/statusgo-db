package io.sitprep.sitprepapi.gamification;

/**
 * Something happened that might earn a token. It names the record; it is never
 * proof — the evaluator re-reads {@code sourceId} before awarding anything.
 *
 * @param actorEmail  who acted (lower-cased by the evaluator)
 * @param householdId the household a household event concerns; null for individual events
 * @param sourceId    the record behind the event — a post id, an answer id, a drill key
 */
public record TokenEvent(
        TokenEventType type,
        String actorEmail,
        String householdId,
        String sourceId
) {
    public static TokenEvent household(TokenEventType type, String actorEmail, String householdId, String sourceId) {
        return new TokenEvent(type, actorEmail, householdId, sourceId);
    }

    public static TokenEvent user(TokenEventType type, String actorEmail, Object sourceId) {
        return new TokenEvent(type, actorEmail, null, sourceId == null ? null : String.valueOf(sourceId));
    }
}
