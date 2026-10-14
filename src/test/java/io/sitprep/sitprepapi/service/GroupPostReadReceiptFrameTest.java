package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.GroupReadState;
import io.sitprep.sitprepapi.dto.GroupPostDto;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupReadStateRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * F7 review 2: "Sent · read by N" survives an edit or a pin. The single-post
 * DTO that edit and pin broadcast (and return) used to skip the read
 * receipts, so every such frame said {@code readCount: 0} and the FE's merge
 * overwrote the real count. Pure Mockito, same wiring as GroupPostModerationTest.
 */
class GroupPostReadReceiptFrameTest {

    private static final String GROUP_ID = "grp-1";
    private static final String ADMIN = "admin@x.com";
    private static final String AUTHOR = "author@x.com";
    private static final Instant SENT = Instant.parse("2026-10-14T10:00:00Z");

    private GroupPostRepo postRepo;
    private WebSocketMessageSender ws;
    private GroupPostService service;
    private GroupPost post;

    @BeforeEach
    void setUp() {
        postRepo = mock(GroupPostRepo.class);
        GroupRepo groupRepo = mock(GroupRepo.class);
        GroupReadStateRepo readStates = mock(GroupReadStateRepo.class);
        ws = mock(WebSocketMessageSender.class);
        service = new GroupPostService(postRepo, mock(UserInfoRepo.class), groupRepo,
                mock(NotificationService.class), ws,
                mock(GroupPostReactionService.class),
                readStates,
                mock(GroupPostThreadPresenceService.class),
                mock(PublisherPublishAuditService.class),
                mock(MentionService.class),
                mock(GroupContentRemovals.class));

        Group g = new Group();
        g.setGroupId(GROUP_ID);
        g.setOwnerEmail(ADMIN);
        g.setAdminEmails(List.of(ADMIN));
        g.setMemberEmails(new ArrayList<>(List.of(AUTHOR, "a@x.com", "b@x.com", "late@x.com")));
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(g));

        post = new GroupPost();
        post.setId(7L);
        post.setGroupId(GROUP_ID);
        post.setAuthor(AUTHOR);
        post.setContent("Back by 6.");
        post.setTimestamp(SENT);
        when(postRepo.findById(7L)).thenReturn(Optional.of(post));
        when(postRepo.save(any(GroupPost.class))).thenAnswer(inv -> inv.getArgument(0));

        // Two members read after it was sent, one read before, and the
        // author's own read never counts.
        when(readStates.findByGroupId(GROUP_ID)).thenReturn(List.of(
                state("a@x.com", SENT.plusSeconds(60)),
                state("b@x.com", SENT.plusSeconds(120)),
                state("late@x.com", SENT.minusSeconds(60)),
                state(AUTHOR, SENT.plusSeconds(300))));

        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static GroupReadState state(String email, Instant at) {
        GroupReadState s = new GroupReadState();
        s.setGroupId(GROUP_ID);
        s.setUserEmail(email);
        s.setLastReadAt(at);
        return s;
    }

    @Test
    void anEditFrameAndItsResponseCarryTheReadCount() {
        GroupPostDto returned = service.editPost(7L, AUTHOR, "Back by 7.", null, List.of(), null, null);
        assertEquals(2, returned.getReadCount());

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        ArgumentCaptor<GroupPostDto> frame = ArgumentCaptor.forClass(GroupPostDto.class);
        verify(ws).sendNewGroupPost(eq(GROUP_ID), frame.capture());
        assertEquals(2, frame.getValue().getReadCount());
        assertEquals(SENT.plusSeconds(120), frame.getValue().getReadAt());
    }

    @Test
    void aPinFrameCarriesTheReadCount() {
        GroupPostDto returned = service.pinPost(7L, ADMIN);
        assertEquals(2, returned.getReadCount());

        ArgumentCaptor<GroupPostDto> frame = ArgumentCaptor.forClass(GroupPostDto.class);
        verify(ws).sendNewGroupPost(eq(GROUP_ID), frame.capture());
        assertEquals(2, frame.getValue().getReadCount());
    }
}
