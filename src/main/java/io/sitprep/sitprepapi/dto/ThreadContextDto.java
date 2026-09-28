package io.sitprep.sitprepapi.dto;

import java.util.List;

/**
 * What the thread page needs about the VIEWER'S relationship to a post that
 * the feed row does not carry (Community Thread C, B2).
 *
 * <p>Its own read ({@code GET /api/posts/{id}/thread-context}) rather than
 * fields on {@link PostDto}: PostDto rides every feed row, and faces would
 * cost a query per feed page for a thread-only need.</p>
 *
 * @param viewerFollowing  the viewer follows this thread (gets reply pushes)
 * @param recentConfirmers up to three people who tapped "I see it too" — the
 *                         viewer first when they did, then the most recent
 *                         others; block relationships skipped. {@code userId},
 *                         never an email (see {@link MemberAvatar}).
 */
public record ThreadContextDto(boolean viewerFollowing, List<MemberAvatar> recentConfirmers) {

    /** Result of a follow toggle. */
    public record FollowResult(boolean following) {}
}
