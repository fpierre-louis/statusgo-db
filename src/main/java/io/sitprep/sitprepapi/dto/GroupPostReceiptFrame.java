package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Live per-group chat receipt frame.
 *
 * <p>Topic: {@code /topic/group-posts/{groupId}}</p>
 */
public record GroupPostReceiptFrame(
        String type,
        String groupId,
        String readerEmail,
        String state,
        Instant deliveredAt,
        Instant readAt
) {
    public static GroupPostReceiptFrame delivered(String groupId, String readerEmail, Instant at) {
        return new GroupPostReceiptFrame("receipt", groupId, readerEmail, "delivered", at, null);
    }

    public static GroupPostReceiptFrame read(String groupId, String readerEmail, Instant at) {
        return new GroupPostReceiptFrame("receipt", groupId, readerEmail, "read", null, at);
    }

    public static GroupPostReceiptFrame closed(String groupId, String readerEmail, Instant at) {
        return new GroupPostReceiptFrame("receipt", groupId, readerEmail, "closed", at, null);
    }
}
