package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.CommunityReport;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.GroupPostComment;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.PostComment;
import io.sitprep.sitprepapi.dto.CommunityReportDto;
import io.sitprep.sitprepapi.dto.CreateCommunityReportRequest;
import io.sitprep.sitprepapi.dto.ReviewCommunityReportRequest;
import io.sitprep.sitprepapi.repo.CommunityReportRepo;
import io.sitprep.sitprepapi.repo.GroupPostCommentRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.PostCommentRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class CommunityReportService {

    private final CommunityReportRepo reportRepo;
    private final PostRepo postRepo;
    private final PostCommentRepo commentRepo;
    private final AdminAuditLogService adminAuditLogService;
    private final GroupPostRepo groupPostRepo;
    private final GroupPostCommentRepo groupCommentRepo;
    private final GroupPostService groupPostService;

    private static final Set<CommunityReport.TargetType> GROUP_TYPES =
            EnumSet.of(CommunityReport.TargetType.GROUP_POST, CommunityReport.TargetType.GROUP_COMMENT);
    /** A group's queue is a working list, not an archive. */
    private static final int GROUP_QUEUE_LIMIT = 100;

    public CommunityReportService(CommunityReportRepo reportRepo,
                                  PostRepo postRepo,
                                  PostCommentRepo commentRepo,
                                  AdminAuditLogService adminAuditLogService,
                                  GroupPostRepo groupPostRepo,
                                  GroupPostCommentRepo groupCommentRepo,
                                  GroupPostService groupPostService) {
        this.reportRepo = reportRepo;
        this.postRepo = postRepo;
        this.commentRepo = commentRepo;
        this.adminAuditLogService = adminAuditLogService;
        this.groupPostRepo = groupPostRepo;
        this.groupCommentRepo = groupCommentRepo;
        this.groupPostService = groupPostService;
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

        boolean authorHidden = false;
        String groupId = null;
        if (targetType.isGroupContent()) {
            // FD-01: a group's chat post or comment. The reporter must be able
            // to read it (a member, or its author); otherwise 404, the same
            // answer as "no such id", so the endpoint can't census group content.
            GroupPost gp;
            if (targetType == CommunityReport.TargetType.GROUP_POST) {
                gp = groupPostRepo.findById(targetId)
                        .filter(p -> groupPostService.canRead(p, reporterEmail))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
                report.setTargetAuthorEmail(normalize(gp.getAuthor()));
                report.setContentPreview(trim(gp.getContent(), 1000));
            } else {
                GroupPostComment c = groupCommentRepo.findById(targetId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));
                gp = groupPostRepo.findById(c.getPostId())
                        .filter(p -> groupPostService.canRead(p, reporterEmail))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));
                report.setTargetAuthorEmail(normalize(c.getAuthor()));
                report.setContentPreview(trim(stripReplyQuote(c.getContent()), 1000));
            }
            report.setPostId(gp.getId());
            groupId = gp.getGroupId();
            // The reporter is a co-member: the receipt never carries the
            // author's email (plain org members don't see emails, D6).
            authorHidden = true;
        } else if (targetType == CommunityReport.TargetType.POST) {
            Post post = postRepo.findById(targetId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
            report.setPostId(post.getId());
            report.setTargetAuthorEmail(normalize(post.getRequesterEmail()));
            report.setContentPreview(trim(message(post.getTitle(), post.getDescription()), 1000));
            authorHidden = post.isAuthorHidden();
        } else {
            PostComment comment = commentRepo.findById(targetId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));
            report.setPostId(comment.getPostId());
            report.setTargetAuthorEmail(normalize(comment.getAuthor()));
            report.setContentPreview(trim(stripReplyQuote(comment.getContent()), 1000));
        }

        CommunityReportDto saved = CommunityReportDto.from(reportRepo.save(report)).withGroupId(groupId);
        // HR6: moderation keeps the author; the person who filed the report
        // must not learn who an anonymous hazard reporter is from the receipt.
        return authorHidden ? saved.withoutTargetAuthor() : saved;
    }

    @Transactional(readOnly = true)
    public List<CommunityReportDto> listReports(String rawStatus) {
        CommunityReport.ReviewStatus status = parseReviewStatus(rawStatus, true);
        List<CommunityReport> rows = status == null
                ? reportRepo.findTop100ByOrderByCreatedAtDesc()
                : reportRepo.findByStatusOrderByCreatedAtDesc(status);
        return withGroupIds(rows);
    }

    /**
     * A group's report queue (FD-01): reports on its chat posts and comments,
     * for the group's owner and admins only (403 otherwise). Rows carry no
     * reporter email, and a report about the caller's own content is left out
     * (it still reaches the other admins and the platform MODERATE_REPORTS queue).
     */
    @Transactional(readOnly = true)
    public List<CommunityReportDto> listGroupReports(String groupId, String rawStatus, String callerEmail) {
        requireGroupModerator(groupId, callerEmail);
        CommunityReport.ReviewStatus status = parseReviewStatus(rawStatus, true);
        PageRequest page = PageRequest.of(0, GROUP_QUEUE_LIMIT);
        List<CommunityReport> rows = status == null
                ? reportRepo.findGroupQueue(groupId, GROUP_TYPES, page)
                : reportRepo.findGroupQueueByStatus(groupId, GROUP_TYPES, status, page);
        String me = normalize(callerEmail);
        return rows.stream()
                .filter(r -> r.getTargetAuthorEmail() == null || !r.getTargetAuthorEmail().equals(me))
                .map(r -> CommunityReportDto.from(r).withGroupId(groupId).withoutReporter())
                .toList();
    }

    /**
     * A group admin reviews a report on the group's own content. The report
     * must belong to this group (404 otherwise, so ids can't be probed across
     * groups), and an admin can't review a report about their own content.
     */
    @Transactional
    public CommunityReportDto reviewGroupReport(String groupId, Long id, ReviewCommunityReportRequest req,
                                                String callerEmail) {
        requireGroupModerator(groupId, callerEmail);
        CommunityReport row = reportRepo.findById(id)
                .filter(r -> r.getTargetType() != null && r.getTargetType().isGroupContent())
                .filter(r -> r.getPostId() != null && groupPostRepo.findById(r.getPostId())
                        .map(gp -> Objects.equals(gp.getGroupId(), groupId)).orElse(false))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));
        if (row.getTargetAuthorEmail() != null && row.getTargetAuthorEmail().equals(normalize(callerEmail))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Another admin reviews a report about your own post");
        }
        return review(id, req, callerEmail).withGroupId(groupId).withoutReporter();
    }

    private void requireGroupModerator(String groupId, String callerEmail) {
        if (!groupPostService.isGroupAdminOrOwner(groupId, callerEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Group admin or owner role required");
        }
    }

    /** Platform queue rows: fold each group report's group id (one batched read). */
    private List<CommunityReportDto> withGroupIds(List<CommunityReport> rows) {
        List<Long> groupPostIds = rows.stream()
                .filter(r -> r.getTargetType() != null && r.getTargetType().isGroupContent())
                .map(CommunityReport::getPostId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> groupByPost = new HashMap<>();
        if (!groupPostIds.isEmpty()) {
            for (GroupPost gp : groupPostRepo.findAllById(groupPostIds)) {
                groupByPost.put(gp.getId(), gp.getGroupId());
            }
        }
        return rows.stream().map(r -> {
            CommunityReportDto d = CommunityReportDto.from(r);
            return r.getTargetType() != null && r.getTargetType().isGroupContent()
                    ? d.withGroupId(groupByPost.get(r.getPostId())) : d;
        }).toList();
    }

    @Transactional
    public CommunityReportDto review(Long id, ReviewCommunityReportRequest req, String reviewerEmail) {
        CommunityReport row = reportRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));
        CommunityReport.ReviewStatus previous = row.getStatus();
        CommunityReport.ReviewStatus next = parseReviewStatus(req == null ? null : req.status(), false);
        row.setStatus(next);
        row.setReviewerEmail(normalize(reviewerEmail));
        row.setReviewerNotes(trim(req == null ? null : req.reviewerNotes(), 1000));
        row.setReviewedAt(Instant.now());
        CommunityReport saved = reportRepo.save(row);
        adminAuditLogService.record(
                reviewerEmail,
                "REVIEWED_REPORT",
                "report",
                String.valueOf(saved.getId()),
                "status " + previous + " -> " + next
                        + "; target=" + saved.getTargetType() + "#" + saved.getTargetId());
        return CommunityReportDto.from(saved);
    }

    private static final String TARGET_TYPE_MESSAGE =
            "targetType must be POST, COMMENT, GROUP_POST or GROUP_COMMENT";

    private static CommunityReport.TargetType parseTargetType(String raw) {
        String value = normalizeToken(raw);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, TARGET_TYPE_MESSAGE);
        }
        try {
            return CommunityReport.TargetType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, TARGET_TYPE_MESSAGE);
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
                    "reason must be one of the supported community report reasons");
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
