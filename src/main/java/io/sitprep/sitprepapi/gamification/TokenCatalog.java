package io.sitprep.sitprepapi.gamification;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The token catalog: one definition per {@link TokenKey}, in display order.
 * Copy reviewed in docs/epics/readiness_tokens/EXEC-T1-schema-catalog-api.md.
 */
public final class TokenCatalog {

    private TokenCatalog() {}

    private static final List<TokenDefinition> ALL = List.of(
            // ── Household ───────────────────────────────────────────────────
            new TokenDefinition(TokenKey.PLAN_ARCHITECT, TokenScope.HOUSEHOLD, "Plan in Place",
                    "Your household has a main evacuation route, a meeting spot, and people to call.",
                    "Add a main evacuation route, a meeting spot, and at least two emergency contacts.",
                    "plan-architect", "/evacuation-wizard", 10),
            new TokenDefinition(TokenKey.MEETING_POINT, TokenScope.HOUSEHOLD, "Meeting Spot",
                    "Your household chose where to meet.",
                    "Choose where your household will meet.",
                    "meeting-point", "/evacuation-wizard", 20),
            new TokenDefinition(TokenKey.CONTACT_CIRCLE, TokenScope.HOUSEHOLD, "People to Call",
                    "Your household saved at least two emergency contacts.",
                    "Save at least two people your household can call in an emergency.",
                    "contact-circle", "/emergency-contacts", 30),
            new TokenDefinition(TokenKey.DRILL_CREW, TokenScope.HOUSEHOLD, "First Practice",
                    "Your household completed its first practice.",
                    "Complete a practice together.",
                    "drill-crew", "/practice", 40),
            new TokenDefinition(TokenKey.PRACTICE_CADENCE, TokenScope.HOUSEHOLD, "Practiced Together",
                    "Your household completed three different practices.",
                    "Complete three different practices together.",
                    "practice-cadence", "/practice", 50),
            new TokenDefinition(TokenKey.STOCKPILE_STEWARD, TokenScope.HOUSEHOLD, "Supplies Taking Shape",
                    "Your household has most of a go bag packed and at least half its home supplies gathered.",
                    "Pack most of a go bag and gather at least half your home supplies.",
                    "stockpile-steward", "/go-bag", 60),
            new TokenDefinition(TokenKey.HOUSEHOLD_READY, TokenScope.HOUSEHOLD, "Ready Together",
                    "Your household has a plan, supplies, and practice behind it.",
                    "Put your plan in place, gather supplies, and complete your first practice.",
                    "household-ready", null, 70),

            // ── Individual ──────────────────────────────────────────────────
            new TokenDefinition(TokenKey.FIRST_NEIGHBOR_SIGNAL, TokenScope.USER, "Neighbor Hello",
                    "You shared your first post with nearby neighbors.",
                    "Share something useful with nearby neighbors.",
                    "neighbor-signal", "/community", 110),
            new TokenDefinition(TokenKey.LOCAL_HAZARD_REPORTER, TokenScope.USER, "Local Heads-Up",
                    "You shared a nearby hazard report.",
                    "Recognizes your first nearby hazard report.",
                    "hazard-reporter", null, 120),
            new TokenDefinition(TokenKey.GROUND_TRUTH, TokenScope.USER, "Nearby Check",
                    "You confirmed a place or hazard nearby.",
                    "Confirm nearby map information when you are there.",
                    "ground-truth", "/map", 130),
            new TokenDefinition(TokenKey.HELPFUL_QUESTION, TokenScope.USER, "First Question",
                    "You asked your first preparedness or local safety question.",
                    "Ask a preparedness or local safety question.",
                    "helpful-question", "/ask/q/new", 140),
            new TokenDefinition(TokenKey.PREP_TIP_SHARER, TokenScope.USER, "Shared a Tip",
                    "You shared your first preparedness tip.",
                    "Share a tip that helped you prepare.",
                    "tip-sharer", "/ask/tips/new", 150),
            new TokenDefinition(TokenKey.ANSWERED_THE_CALL, TokenScope.USER, "Neighbor Answer",
                    "You answered a neighbor's question.",
                    "Answer a question from a neighbor.",
                    "answered-call", "/ask", 160),
            new TokenDefinition(TokenKey.TRUSTED_ANSWER, TokenScope.USER, "Trusted Answer",
                    "A neighbor accepted your answer.",
                    "Recognizes an answer that a neighbor accepts.",
                    "trusted-answer", null, 170),
            new TokenDefinition(TokenKey.HELPING_HAND, TokenScope.USER, "Helping Hand",
                    "A neighbor found one of your contributions helpful.",
                    "Recognizes a post, answer, or tip that a neighbor confirms or upvotes.",
                    "helping-hand", null, 180)
    );

    private static final Map<TokenKey, TokenDefinition> BY_KEY = new EnumMap<>(TokenKey.class);
    static {
        for (TokenDefinition d : ALL) BY_KEY.put(d.key(), d);
    }

    /** Every definition, in display order. */
    public static List<TokenDefinition> all() {
        return ALL;
    }

    public static TokenDefinition get(TokenKey key) {
        return BY_KEY.get(key);
    }

    /** The definition for a stored {@code token_key}, or null if it is no longer in the catalog. */
    public static TokenDefinition forStoredKey(String tokenKey) {
        if (tokenKey == null) return null;
        try {
            return BY_KEY.get(TokenKey.valueOf(tokenKey));
        } catch (IllegalArgumentException retired) {
            return null;
        }
    }
}
