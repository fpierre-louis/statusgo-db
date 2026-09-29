package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.repo.AskBookmarkRepo;
import io.sitprep.sitprepapi.repo.FollowRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostConfirmRepo;
import io.sitprep.sitprepapi.repo.PostFollowRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Composer V2 C0 (2026-09-28): the two production P0s.
 *
 * <p>C0a — a Free listing must bind from the composer's {@code "isFree"} key.
 * C0b — a post may only carry, and only frees, images its AUTHOR uploaded:
 * the attack was attaching a stranger's image key (keys are public on the
 * wire) to your own post, then deleting the post to destroy the original.</p>
 */
class PostImageOwnershipTest {

    private static final String AUTHOR = "author@x.com";
    private static final String VICTIM = "victim@x.com";
    private static final Long POST_ID = 7L;

    private PostRepo taskRepo;
    private StorageService storage;
    private PostService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(PostRepo.class);
        storage = mock(StorageService.class);
        service = new PostService(
                taskRepo,
                mock(UserInfoRepo.class),
                mock(NominatimGeocodeService.class),
                mock(WebSocketMessageSender.class),
                mock(AlertModeService.class),
                mock(FollowRepo.class),
                mock(BlockService.class),
                mock(PostReactionService.class),
                mock(PostCommentService.class),
                storage,
                mock(GroupRepo.class),
                mock(PublisherPublishAuditService.class),
                mock(AgencyAuthorizationService.class),
                mock(PostConfirmRepo.class),
                mock(PostFollowRepo.class),
                mock(AskBookmarkRepo.class),
                mock(WorkOrderQuotaService.class),
                mock(AdminAuditLogService.class),
                mock(TaskAssigneeRepo.class),
                mock(TaskAssignmentService.class),
                mock(AgencyJurisdictionService.class),
                mock(CivicAgencyService.class),
                new PostReadAuthorizer(mock(GroupRepo.class), mock(TaskAssigneeRepo.class)),
                org.mockito.Mockito.mock(PostMentionService.class),
                org.mockito.Mockito.mock(io.sitprep.sitprepapi.repo.AlertPostRepo.class),
                org.mockito.Mockito.mock(io.sitprep.sitprepapi.repo.HazardReportRepo.class),
                org.mockito.Mockito.mock(io.sitprep.sitprepapi.repo.HazardVoteRepo.class));
        when(storage.ownerOf("task/mine.jpg"))
                .thenReturn(new StorageService.ObjectOwner(true, StorageService.uploaderTag(AUTHOR)));
        when(storage.ownerOf("profile/victim.jpg"))
                .thenReturn(new StorageService.ObjectOwner(true, StorageService.uploaderTag(VICTIM)));
        when(storage.ownerOf("task/legacy.jpg"))
                .thenReturn(new StorageService.ObjectOwner(true, null));
        when(storage.ownerOf("task/nope.jpg"))
                .thenReturn(new StorageService.ObjectOwner(false, null));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ── C0a ────────────────────────────────────────────────────────────────

    @Test
    void isFreeBindsFromTheComposersKeyAndSerialisesBackAsIsFree() throws Exception {
        ObjectMapper om = new ObjectMapper();
        Post p = om.readValue("{\"isFree\": true}", Post.class);
        assertTrue(p.isFree(), "the composer sends isFree — it must bind");
        assertTrue(om.writeValueAsString(p).contains("\"isFree\":true"));
    }

    // ── C0b: attach ────────────────────────────────────────────────────────

    @Test
    void attachingSomeoneElsesImageIsForbidden() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.requireOwnedImageKeys(List.of("profile/victim.jpg"), AUTHOR, List.of()));
        assertEquals(403, e.getStatusCode().value());
    }

    @Test
    void attachingAnUnstampedImageIsForbidden() {
        assertThrows(ResponseStatusException.class,
                () -> service.requireOwnedImageKeys(List.of("task/legacy.jpg"), AUTHOR, List.of()));
    }

    @Test
    void attachingAnUnknownKeyIsABadRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> service.requireOwnedImageKeys(List.of("task/nope.jpg"), AUTHOR, List.of()));
    }

    @Test
    void attachingYourOwnUploadIsAllowed() {
        assertDoesNotThrow(() -> service.requireOwnedImageKeys(List.of("task/mine.jpg"), AUTHOR, List.of()));
    }

    @Test
    void keysAlreadyOnThePostAreExemptSoALegacyPostStaysEditable() {
        assertDoesNotThrow(() -> service.requireOwnedImageKeys(
                List.of("task/legacy.jpg", "task/mine.jpg"), AUTHOR, List.of("task/legacy.jpg")));
    }

    // ── C0b: free ──────────────────────────────────────────────────────────

    @Test
    void onlyTheAuthorsOwnUnreferencedUploadsAreFreeable() {
        when(taskRepo.countOtherPostsWithImageKey(eq(POST_ID), anyString())).thenReturn(0L);
        List<String> out = service.freeableImageKeys(POST_ID,
                List.of("task/mine.jpg", "profile/victim.jpg", "task/legacy.jpg", "task/nope.jpg"), AUTHOR);
        assertEquals(List.of("task/mine.jpg"), out);
    }

    @Test
    void anUploadAnotherPostStillReferencesIsKept() {
        when(taskRepo.countOtherPostsWithImageKey(POST_ID, "task/mine.jpg")).thenReturn(1L);
        assertTrue(service.freeableImageKeys(POST_ID, List.of("task/mine.jpg"), AUTHOR).isEmpty());
    }

    @Test
    void theAttackDeletingAPostCarryingAVictimsKeyLeavesTheVictimsObject() {
        Post p = new Post();
        p.setId(POST_ID);
        p.setRequesterEmail(AUTHOR);
        p.setKind("post");
        p.setImageKeys(new ArrayList<>(List.of("task/mine.jpg", "profile/victim.jpg")));
        when(taskRepo.findById(POST_ID)).thenReturn(Optional.of(p));
        when(taskRepo.countOtherPostsWithImageKey(eq(POST_ID), anyString())).thenReturn(0L);

        service.delete(POST_ID);
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }

        verify(storage).delete("task/mine.jpg");
        verify(storage, never()).delete("profile/victim.jpg");
    }
}
