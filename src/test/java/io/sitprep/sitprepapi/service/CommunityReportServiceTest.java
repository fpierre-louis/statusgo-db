package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.CommunityReport;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.dto.CreateCommunityReportRequest;
import io.sitprep.sitprepapi.repo.CommunityReportRepo;
import io.sitprep.sitprepapi.repo.PostCommentRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommunityReportServiceTest {

    private final CommunityReportRepo reportRepo = mock(CommunityReportRepo.class);
    private final PostRepo postRepo = mock(PostRepo.class);
    private final PostCommentRepo commentRepo = mock(PostCommentRepo.class);
    private final CommunityReportService service = new CommunityReportService(
            reportRepo,
            postRepo,
            commentRepo,
            mock(AdminAuditLogService.class)
    );

    @ParameterizedTest
    @ValueSource(strings = {
            "MISINFORMATION",
            "MISLEADING_AUTHORITY",
            "IMPERSONATION",
            "SCAM",
            "PRICE_GOUGING",
            "EMERGENCY_EXPLOITATION",
            "UNLICENSED_EMERGENCY_SERVICE",
            "HARASSMENT",
            "HATE",
            "SPAM",
            "SAFETY_RISK",
            "OTHER"
    })
    void acceptsEveryFrontendReportReason(String reason) {
        Post post = new Post();
        post.setId(44L);
        post.setRequesterEmail("author@example.com");
        post.setTitle("Generator available");
        post.setDescription("Cash only.");

        when(postRepo.findById(44L)).thenReturn(Optional.of(post));
        when(reportRepo.save(any(CommunityReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var dto = service.create(
                new CreateCommunityReportRequest("POST", 44L, reason, "review this"),
                "reporter@example.com"
        );

        assertThat(dto.reason()).isEqualTo(CommunityReport.Reason.valueOf(reason));
        assertThat(dto.targetAuthorEmail()).isEqualTo("author@example.com");
        assertThat(dto.contentPreview()).contains("Generator available");
    }
}
