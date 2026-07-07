package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Wire shapes for the direct-message vertical ({@code /api/dm/*} +
 * STOMP {@code /topic/dm/{email}}).
 *
 * <p>The peer identity intentionally mirrors the MemberAvatar shape
 * (userId + name + avatar) plus the email — DM participants already
 * see each other's public profile, and the FE keys threads by peer
 * email, so shipping it here isn't a roster leak the way it would be
 * on discover surfaces.</p>
 */
public final class DmDtos {

    private DmDtos() {}

    public record PeerDto(
            String userId,
            String email,
            String name,
            String avatarUrl
    ) {}

    public record DmMessageDto(
            Long id,
            Long threadId,
            String senderEmail,
            String body,
            Instant createdAt
    ) {}

    public record DmThreadDto(
            Long threadId,
            PeerDto peer,
            DmMessageDto lastMessage,
            long unreadCount,
            Instant lastReadAt
    ) {}

    /** POST /api/dm/messages request body. */
    public record SendMessageRequest(String peerEmail, String body) {}
}
