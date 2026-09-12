package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * What a LINK HOLDER sees. An allowlist, not a redaction.
 *
 * <p>── WHY THIS TYPE EXISTS ─────────────────────────────────────────────────
 *
 * <p>{@code GET /api/plans/activations/{id}} is {@code permitAll}: a shared
 * emergency plan has to open for a recipient who has no SitPrep account, so the
 * activation id IS the credential. That id travels by SMS, gets forwarded,
 * screenshotted and re-opened on other devices, and it stays valid for 72
 * hours — during the exact hours a household is least careful about who a link
 * reaches.
 *
 * <p>Until this type existed, that route answered with {@link
 * PlanActivationDtos.ActivationDetailDto} — the household's own DTO — and
 * narrowed it by NULLING fields on the way out. Two problems with that shape,
 * and only the second one is about any particular field:
 *
 * <ol>
 *   <li>It leaked. The recipient snapshot reused the household place mappers,
 *       so a link holder received {@code evacPlan.origin} — <b>the household's
 *       home address</b> — plus the meeting place's phone number and private
 *       {@code additionalInfo}, the shelter's phone and {@code shelterInfo},
 *       the owner's internal {@code ownerUserId}, and every internal row id.
 *       Verified against production with recognizable markers before the fix.</li>
 *   <li><b>It was a blacklist.</b> Narrowing by omission means every field
 *       added to the household DTO is public by default until somebody
 *       remembers to null it. That is not a boundary; it is a habit, and the
 *       leak above is what it produced. RC-3's support profiles escaped only
 *       because they were never mapped onto an activation at all — luck, not
 *       design.</li>
 * </ol>
 *
 * <p>So this type is built by an explicit mapper from the internal model and
 * names every field it is willing to publish. A new field on the household DTO
 * reaches a link holder only when somebody adds it <i>here</i>, on purpose.
 *
 * <p>── THE PRODUCT RULE IT ENCODES ─────────────────────────────────────────
 *
 * <p>A recipient should learn <b>what the household wants them to do, where to
 * go, and how to read the active situation</b> — not receive the household's
 * internal emergency database. Everything below is one of those three. Contact
 * lists, go-bag contents, other recipients' check-ins and the household's own
 * location are none of them, and are structurally absent rather than nulled.
 *
 * <p>── WHY JSON NAMES MATCH THE AUTHENTICATED DTO ──────────────────────────
 *
 * <p>{@code meetingPlace}, {@code evacPlan} and {@code activeSituation} keep
 * the names the recipient view already reads. The security boundary is the
 * TYPE, not the vocabulary; renaming would have churned the client for no
 * privacy gain.
 */
public final class PublicActivationDtos {

    private PublicActivationDtos() {}

    /**
     * A place a recipient is being sent to.
     *
     * <p>Name, street address and coordinates — enough to recognize it, read it
     * aloud, and route to it. No {@code phoneNumber} (a meeting place's number
     * is the household's contact surface, not a direction), no {@code
     * additionalInfo} (free-text the household wrote for itself), and no row id.
     */
    public record PublicPlaceDto(
            String name,
            String address,
            Double lat,
            Double lng
    ) {}

    /**
     * Where to go if the household is leaving.
     *
     * <p>Deliberately NOT {@code EvacuationPlanSnapshotDto}. That record carries
     * {@code origin} — where the household lives — which is the single most
     * sensitive field that was reaching link holders, and which the recipient
     * has no use for: they are being told a destination, not a route from
     * somebody else's house. {@code shelterPhoneNumber} and {@code shelterInfo}
     * are likewise household planning notes.
     */
    public record PublicDestinationDto(
            /** The destination as the household named it. */
            String destination,
            String shelterName,
            String shelterAddress,
            Double lat,
            Double lng,
            /** "driving" / "walking" — how the household planned to travel. */
            String travelMode
    ) {}

    /**
     * The official alert this activation was fired against, for provenance.
     *
     * <p>Public data by origin (NWS/USGS/NIFC), and load-bearing: it is what
     * lets the recipient see that the county issued the order and SitPrep
     * merely carried it. {@code id} is the issuer's own public alert id.
     */
    public record PublicGoverningAlertDto(
            String source,
            String id,
            String event,
            String headline,
            String lifecycleState
    ) {}

    /**
     * The active situation, as a recipient needs to read it.
     *
     * <p>This is the safety-critical half of the payload and is narrowed the
     * least: the movement directive, the action it produces, and what that
     * action SUPPRESSED are the whole point of the link. Dropping
     * {@code suppressedAction}/{@code suppressedReason} would make the plan
     * operationally ambiguous — the recipient would see no destination offered
     * and not know that withholding it was deliberate.
     *
     * <p>Absent by design: {@code requestedOperationalMode} (what the household
     * asked for before policy resolved it — internal reasoning) and
     * {@code checkInSummary} (other recipients' welfare).
     */
    public record PublicSituationDto(
            String id,
            String status,
            Instant activatedAt,
            Instant updatedAt,
            Instant endedAt,
            String operationalMode,
            String movementDirective,
            PublicPlaceDto activeMeetingPlace,
            PublicDestinationDto activeEvacuationDestination,
            PublicGoverningAlertDto governingAlert,
            String primaryAction,
            String primaryActionKind,
            String suppressedAction,
            String suppressedReason,
            /**
             * P0-A. A link holder has no live alert layer of their own, so the
             * server has to tell them how much weight the directive carries:
             * CURRENT, UNVERIFIED (could not check — may be stale) or
             * SUPERSEDED_UNRESOLVED (nothing in force; narrowed to
             * follow_official_instruction). Without this the recipient cannot
             * distinguish "shelter in place, confirmed a moment ago" from
             * "shelter in place, decided an hour ago and never rechecked" —
             * which is exactly the confusion Scenario 24 is about.
             *
             * `directiveChanged` says the guidance is no longer what the sender
             * activated under, so the surface can say the instruction changed.
             * No household PII is added: these are safety facts about the alert.
             */
            String directiveStatus,
            boolean directiveChanged,
            Instant directiveResolvedAt
    ) {}

    /**
     * The whole of what a link holder receives.
     *
     * <p>{@code viewerCanEnd} and {@code viewerIsOwner} are always {@code false}
     * here and are present only because the client branches on them. They are
     * server-computed capabilities, which is the point: the client used to
     * derive "am I the owner?" by comparing {@code ownerUserId} against its own
     * profile id, and that comparison is the only reason an internal user id was
     * ever published. The boolean replaces the identifier.
     *
     * <p>{@code ownerName} stays: a recipient must be able to see who sent
     * this. It is the sharer's own display name, chosen by them, and the
     * alternative is an anonymous instruction to evacuate.
     */
    public record PublicActivationDto(
            String activationId,
            String ownerName,
            Instant activatedAt,
            Instant expiresAt,
            boolean closed,
            Instant endedAt,
            boolean viewerCanEnd,
            boolean viewerIsOwner,
            String meetingMode,
            String evacMode,
            /** The household's own words to the people it sent this to. */
            String messagePreview,
            PublicPlaceDto meetingPlace,
            PublicDestinationDto evacPlan,
            PublicSituationDto activeSituation
    ) {}
}
