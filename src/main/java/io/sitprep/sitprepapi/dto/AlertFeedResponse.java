package io.sitprep.sitprepapi.dto;

import java.util.List;

/**
 * The alert feed: cards plus the freshness and coverage context needed to
 * render them honestly.
 *
 * @see AlertCardDto
 */
public record AlertFeedResponse(List<AlertCardDto> alerts, Meta meta) {

    /**
     * @param lastSuccessAt when the last upstream poll succeeded; null before
     *   the first one ever does
     * @param isStale       true when {@code lastSuccessAt} is older than the
     *   poll cadence allows. <b>The frontend must not render a stale feed and
     *   an empty feed the same way</b> — Phase 1's reliability section found
     *   nothing distinguishing "calm day" from "the feed has been down for
     *   hours", which is the difference between reassurance and a lie.
     * @param coverageCaveat see {@link #COVERAGE_CAVEAT}
     */
    public record Meta(String lastSuccessAt, boolean isStale, String coverageCaveat) {}

    /**
     * What SitPrep does and does not promise about evacuation coverage
     * (audit P0-6).
     *
     * <h2>Why this ships on every response</h2>
     *
     * <p>Phase 1 recorded "no evacuation-order source exists". That was
     * <b>too strong</b>, and the correction is what makes this string
     * necessary rather than merely honest. NWS does relay
     * {@code Evacuation Immediate}, {@code Shelter In Place Warning},
     * {@code Civil Danger Warning} and six related civil products — they are
     * IPAWS-originated messages a local authority pushed through the NWS
     * dissemination path, arriving in the feed we already poll, and P0-2 now
     * ships plain-language copy for all nine.</p>
     *
     * <p><b>But coverage is inconsistent by jurisdiction and outside our
     * control.</b> Many counties never push through IPAWS; many that do use
     * channels the NWS relay does not carry. So an arriving message is
     * trustworthy and a quiet feed proves nothing — and partial coverage is
     * <i>easier</i> to mistake for full coverage than no coverage is. That
     * asymmetry is the whole reason for saying it out loud.</p>
     *
     * <h2>Why it is a constant, and why it is on every response</h2>
     *
     * <p>One string, one source of truth. Not reconstructed per request, and
     * not reserved for the empty state: a user reading three real alerts is
     * exactly as entitled to know what is missing as a user reading none. If
     * the frontend needed its own copy for the empty state, there would be two
     * strings and they would drift.</p>
     */
    public static final String COVERAGE_CAVEAT =
            "SitPrep may relay evacuation and shelter-in-place orders when issued through "
                    + "official channels reflected in the sources we monitor. Coverage varies by "
                    + "jurisdiction and should not be treated as complete — always follow "
                    + "instructions from local authorities.";
}
