package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.CommunityReport;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.GroupPostComment;
import io.sitprep.sitprepapi.dto.CommunityReportDto;
import io.sitprep.sitprepapi.dto.CreateCommunityReportRequest;
import io.sitprep.sitprepapi.dto.ReviewCommunityReportRequest;
import io.sitprep.sitprepapi.repo.CommunityReportRepo;
import io.sitprep.sitprepapi.repo.GroupPostCommentRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.PostCommentRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Audit FD-01 (lane B6, D23): a report against a group's chat post or comment
 * is a real report. It is saved as GROUP_POST / GROUP_COMMENT, it reaches the
 * group's admins (their queue) and the platform MODERATE_REPORTS queue (with
 * the group id), an outsider can't file one (404), a plain member can't read
 * the group queue (403), and neither the receipt nor the admin queue leaks an
 * email the viewer shouldn't hold. The queue's JPQL is proven against H2 in
 * {@link io.sitprep.sitprepapi.repo.GroupContentReportRepoTest}.
 */
class GroupContentReportTest {

    private static final String GROUP = "grp-1";
    private static final String MEMBER = "member@x.com";
    private static final String AUTHOR = "author@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String OUTSIDER = "out@x.com";

    private CommunityReportRepo reportRepo;
    private GroupPostRepo groupPostRepo;
    private GroupPostCommentRepo groupCommentRepo;
    private GroupPostService groupPostService;
    private CommunityReportService service;
    private GroupPost post;

    @BeforeEach
    void setUp() {
        reportRepo = mock(CommunityReportRepo.class);
        groupPostRepo = mock(GroupPostRepo.class);
        groupCommentRepo = mock(GroupPostCommentRepo.class);
        groupPostService = mock(GroupPostService.class);
        service = new CommunityReportService(reportRepo, mock(PostRepo.class), mock(PostCommentRepo.class),
                mock(AdminAuditLogService.class), groupPostRepo, groupCommentRepo, groupPostService);

        post = new GroupPost();
        post.setId(7L);
        post.setGroupId(GROUP);
        post.setAuthor(AUTHOR);
        post.setContent("you are all idiots");
        when(groupPostRepo.findById(7L)).thenReturn(Optional.of(post));
        when(groupPostRepo.findAllById(anyCollection())).thenReturn(List.of(post));
        when(groupPostService.canRead(post, MEMBER)).thenReturn(true);
        when(groupPostService.canRead(post, ADMIN)).thenReturn(true);
        when(groupPostService.isGroupAdminOrOwner(GROUP, ADMIN)).thenReturn(true);
        when(reportRepo.save(any(CommunityReport.class))).thenAnswer(inv -> {
            CommunityReport r = inv.getArgument(0);
            if (r.getId() == null) r.setId(500L);
            return r;
        });
    }

    @Test
    void memberReportsAGroupPost_savedAsGroupPost_receiptHasNoAuthorEmail() {
        CommunityReportDto dto = service.create(
                new CreateCommunityReportRequest("GROUP_POST", 7L, "HARASSMENT", "please look"), MEMBER);

        assertThat(dto.targetType()).isEqualTo(CommunityReport.TargetType.GROUP_POST);
        assertThat(dto.postId()).isEqualTo(7L);
        assertThat(dto.groupId()).isEqualTo(GROUP);
        assertThat(dto.contentPreview()).contains("idiots");
        assertThat(dto.targetAuthorEmail()).as("receipt hides the co-member's email").isNull();
        verify(reportRepo).save(argThat(r -> AUTHOR.equals(r.getTargetAuthorEmail())));
    }

    @Test
    void memberReportsAGroupComment_keyedToItsPost() {
        GroupPostComment c = new GroupPostComment();
        c.setId(11L);
        c.setPostId(7L);
        c.setAuthor(AUTHOR);
        c.setContent("> Replying to Sam:\n> hi\n\nget lost");
        when(groupCommentRepo.findById(11L)).thenReturn(Optional.of(c));

        CommunityReportDto dto = service.create(
                new CreateCommunityReportRequest("group_comment", 11L, "HARASSMENT", null), MEMBER);

        assertThat(dto.targetType()).isEqualTo(CommunityReport.TargetType.GROUP_COMMENT);
        assertThat(dto.targetId()).isEqualTo(11L);
        assertThat(dto.postId()).isEqualTo(7L);
        assertThat(dto.contentPreview()).isEqualTo("get lost");
    }

