package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The canonical record of one official alert — {@code GET /api/alerts/record}
 * (S2 contract §4).
 *
 * <p>One answer for every link to an alert, whichever spelling it carries
 * ({@code SOURCE:id}, the legacy {@code SOURCE-id}, or a bare id plus a source
 * hint) and whatever has happened to the alert since the link was made:</p>
 *
 * <ul>
 *   <li>{@code live} — the issuer still lists it (it is in the live ingest
 *       snapshot), and {@code card} is the live card re-evaluated now. A live
 *       message already replaced in that snapshot is still {@code live} here,
 *       and its card says so: {@code presentation.current} is false and it
 *       carries no primary action.</li>
 *   <li>{@code record} — no longer listed; {@code card} is a RECORD built from
 *       the stored source revision or history row. Never guidance, never a
 *       primary action, never a current directive.</li>
 *   <li>{@code ambiguous} — a bare id with no source hint, or a prefix that
 *       contradicts the hint. Nothing is guessed, so there is no card.</li>
 *   <li>{@code not_found} — nothing held under that key (or a message that is
 *       never rendered at all: a retraction, a drill, a private-scope
 *       message).</li>
 * </ul>
 *
 * @param geometry the alert's full GeoJSON geometry, or null — the card carries
 *                 only a bbox
 * @param thread   the alert's lifecycle thread: every message NWS linked to it
 *                 by VTEC event key or CAP references, oldest first
 * @param revisions this dedupeKey's known source revisions ({@code dedupeKey@fp12}),
 *                 newest first
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlertRecordResponse(
        String status,
        AlertCardDto card,
        Object geometry,
        RecordThread thread,
        List<String> revisions,
        Meta meta
) {

    public static final String STATUS_LIVE = "live";
    public static final String STATUS_RECORD = "record";
    public static final String STATUS_AMBIGUOUS = "ambiguous";
    public static final String STATUS_NOT_FOUND = "not_found";

    /**
     * @param threadKey the alert's own {@code lifecycleInfo.threadKey} —
     *                  {@code VTEC:<event key>}, else {@code CAP:<root>}, else
     *                  the dedupeKey. The key a client hands back to mark the
     *                  thread read.
     * @param members   oldest first; the alert itself is one of them
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordThread(String threadKey, List<Member> members) {}

    /**
     * One message of the thread.
     *
     * @param revisionRef the newest revision held for it
     * @param state       its lifecycle state, decided the way the card's is
     * @param sent        when the issuer sent it (USGS: its own revision time)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Member(String dedupeKey, String revisionRef, String state, String sent) {}

    /**
     * @param lastSuccessAt  the snapshot's last successful poll, as the feed reports it
     * @param coverageCaveat the same constant every alert response ships
     * @param serverTime     when this answer was assembled — the {@code cutoff} a
     *                       client sends back to mark the thread read (§5), so a
     *                       message that arrives after the reader looked stays
     *                       unread. Server time, never the device clock.
     * @param context        {@code current} or {@code home}, echoed as the feed does
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(String lastSuccessAt, String coverageCaveat, String serverTime, String context) {}
}
