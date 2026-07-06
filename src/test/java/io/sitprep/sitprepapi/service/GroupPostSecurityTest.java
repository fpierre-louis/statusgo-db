package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.dto.GroupPostDto;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupReadStateRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Security gates on GroupPost creation (2026-07-06 location-audit
 * hardening): (1) writes require actual group membership — owner, admin,
 * or member; strangers and pending members get 403; (2) attached
 * share-location coordinates are bounds-checked at the write boundary.
 * Pure Mockito — no Spring context, no DB.
 */
class GroupPostSecurityTest {

    private static final String GROUP_ID = "grp-1";
    private static final String MEMBER = "member@x.com";
    private static final String STRANGER = "stranger@x.com";

    private GroupPostRepo postRepo;
    private UserInfoRepo userInfoRepo;
    private GroupRepo groupRepo;
    private GroupPostService service;

    @BeforeEach
    void setUp() {
        postRepo = mock(GroupPostRepo.class);
        userInfoRepo = mock(UserInfoRepo.class);
        groupRepo = mock(GroupRepo.class);
        service = new GroupPostService(postRepo, userInfoRepo, groupRepo,
                mock(NotificationService.class),
                mock(WebSocketMessageSender.class),
                mock(GroupPostReactionService.class),
                mock(GroupReadStateRepo.class),
                mock(GroupPostThreadPresenceService.class),
                mock(PublisherPublishAuditService.class));
        // createPost registers an afterCommit synchronization on success.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Group groupWithMember(String memberEmail) {
        Group g = new Group();
        g.setGroupId(GROUP_ID);
        g.setOwnerEmail("owner@x.com");
        g.setAdminEmails(List.of("admin@x.com"));
        g.setMemberEmails(List.of(memberEmail));
        g.setPendingMemberEmails(List.of("pending@x.com"));
        return g;
    }

    private GroupPostDto dto(String author) {
        GroupPostDto dto = new GroupPostDto();
        dto.setAuthor(author);
        dto.setGroupId(GROUP_ID);
        dto.setContent("hello");
        return dto;
    }

    @Test
    void nonMemberRejectedWith403() {
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(groupWithMember(MEMBER)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createPost(dto(STRANGER), STRANGER));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(postRepo, never()).save(any());
    }

    @Test
    void pendingMemberRejectedWith403() {
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(groupWithMember(MEMBER)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createPost(dto("pending@x.com"), "pending@x.com"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(postRepo, never()).save(any());
    }

    @Test
    void unknownGroupRejectedWith400() {
        when(groupRepo.findByGroupId(anyString())).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.createPost(dto(MEMBER), MEMBER));
        verify(postRepo, never()).save(any());
    }

    @Test
    void outOfBoundsShareLocationRejectedEvenForMember() {
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(groupWithMember(MEMBER)));

        GroupPostDto bad = dto(MEMBER);
        bad.setLatitude(91.0);
        bad.setLongitude(-84.4);

        assertThrows(IllegalArgumentException.class,
                () -> service.createPost(bad, MEMBER));
        verify(postRepo, never()).save(any());
    }

    @Test
    void memberPostSaves() {
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(groupWithMember(MEMBER)));
        when(userInfoRepo.findByUserEmail(anyString())).thenReturn(Optional.empty());
        when(postRepo.save(any(GroupPost.class))).thenAnswer(inv -> {
            GroupPost p = inv.getArgument(0);
            p.setId(42L);
            return p;
        });

        GroupPostDto saved = service.createPost(dto(MEMBER), MEMBER);

        assertNotNull(saved);
        assertEquals(MEMBER, saved.getAuthor());
        verify(postRepo).save(any(GroupPost.class));
    }

    @Test
    void ownerAndAdminAlsoPassMembershipGate() {
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(groupWithMember(MEMBER)));
        when(userInfoRepo.findByUserEmail(anyString())).thenReturn(Optional.empty());
        when(postRepo.save(any(GroupPost.class))).thenAnswer(inv -> {
            GroupPost p = inv.getArgument(0);
            p.setId(43L);
            return p;
        });

        assertNotNull(service.createPost(dto("owner@x.com"), "owner@x.com"));
        assertNotNull(service.createPost(dto("admin@x.com"), "admin@x.com"));
    }
}
