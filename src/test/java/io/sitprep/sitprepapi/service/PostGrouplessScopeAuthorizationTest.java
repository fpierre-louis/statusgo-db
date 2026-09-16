package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.PostKind;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The GROUPLESS arm of {@link PostReadAuthorizer#canRead} — the one that had a
 * real production incident and the one nothing tested.
 *
 * <p>{@code PostGroupScopeAuthorizationTest} covers the group-scoped arms
 * thoroughly (membership, author, claimer group, assignee, and the rejections
 * including no admin/moderator bypass). It stops at the group boundary. But the
 * branch that leaked was the other one:</p>
 *
 * <pre>
 *   if (groupId == null || groupId.isBlank()) {
 *       if (!PostKind.isPersonalScope(post.getKind())) return true;  // broadcast
 *       return isRequester(post, viewerEmail);                       // private
 *   }
 * </pre>
 *
 * <p>{@code PostKind}'s own doc records what happened: <i>"Until 2026-08-11 this
 * paragraph asserted the same rule as settled fact while no such filter existed
 * anywhere, and personal tasks were reaching strangers' community feeds; if you
 * are adding a read path, call the authorizer rather than trusting this
 * sentence."</i> The rule was prose, the prose was wrong, and strangers read
 * other people's personal tasks.</p>
 *
 * <p>It is correct now. This exists so that stays true — {@code isPersonalScope}
 * had zero test coverage anywhere in the suite before this file.</p>
 */
class PostGrouplessScopeAuthorizationTest {

    private static final String AUTHOR = "author@x.com";
    private static final String STRANGER = "stranger@x.com";

    private PostReadAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        authorizer = new PostReadAuthorizer(mock(GroupRepo.class), mock(TaskAssigneeRepo.class));
    }

    private Post groupless(String kind) {
        Post p = new Post();
        p.setId(7L);
        p.setGroupId(null);          // the branch under test
        p.setKind(kind);
        p.setRequesterEmail(AUTHOR);
        return p;
    }

    // ---------------------------------------------------------------
    // Personal scope — private to the author. This is the leak that was.
    // ---------------------------------------------------------------

    @Test
    void personalTask_isReadableByItsAuthor() {
        assertTrue(authorizer.canRead(groupless("task"), AUTHOR));
    }

    @Test
    void personalTask_isNotReadableByAStranger() {
        assertFalse(authorizer.canRead(groupless("task"), STRANGER),
                "a groupless personal task must never reach another user's feed");
    }

    @Test
    void personalTask_isNotReadableAnonymously() {
        assertFalse(authorizer.canRead(groupless("task"), null));
        assertFalse(authorizer.canRead(groupless("task"), "  "));
    }

    @Test
    void personalProject_isAlsoPrivate() {
        // PERSONAL_SCOPE is Set.of(TASK, PROJECT) — both arms, not just the
        // one that happened to leak.
        assertTrue(authorizer.canRead(groupless("project"), AUTHOR));
        assertFalse(authorizer.canRead(groupless("project"), STRANGER));
    }

    @Test
    void theKindSetItselfIsPinned() {
        // If a new personal-ish kind is added and NOT added to PERSONAL_SCOPE,
        // it becomes a public broadcast silently. Pin the two that must be in.
        assertTrue(PostKind.isPersonalScope("task"));
        assertTrue(PostKind.isPersonalScope("project"));
    }

    // ---------------------------------------------------------------
    // Everything else groupless is a deliberate neighborhood broadcast.
    // ---------------------------------------------------------------

    @Test
    void communityPost_isReadableByAnyone() {
        assertTrue(authorizer.canRead(groupless("post"), STRANGER));
        assertTrue(authorizer.canRead(groupless("post"), null));
    }

    @Test
    void anUnknownKindIsTreatedAsBroadcast_soNewKindsMustOptIn() {
        // Documented behaviour: fromWire returns null for an unrecognized kind
        // and isPersonalScope is false. Asserted rather than assumed, because
        // it means a typo'd kind is PUBLIC — worth knowing when adding one.
        assertFalse(PostKind.isPersonalScope("not-a-real-kind"));
        assertTrue(authorizer.canRead(groupless("not-a-real-kind"), STRANGER));
    }

    @Test
    void aNullKindIsAlsoBroadcast() {
        assertFalse(PostKind.isPersonalScope(null));
        assertTrue(authorizer.canRead(groupless(null), STRANGER));
    }
}