    @Test
    void outsiderCannotReportGroupContent_404() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(
                new CreateCommunityReportRequest("GROUP_POST", 7L, "SPAM", null), OUTSIDER));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(reportRepo, never()).save(any());
    }

    @Test
    void adminQueueListsTheReport_withoutReporterEmail_andPlainMemberIs403() {
        CommunityReport row = new CommunityReport();
        row.setId(500L);
        row.setTargetType(CommunityReport.TargetType.GROUP_POST);
        row.setTargetId(7L);
        row.setPostId(7L);
        row.setReporterEmail(MEMBER);
        row.setTargetAuthorEmail(AUTHOR);
        row.setReason(CommunityReport.Reason.HARASSMENT);
        row.setStatus(CommunityReport.ReviewStatus.PENDING);
        when(reportRepo.findGroupQueueByStatus(eq(GROUP), anyCollection(),
                eq(CommunityReport.ReviewStatus.PENDING), any())).thenReturn(List.of(row));

        List<CommunityReportDto> queue = service.listGroupReports(GROUP, null, ADMIN);
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).groupId()).isEqualTo(GROUP);
        assertThat(queue.get(0).reporterEmail()).as("admins act on content, not reporters").isNull();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listGroupReports(GROUP, null, MEMBER));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());

        // ...and the platform MODERATE_REPORTS queue gets the same row with its group.
        when(reportRepo.findByStatusOrderByCreatedAtDesc(CommunityReport.ReviewStatus.PENDING))
                .thenReturn(List.of(row));
        List<CommunityReportDto> platform = service.listReports("PENDING");
        assertThat(platform.get(0).groupId()).isEqualTo(GROUP);
        assertThat(platform.get(0).reporterEmail()).isEqualTo(MEMBER);
    }

    @Test
    void reportAboutTheAdminsOwnPost_isLeftOutOfTheirQueue_andTheyCannotReviewIt() {
        CommunityReport row = new CommunityReport();
        row.setId(501L);
        row.setTargetType(CommunityReport.TargetType.GROUP_POST);
        row.setPostId(7L);
        row.setTargetAuthorEmail(ADMIN);
        row.setReason(CommunityReport.Reason.HARASSMENT);
        when(reportRepo.findGroupQueue(eq(GROUP), anyCollection(), any())).thenReturn(List.of(row));
        when(reportRepo.findById(501L)).thenReturn(Optional.of(row));

        assertThat(service.listGroupReports(GROUP, "ALL", ADMIN)).isEmpty();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.reviewGroupReport(GROUP, 501L, new ReviewCommunityReportRequest("DISMISSED", null), ADMIN));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void adminReviewsAReportOnTheirGroup_butNotAnotherGroupsReport() {
        CommunityReport row = new CommunityReport();
        row.setId(502L);
        row.setTargetType(CommunityReport.TargetType.GROUP_POST);
        row.setPostId(7L);
        row.setTargetAuthorEmail(AUTHOR);
        row.setReporterEmail(MEMBER);
        row.setReason(CommunityReport.Reason.SPAM);
        row.setStatus(CommunityReport.ReviewStatus.PENDING);
        when(reportRepo.findById(502L)).thenReturn(Optional.of(row));

        CommunityReportDto out = service.reviewGroupReport(GROUP, 502L,
                new ReviewCommunityReportRequest("ACTIONED", "removed"), ADMIN);
        assertThat(out.status()).isEqualTo(CommunityReport.ReviewStatus.ACTIONED);
        assertThat(out.reporterEmail()).isNull();

        when(groupPostService.isGroupAdminOrOwner("grp-2", ADMIN)).thenReturn(true);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.reviewGroupReport("grp-2", 502L, new ReviewCommunityReportRequest("DISMISSED", null), ADMIN));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(groupPostService, atLeastOnce()).isGroupAdminOrOwner(anyString(), anyString());
    }
}
