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
            new TokenDefinition(TokenKey.PLAN_ARCHITECT, TokenScope.HOUSEHOLD, "Plan Architect",
                    "Your household has a primary evacuation route, a meeting place and an emergency contact circle.",
                    "Set a primary evacuation route, a meeting place and two emergency contacts.",
                    "plan-architect", "/evacuation-wizard", 10),
            new TokenDefinition(TokenKey.MEETING_POINT, TokenScope.HOUSEHOLD, "Meeting Point",
                    "Your household agreed on a place to meet.",
                    "Choose a place your household will meet.",
                    "meeting-point", "/evacuation-wizard", 20),
            new TokenDefinition(TokenKey.CONTACT_CIRCLE, TokenScope.HOUSEHOLD, "Contact Circle",
                    "Your household has at least two emergency contacts saved.",
                    "Save two emergency contacts to a contact group.",
                    "contact-circle", "/emergency-contacts", 30),
            new TokenDefinition(TokenKey.DRILL_CREW, TokenScope.HOUSEHOLD, "Drill Crew",
                    "Your household completed its first practice drill.",
                    "Run any practice drill together.",
                    "drill-crew", "/practice", 40),
            new TokenDefinition(TokenKey.PRACTICE_CADENCE, TokenScope.HOUSEHOLD, "Practice Cadence",
                    "Your household has practiced three different drills.",
                    "Practice three different drills.",
                    "practice-cadence", "/practice", 50),
            new TokenDefinition(TokenKey.STOCKPILE_STEWARD, TokenScope.HOUSEHOLD, "Stockpile Steward",
                    "Your go bag is packed and half your home stockpile is in place.",
                    "Pack most of a go bag and check off half your home stockpile.",
                    "stockpile-steward", "/go-bag", 60),
            new TokenDefinition(TokenKey.HOUSEHOLD_READY, TokenScope.HOUSEHOLD, "Household Ready",
                    "Your household holds Plan Architect, Stockpile Steward and Drill Crew.",
                    "Earn Plan Architect, Stockpile Steward and Drill Crew.",
                    "household-ready", null, 70),

            // ── Individual ──────────────────────────────────────────────────
            new TokenDefinition(TokenKey.FIRST_NEIGHBOR_SIGNAL, TokenScope.USER, "First Neighbor Signal",
                    "You shared your first post with neighbors nearby.",
                    "Share something useful with neighbors nearby.",
                    "neighbor-signal", "/community", 110),
            new TokenDefinition(TokenKey.LOCAL_HAZARD_REPORTER, TokenScope.USER, "Local Hazard Reporter",
                    "You reported a hazard near you.",
                    "Earned the first time you report a hazard near you.",
                    "hazard-reporter", null, 120),
            new TokenDefinition(TokenKey.GROUND_TRUTH, TokenScope.USER, "Ground Truth",
                    "You confirmed what is on the map near you.",
                    "Confirm a place or hazard on the map when you are there.",
                    "ground-truth", "/map", 130),
            new TokenDefinition(TokenKey.HELPFUL_QUESTION, TokenScope.USER, "Helpful Question",
                    "You asked your first preparedness question.",
                    "Ask a preparedness or local-safety question.",
                    "helpful-question", "/ask/q/new", 140),
            new TokenDefinition(TokenKey.PREP_TIP_SHARER, TokenScope.USER, "Prep Tip Sharer",
                    "You shared your first preparedness tip.",
                    "Share a tip that helped you prepare.",
                    "tip-sharer", "/ask/tips/new", 150),
            new TokenDefinition(TokenKey.ANSWERED_THE_CALL, TokenScope.USER, "Answered the Call",
                    "You answered a neighbor's question.",
                    "Answer a question in Ask.",
                    "answered-call", "/ask", 160),
            new TokenDefinition(TokenKey.TRUSTED_ANSWER, TokenScope.USER, "Trusted Answer",
                    "A neighbor accepted your answer.",
                    "Earned when someone accepts your answer.",
                    "trusted-answer", null, 170),
            new TokenDefinition(TokenKey.HELPING_HAND, TokenScope.USER, "Helping Hand",
                    "A neighbor confirmed your post or up-voted your answer or tip.",
                    "Earned when a neighbor confirms your post or up-votes your answer or tip.",
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
