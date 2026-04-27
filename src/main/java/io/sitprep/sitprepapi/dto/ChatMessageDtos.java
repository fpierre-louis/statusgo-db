package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Request/response shapes for {@code /api/chat/**}. Kept small: the chat path
 * is the hot one, so extra fields here translate directly to bandwidth.
 */
public final class ChatMessageDtos {

    private ChatMessageDtos() {}

    public record CreateMessageRequest(
            String authorEmail,
            String content,
            String tempId
    ) {}

    public record UpdateMessageRequest(
            String authorEmail,
            String content
    ) {}

    public record ChatMessageDto(
            Long id,
            String groupId,
            String authorEmail,
            String authorFirstName,
            String authorLastName,
            String authorProfileImageUrl,
            String content,
            Instant createdAt,
            Instant editedAt,
            Instant updatedAt,
            String tempId
    ) {}

    /**
     * Response for a paginated history fetch. {@code nextBefore} is the cursor
     * to pass as {@code ?before=} on the next older page, or {@code null} when
     * there's nothing older.
     */
    public record MessagesPage(
            List<ChatMessageDto> messages,
            Instant nextBefore
    ) {}
}
