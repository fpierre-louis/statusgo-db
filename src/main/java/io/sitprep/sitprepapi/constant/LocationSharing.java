package io.sitprep.sitprepapi.constant;

import java.util.Map;

import io.sitprep.sitprepapi.service.HouseholdEventService;

/**
 * The one owner of "may this group see this member's location right now".
 *
 * <h2>Why this class exists</h2>
 *
 * {@code UserInfo.groupLocationSharing} is a <b>sparse</b> map — an entry is
 * written only when the user explicitly picks a mode on
 * {@code /account/map-visibility} — so every reader of it has to decide what a
 * <i>missing</i> entry means. Before 2026-08-31 three readers decided
 * independently and disagreed:
 *
 * <ul>
 *   <li>{@code GroupViewService.shouldShareLocation} — household
 *       {@code check-in-only}, everything else {@code never}. The enforcer.</li>
 *   <li>{@code UserInfoService.shouldShareLocationWithGroup} — a second,
 *       character-identical copy governing the WS location broadcast.</li>
 *   <li>The frontend's {@code MapVisibilityPage.defaultFor} — household
 *       {@code always}, everything else {@code check-in-only}. <b>Wrong, and
 *       the only one a human ever read.</b> A never-configured circle was told
 *       "Check-ins only — during an active alert" while the server revealed
 *       nothing during an alert either. That is a safety claim, and it was
 *       false.</li>
 * </ul>
 *
 * A fourth statement of the rule lived in {@code MeDto.ProfileDto}'s javadoc,
 * also wrong. Two of the four drifted because the rule was prose in four places
 * instead of code in one.
 *
 * <p><b>Every reader now delegates here, and the frontend no longer derives it
 * at all</b> — {@code MeDto.ProfileDto.groupLocationSharingEffective} ships the
 * resolved mode per group so no client has to know the defaults. A client that
 * re-derives this rule is reintroducing the bug.</p>
 *
 * <h2>The rule</h2>
 *
 * <p>Defaults for an unset entry: household → {@link #CHECK_IN_ONLY},
 * everything else → {@link #NEVER}. An unrecognised mode <b>fails closed</b>.</p>
 *
 * <p><b>{@link #NEVER} is an absolute opt-out</b> (locked 2026-07-02): a member
 * who selects it stays hidden <b>even during an Active alert</b>. This
 * deliberately protects users in extreme edge cases — e.g. domestic-violence
 * survivors — who cannot risk their location being broadcast to a group under
 * any circumstance. <b>Do NOT add an alert-time override that reveals
 * {@code never}.</b></p>
 *
 * <p>Note that a mode is a <i>permission</i>, not a promise of data: the client
 * only reports a position when the user has explicitly chosen {@link #ALWAYS}
 * somewhere (see the frontend's {@code useTrackPresence}, which treats an unset
 * entry as "not consent"), plus whatever a check-in gesture supplies. So
 * {@link #ALWAYS} means "you may see my last known fix", never "I am tracked".</p>
 */
public final class LocationSharing {

    /** The group may always see the member's last known fix. */
    public static final String ALWAYS = "always";
    /** Visible only while the group's alert is Active. */
    public static final String CHECK_IN_ONLY = "check-in-only";
    /** Never visible. Absolute — an active alert does not override it. */
    public static final String NEVER = "never";

    private LocationSharing() {}

    /** What an unset entry means for this group type. */
    public static String defaultFor(String groupType) {
        return HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(groupType)
                ? CHECK_IN_ONLY
                : NEVER;
    }

    /**
     * The mode actually in force — the member's explicit choice if they made
     * one, otherwise the default for the group type. Never null.
     *
     * <p>This is what {@code MeDto} ships to clients. It deliberately does NOT
     * tell you whether the value was chosen or defaulted; the sparse
     * {@code groupLocationSharing} map still carries that, and the settings page
     * needs both — a default is not a decision, and saying so is the difference
     * between a setting the user picked and one picked for them.</p>
     */
    public static String effectiveMode(Map<String, String> prefs, String groupId, String groupType) {
        String mode = (prefs == null || groupId == null) ? null : prefs.get(groupId);
        return (mode == null || mode.isBlank()) ? defaultFor(groupType) : mode;
    }

    /** Whether a resolved mode reveals a location given the group's alert state. */
    public static boolean visible(String mode, boolean alertActive) {
        if (mode == null) return false;
        return switch (mode) {
            case ALWAYS -> true;
            case NEVER -> false;              // absolute — never overridden, even in an alert
            case CHECK_IN_ONLY -> alertActive;
            default -> false;                 // unknown mode: fail closed (hidden)
        };
    }

    /** The gate, end to end. */
    public static boolean shouldShare(Map<String, String> prefs, String groupId,
                                      String groupType, boolean alertActive) {
        return visible(effectiveMode(prefs, groupId, groupType), alertActive);
    }
}
