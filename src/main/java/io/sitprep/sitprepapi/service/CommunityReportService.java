package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.CommunityReport;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.PostComment;
import io.sitprep.sitprepapi.dto.CommunityReportDto;
import io.sitprep.sitprepapi.dto.CreateCommunityReportRequest;
import io.sitprep.sitprepapi.dto.ReviewCommunityReportRequest;
import io.sitprep.sitprepapi.repo.CommunityReportRepo;
import io.sitprep.sitprepapi.repo.PostCommentRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class CommunityReportService {

    private final CommunityReportRepo reportRepo;
    private final PostRepo postRepo;
    private final PostCommentRepo commentRepo;

    public CommunityReportService(CommunityReportRepo reportRepo,
                                  PostRepo postRepo,
                                  PostCommentRepo commentRepo) {
        this.reportRepo = reportRepo;
        this.postRepo = postRepo;
        this.commentRepo = commentRepo;
    }

    @Transactional
    public CommunityReportDto create(CreateCommunityReportRequest req, String reporterEmail) {
        if (reporterEmail == null || reporterEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to report content");
        }
        CommunityReport.TargetType targetType = parseTargetType(req == null ? null : req.targetType());
        Long targetId = req == null ? null : req.targetId();
        if (targetId == null || targetId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetId required");
        }

        CommunityReport report = new CommunityReport();
        report.setTargetType(targetType);
        report.setTargetId(targetId);
        report.setReporterEmail(normalize(reporterEmail));
        report.setReason(parseReason(req.reason()));
        report.setDetails(trim(req.details(), 1000));

        if (targetType == CommunityReport.TargetType.POST) {
            Post post = postRepo.findById(targetId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
            report.setPostId(post.getId());
            report.setTargetAuthorEmail(normalize(post.getRequesterEmail()));
            report.setContentPreview(trim(message(post.getTitle(), post.getDescription()), 1000));
        } else {
            PostComment comment = commentRepo.findById(targetId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));
            report.setPostId(comment.getPostId());
            report.setTargetAuthorEmail(normalize(comment.getAuthor()));
            report.setContentPreview(trim(stripReplyQuote(comment.getContent()), 1000));
        }

        return CommunityReportDto.from(reportRepo.save(report));
    }

    @Transactional(readOnly = true)
    public List<CommunityReportDto> listReports(String rawStatus) {
        CommunityReport.ReviewStatus status = parseReviewStatus(rawStatus, true);
        List<CommunityReport> rows = status == null
                ? reportRepo.findTop100ByOrderByCreatedAtDesc()
                : reportRepo.findByStatusOrderByCreatedAtDesc(status);
        return rows.stream().map(CommunityReportDto::from).toList();
    }

    @Transactional
    public CommunityReportDto review(Long id, ReviewCommunityReportRequest req, String reviewerEmail) {
        CommunityReport row = reportRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));
        row.setStatus(parseReviewStatus(req == null ? null : req.status(), false));
        row.setReviewerEmail(normalize(reviewerEmail));
        row.setReviewerNotes(trim(req == null ? null : req.reviewerNotes(), 1000));
        row.setReviewedAt(Instant.now());
        return CommunityReportDto.from(reportRepo.save(row));
    }

    private static CommunityReport.TargetType parseTargetType(String raw) {
        String value = normalizeToken(raw);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetType must be POST or COMMENT");
        }
        try {
            return CommunityReport.TargetType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetType must be POST or COMMENT");
        }
    }

    private static CommunityReport.Reason parseReason(String raw) {
        String value = normalizeToken(raw);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason required");
        }
        try {
            return CommunityReport.Reason.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reason must be SPAM, HARASSMENT, MISINFORMATION, IMPERSONATION, SCAM, SAFETY_RISK, HATE, or OTHER");
        }
    }

    private static CommunityReport.ReviewStatus parseReviewStatus(String raw, boolean allowAll) {
        String value = normalizeToken(raw);
        if (value == null) return allowAll ? CommunityReport.ReviewStatus.PENDING : null;
        if (allowAll && "ALL".equals(value)) return null;
        try {
            return CommunityReport.ReviewStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status must be PENDING, REVIEWED, DISMISSED, ACTIONED, NEEDS_INFO, or ALL");
        }
    }

    private static String normalizeToken(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static String normalize(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String raw, int max) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    private static String message(String title, String description) {
        String t = title == null ? "" : title.trim();
        String d = description == null ? "" : description.trim();
        if (t.isBlank()) return d;
        if (d.isBlank()) return t;
        return t + "\n\n" + d;
    }

    private static String stripReplyQuote(String content) {
        if (content == null) return null;
        if (!content.startsWith("> Replying to")) return content;
        String[] parts = content.split("\\n\\n", 2);
        return parts.length == 2 ? parts[1] : content;
    }
}
