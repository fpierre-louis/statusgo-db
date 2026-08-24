package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.GroupPostComment;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.PostComment;
import io.sitprep.sitprepapi.repo.GroupPostCommentRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.PostCommentRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * "May this viewer touch this thread?" — resolved from a post or comment id.
 *
 * <p>Written 2026-08-24 for the reaction and comment endpoints, which took a
 * numeric id straight off the path and did the work. Every one of them verified
 * the caller was signed in, and none verified they could see the thread. Post
 * and comment ids are sequential, so reading a private family or agency chat's
 * reaction roster — which returns {@code EmojiReactionDto(userEmail, addedAt)},
 * a list of member email addresses — was a for-loop. Commenting into one was the
 * same loop with a body attached, and the comment arrives as a push
 * notification.</p>
 *
 * <p>This does not invent an authorization rule. Both rules already existed one
 * class over — {@link PostReadAuthorizer#isMemberOfGroup} for group chat,
 * {@link PostReadAuthorizer#canRead} for community content, which correctly
 * treats a personal task as requester-or-assignee-only and everything else as
 * public. All this class does is resolve an id to the thing those rules take,
 * so that four resources ask the same question the same way instead of four
 * slightly different ways.</p>
 *
 * <p><b>404, never 403.</b> A caller who cannot see a thread should not learn
 * whether its id exists — otherwise the gate itself becomes the enumeration
 * oracle it was added to close.</p>
 */
@Service
public class ThreadAccessService {

    private final GroupPostRepo groupPostRepo;
    private final GroupPostCommentRepo groupPostCommentRepo;
    private final PostRepo postRepo;
    private final PostCommentRepo postCommentRepo;
    private final PostReadAuthorizer postReadAuthorizer;

    public ThreadAccessService(GroupPostRepo groupPostRepo,
                               GroupPostCommentRepo groupPostCommentRepo,
                               PostRepo postRepo,
                               PostCommentRepo postCommentRepo,
                               PostReadAuthorizer postReadAuthorizer) {
        this.groupPostRepo = groupPostRepo;
        this.groupPostCommentRepo = groupPostCommentRepo;
        this.postRepo = postRepo;
        this.postCommentRepo = postCommentRepo;
        this.postReadAuthorizer = postReadAuthorizer;
    }

    /** Group chat post: caller must be a member of the post's group. */
    @Transactional(readOnly = true)
    public void requireCanAccessGroupPost(Long groupPostId, String viewerEmail) {
        GroupPost post = groupPostId == null ? null : groupPostRepo.findById(groupPostId).orElse(null);
        if (post == null || !postReadAuthorizer.isMemberOfGroup(post.getGroupId(), viewerEmail)) {
            throw notFound();
        }
    }

    /** Group chat comment: resolved to its post, then the same membership rule. */
    @Transactional(readOnly = true)
    public void requireCanAccessGroupPostComment(Long groupPostCommentId, String viewerEmail) {
        GroupPostComment comment = groupPostCommentId == null
                ? null : groupPostCommentRepo.findById(groupPostCommentId).orElse(null);
        if (comment == null) throw notFound();
        requireCanAccessGroupPost(comment.getPostId(), viewerEmail);
    }

    /** Community post: PostReadAuthorizer's rule, unchanged. */
    @Transactional(readOnly = true)
    public void requireCanAccessPost(Long postId, String viewerEmail) {
        Post post = postId == null ? null : postRepo.findById(postId).orElse(null);
        if (post == null || !postReadAuthorizer.canRead(post, viewerEmail)) {
            throw notFound();
        }
    }

    /** Community comment: resolved to its post, then the same rule. */
    @Transactional(readOnly = true)
    public void requireCanAccessPostComment(Long postCommentId, String viewerEmail) {
        PostComment comment = postCommentId == null
                ? null : postCommentRepo.findById(postCommentId).orElse(null);
        if (comment == null) throw notFound();
        requireCanAccessPost(comment.getPostId(), viewerEmail);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found");
    }
}
