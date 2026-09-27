package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.CivicStatus;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CivicWorkLifecycleServiceTest {

    @Autowired CivicAgencyService civic;
    @Autowired PostRepo postRepo;

    private Post report(String status) {
        Post report = new Post();
        report.setKind("civic-report");
        report.setRequesterEmail("resident@example.com");
        report.setCivicCategory("pothole");
        report.setCivicStatus(status);
        return postRepo.save(report);
    }

    private Post work(Long sourceId, PostStatus status, String title) {
        Post work = new Post();
        work.setKind("task");
        work.setRequesterEmail("dispatcher@example.gov");
        work.setSourcePostId(sourceId);
        work.setStatus(status);
        work.setTitle(title);
        return postRepo.save(work);
    }

    @Test
    void firstLinkedStartSchedulesAcknowledgedReport_once() {
        Post report = report(CivicStatus.ACKNOWLEDGED.wire());
        Post first = work(report.getId(), PostStatus.IN_PROGRESS, "Inspect site");
        Post second = work(report.getId(), PostStatus.IN_PROGRESS, "Repair surface");

        assertThat(civic.scheduleReportWhenLinkedWorkStarts(first.getId())).isEqualTo(report.getId());
        assertThat(civic.scheduleReportWhenLinkedWorkStarts(second.getId())).isNull();

        Post scheduled = postRepo.findById(report.getId()).orElseThrow();
        assertThat(scheduled.getCivicStatus()).isEqualTo(CivicStatus.SCHEDULED.wire());
        assertThat(scheduled.getScheduledFor()).isNotNull();
    }

    @Test
    void linkedStartNeverRegressesReportedOrResolvedReport() {
        Post reported = report(CivicStatus.REPORTED.wire());
        Post resolved = report(CivicStatus.RESOLVED.wire());

        assertThat(civic.scheduleReportWhenLinkedWorkStarts(
                work(reported.getId(), PostStatus.IN_PROGRESS, "Reported work").getId())).isNull();
        assertThat(civic.scheduleReportWhenLinkedWorkStarts(
                work(resolved.getId(), PostStatus.IN_PROGRESS, "Resolved work").getId())).isNull();

        assertThat(postRepo.findById(reported.getId()).orElseThrow().getCivicStatus())
                .isEqualTo(CivicStatus.REPORTED.wire());
        assertThat(postRepo.findById(resolved.getId()).orElseThrow().getCivicStatus())
                .isEqualTo(CivicStatus.RESOLVED.wire());
    }

    @Test
    void progressRequiresEveryNonCancelledWorkOrderDone_andReopenRemovesReadiness() {
        Post report = report(CivicStatus.SCHEDULED.wire());
        work(report.getId(), PostStatus.DONE, "Patch road");
        Post inspection = work(report.getId(), PostStatus.IN_PROGRESS, "Final inspection");
        work(report.getId(), PostStatus.CANCELLED, "Duplicate dispatch");

        CivicAgencyService.LinkedWorkProgress progress = progress(report.getId());
        assertThat(progress.total()).isEqualTo(3);
        assertThat(progress.completed()).isEqualTo(1);
        assertThat(progress.inProgress()).isEqualTo(1);
        assertThat(progress.cancelled()).isEqualTo(1);
        assertThat(progress.resolutionReady()).isFalse();

        inspection.setStatus(PostStatus.DONE);
        postRepo.saveAndFlush(inspection);
        assertThat(progress(report.getId()).resolutionReady()).isTrue();

        inspection.setStatus(PostStatus.IN_PROGRESS);
        postRepo.saveAndFlush(inspection);
        assertThat(progress(report.getId()).resolutionReady()).isFalse();
    }

    @Test
    void legacyWorkLinkedToMergedDuplicateSchedulesCanonical() {
        Post canonical = report(CivicStatus.ACKNOWLEDGED.wire());
        Post duplicate = report(CivicStatus.REPORTED.wire());
        duplicate.setMergedIntoPostId(canonical.getId());
        postRepo.save(duplicate);
        Post linked = work(duplicate.getId(), PostStatus.IN_PROGRESS, "Legacy link");

        assertThat(civic.scheduleReportWhenLinkedWorkStarts(linked.getId())).isEqualTo(canonical.getId());
        assertThat(postRepo.findById(canonical.getId()).orElseThrow().getCivicStatus())
                .isEqualTo(CivicStatus.SCHEDULED.wire());
        assertThat(postRepo.findById(duplicate.getId()).orElseThrow().getCivicStatus())
                .isEqualTo(CivicStatus.REPORTED.wire());
    }

    private CivicAgencyService.LinkedWorkProgress progress(Long reportId) {
        Map<Long, CivicAgencyService.LinkedWorkProgress> progress =
                civic.linkedWorkProgress(List.of(reportId));
        return progress.get(reportId);
    }
}
