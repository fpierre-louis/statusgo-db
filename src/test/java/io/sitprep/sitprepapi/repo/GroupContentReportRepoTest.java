package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.CommunityReport;
import io.sitprep.sitprepapi.domain.GroupPost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The group report queue's JPQL (audit FD-01) against a real ORM (H2): a
 * group's queue holds the reports on its own chat posts and their comments,
 * never another group's, never community-feed reports that happen to share a
 * post id.
 */
@DataJpaTest
@ActiveProfiles("test")
class GroupContentReportRepoTest {

    @Autowired private CommunityReportRepo repo;
    @Autowired private TestEntityManager em;

    private GroupPost post(String groupId) {
        GroupPost p = new GroupPost();
        p.setGroupId(groupId);
        p.setAuthor("a@x.com");
        p.setContent("hi");
        return em.persistAndFlush(p);
    }

    private CommunityReport report(CommunityReport.TargetType type, Long targetId, Long postId,
                                   CommunityReport.ReviewStatus status) {
        CommunityReport r = new CommunityReport();
        r.setTargetType(type);
        r.setTargetId(targetId);
        r.setPostId(postId);
        r.setReporterEmail("r@x.com");
        r.setReason(CommunityReport.Reason.HARASSMENT);
        r.setStatus(status);
        return em.persistAndFlush(r);
    }

    @Test
    void groupQueueHoldsOnlyThisGroupsChatReports() {
        GroupPost mine = post("grp-1");
        GroupPost theirs = post("grp-2");
        CommunityReport onPost = report(CommunityReport.TargetType.GROUP_POST, mine.getId(), mine.getId(),
                CommunityReport.ReviewStatus.PENDING);
        CommunityReport onComment = report(CommunityReport.TargetType.GROUP_COMMENT, 99L, mine.getId(),
                CommunityReport.ReviewStatus.DISMISSED);
        report(CommunityReport.TargetType.GROUP_POST, theirs.getId(), theirs.getId(),
                CommunityReport.ReviewStatus.PENDING);
        // A community-feed report whose post_id collides with the group post id.
        report(CommunityReport.TargetType.POST, mine.getId(), mine.getId(), CommunityReport.ReviewStatus.PENDING);

        var types = EnumSet.of(CommunityReport.TargetType.GROUP_POST, CommunityReport.TargetType.GROUP_COMMENT);
        List<CommunityReport> all = repo.findGroupQueue("grp-1", types, PageRequest.of(0, 100));
        assertThat(all).extracting(CommunityReport::getId)
                .containsExactlyInAnyOrder(onPost.getId(), onComment.getId());

        List<CommunityReport> pending = repo.findGroupQueueByStatus("grp-1", types,
                CommunityReport.ReviewStatus.PENDING, PageRequest.of(0, 100));
        assertThat(pending).extracting(CommunityReport::getId).containsExactly(onPost.getId());
    }
}
