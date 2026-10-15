package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.notifications.NotificationPresentation;
import io.sitprep.sitprepapi.notifications.NotificationPresentationBuilder;

import java.time.Instant;
import java.util.List;

public record NotificationInboxRowDto(
        Long id,
        String recipientEmail,
        String type,
        String notificationType,
        String token,
        String title,
        String body,
        String referenceId,
        String targetUrl,
        String additionalData,
        Instant timestamp,
        boolean success,
        String errorMessage,
        Instant readAt,
        String lane,
        String category,
        Instant archivedAt,
        String actorUserId,
        /** Always present on the wire: stored, or built for a legacy row. */
        NotificationPresentation presentation,
        /**
         * The official alert this row is about (S2 contract §5), with its
         * lifecycle RE-EVALUATED at read time. Null for every non-alert row,
         * for agency alerts (no canonical record), and for a hazard row whose
         * reference names no resolvable alert.
         */
        AlertRow alert
) {
    public static NotificationInboxRowDto from(NotificationLog row) {
        return from(row, row == null ? null : NotificationPresentationBuilder.fromMap(row.getPresentationJson()));
    }

    public static NotificationInboxRowDto from(NotificationLog row, NotificationPresentation presentation) {
        return from(row, presentation, null);
    }

    public static NotificationInboxRowDto from(NotificationLog row, NotificationPresentation presentation,
                                               AlertRow alert) {
        if (row == null) return null;
        return new NotificationInboxRowDto(
                row.getId(),
                row.getRecipientEmail(),
                row.getType(),
                row.getType(),
                row.getToken(),
                row.getTitle(),
                row.getBody(),
                row.getReferenceId(),
                row.getTargetUrl(),
                row.getAdditionalData(),
                row.getTimestamp(),
                row.isSuccess(),
                row.getErrorMessage(),
                row.getReadAt(),
                row.getLane(),
                row.getCategory(),
                row.getArchivedAt(),
                row.getActorUserId(),
                presentation,
                alert
        );
    }

    /**
     * {@code row.alert}. Identity and the delivered interpretation come from the
     * row; {@code lifecycle} is the alert's state NOW, resolved for the whole
     * page in one batched lookup.
     *
     * @param dedupeKey     {@code SOURCE:id}
     * @param revisionRef   the revision this row delivered ({@code dedupeKey@fp12});
     *                      null on rows written before V110
     * @param threadKey     the lifecycle thread; rows sharing it group together,
     *                      across page boundaries ({@code GET /api/notifications/threads/{threadKey}})
     * @param snapshotId    the interpretation delivered with the row, or null
     * @param eventLabel    the issuer's product name ({@code "Flood Warning"}), else its headline
     * @param tier          warning | watch | advisory | statement | information
     * @param issuer        the verified source's name — never taken from the payload
     * @param lifecycle     re-evaluated now; null when the alert is no longer on record
     * @param primaryAction the delivered snapshot's primary action, ONLY while the
     *                      alert is listed, current, and still on the revision
     *                      this row delivered; null otherwise
     * @param areaLabel     the issuer's area, shortened ({@code "Salt Lake County + 2 more"})
     * @param expiresAt     the end the delivered revision gave, verbatim
     * @param actions       navigation only — {@code OPEN_ALERT_DETAIL}, always
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AlertRow(
            String dedupeKey,
            String revisionRef,
            String threadKey,
            String snapshotId,
            String eventLabel,
            String tier,
            String issuer,
            Lifecycle lifecycle,
            AlertCardDto.PrimaryAction primaryAction,
            String areaLabel,
            String expiresAt,
            List<AlertCardDto.Action> actions
    ) {}

    /**
     * {@code state}: active | updated | superseded | cancelled | expired |
     * no_longer_listed. {@code current}: state is active or updated.
     * {@code reason} as {@code lifecycleInfo.reason} (vtec_can, cap_cancel,
     * all_clear, replaced, expired, not_listed, …), so a client can tell an
     * issuer cancel from a replacement.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lifecycle(String state, boolean current, String reason) {}
}
