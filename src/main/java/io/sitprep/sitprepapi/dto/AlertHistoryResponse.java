package io.sitprep.sitprepapi.dto;

import java.util.List;

/**
 * What was active near a coordinate over a past window.
 *
 * <p>Deliberately <b>not</b> "what we pushed to you". Those are different
 * features: pushes are per-user and cover only the products we have template
 * copy for plus a resolvable geocell, which on the measured feed is 81 of 310
 * alerts. A history assembled from pushes would silently omit every Heat
 * Advisory — and Oklahoma City's entire month of history is Heat Advisories.
 * This is the location's record, not the reader's.</p>
 *
 * @see AlertCardDto
 */
public record AlertHistoryResponse(List<AlertCardDto> alerts, Meta meta) {

    /**
     * @param recordingSince ISO-8601 instant of the oldest alert still held,
     *   across all locations — i.e. when this server began watching. Null
     *   before the first tick ever records anything.
     *
     *   <p><b>The empty state is the reason this field exists.</b> "Nothing
     *   happened here in the last 30 days" and "we have only been recording
     *   since Tuesday" are different claims, and on Wednesday only one of them
     *   is true. A quiet history is the NORMAL case, not an edge case — Lehi UT
     *   had one alert in thirty days, Phoenix two, Miami four — so the surface
     *   that renders nothing has to be able to say <i>why</i> it is rendering
     *   nothing.</p>
     * @param days the window actually applied, after clamping to retention. The
     *   caller may ask for more than we keep; this says what it got.
     * @param coverageCaveat the same constant the live feed ships —
     *   {@link AlertFeedResponse#COVERAGE_CAVEAT}, not a second copy of it.
     *   A gap in coverage is a gap in the history too, and the sentence saying
     *   so must be one string in one place or the two will drift.
     */
    public record Meta(String recordingSince, int days, String coverageCaveat) {}
}
