package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.AskBookmark;
import io.sitprep.sitprepapi.dto.*;
import io.sitprep.sitprepapi.service.AskService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST surface for the /ask experience. Reads are open (anonymous browse);
 * writes (post / vote / accept / bookmark / edit / delete) require a
 * verified Firebase token via {@link AuthUtils#requireAuthenticatedEmail()}.
 *
 * <p>Two opt-in headers shape responses without changing the URL:</p>
 * <ul>
 *   <li>{@code X-Active-Hazards} — comma-separated lowercase list (e.g.
 *       {@code "hurricane,wildfire"}). Items whose hazard tags intersect
 *       the set are pinned in a higher tier in list responses.</li>
 *   <li>The verified token, when present, hydrates {@code viewerVote},
 *       {@code viewerBookmarked}, and {@code viewerIsAuthor} on each row.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ask")
public class AskResource {

    private final AskService service;

    public AskResource(AskService service) {
        this.service = service;
    }

    // -----------------------------------------------------------------
    // Questions
    // -----------------------------------------------------------------

    @GetMapping("/questions")
    public List<AskQuestionDto> listQuestions(
            @RequestParam(required = false) String zipBucket,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestHeader(value = AskService.HAZARD_TYPE_HEADER, required = false) String hazards) {
        String viewer = AuthUtils.getCurrentUserEmail();
        return service.listQuestions(viewer, zipBucket, beforeId, limit,
                AskService.parseHazardHeader(hazards));
    }

    @GetMapping("/questions/top")
    public List<AskQuestionDto> topQuestions(
            @RequestParam(required = false, defaultValue = "week") String window,
            @RequestParam(required = false, defaultValue = "10") int limit,
            @RequestHeader(value = AskService.HAZARD_TYPE_HEADER, required = false) String hazards) {
        String viewer = AuthUtils.getCurrentUserEmail();
        return service.topQuestions(viewer, window, limit,
                AskService.parseHazardHeader(hazards));
    }

    @GetMapping("/questions/{id}")
    public AskQuestionDto getQuestion(
            @PathVariable Long id,
            @RequestHeader(value = AskService.HAZARD_TYPE_HEADER, required = false) String hazards) {
        return service.getQuestionDetail(id, AuthUtils.getCurrentUserEmail(),
                AskService.parseHazardHeader(hazards));
    }

    @PostMapping("/questions")
    public AskQuestionDto createQuestion(@RequestBody AskQuestionDto in) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        return service.createQuestion(actor, in);
    }

    @PutMapping("/questions/{id}")
    public AskQuestionDto editQuestion(@PathVariable Long id, @RequestBody AskQuestionDto in) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        return service.editQuestion(id, actor, in);
    }

    @DeleteMapping("/questions/{id}")
    public ResponseEntity<Void> deleteQuestion(@PathVariable Long id) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        service.deleteQuestion(id, actor);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------
    // Answers
    // -----------------------------------------------------------------

    @PostMapping("/questions/{id}/answers")
    public AskAnswerDto createAnswer(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        return service.createAnswer(id, actor, body == null ? null : body.get("body"));
    }

    @PutMapping("/answers/{id}")
    public AskAnswerDto editAnswer(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        return service.editAnswer(id, actor, body == null ? null : body.get("body"));
    }

    @DeleteMapping("/answers/{id}")
    public ResponseEntity<Void> deleteAnswer(@PathVariable Long id) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        service.deleteAnswer(id, actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/questions/{questionId}/accept-answer/{answerId}")
    public AskQuestionDto acceptAnswer(
            @PathVariable Long questionId,
            @PathVariable Long answerId) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        return service.acceptAnswer(questionId, answerId, actor);
    }

    // -----------------------------------------------------------------
    // Tips
    // -----------------------------------------------------------------

    @GetMapping("/tips")
    public List<AskTipDto> listTips(
            @RequestParam(required = false) String zipBucket,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestHeader(value = AskService.HAZARD_TYPE_HEADER, required = false) String hazards) {
        String viewer = AuthUtils.getCurrentUserEmail();
        return service.listTips(viewer, zipBucket, beforeId, limit,
                AskService.parseHazardHeader(hazards));
    }

    @GetMapping("/tips/{id}")
    public AskTipDto getTip(
            @PathVariable Long id,
            @RequestHeader(value = AskService.HAZARD_TYPE_HEADER, required = false) String hazards) {
        return service.getTipDetail(id, AuthUtils.getCurrentUserEmail(),
                AskService.parseHazardHeader(hazards));
    }

    @PostMapping("/tips")
    public AskTipDto createTip(@RequestBody AskTipDto in) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        return service.createTip(actor, in);
    }

    @PutMapping("/tips/{id}")
    public AskTipDto editTip(@PathVariable Long id, @RequestBody AskTipDto in) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        return service.editTip(id, actor, in);
    }

    @DeleteMapping("/tips/{id}")
    public ResponseEntity<Void> deleteTip(@PathVariable Long id) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        service.deleteTip(id, actor);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------
    // Votes
    // -----------------------------------------------------------------

    /**
     * Body: {@code { "value": +1 | -1 }}. Returns the new score on the parent.
     * Re-applying the same value un-votes; switching value flips.
     */
    @PostMapping("/{targetType}/{targetId}/vote")
    public Map<String, Object> vote(
            @PathVariable String targetType,
            @PathVariable Long targetId,
            @RequestBody Map<String, Object> body) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        int value = parseValue(body);
        int newScore = service.vote(targetType, targetId, actor, value);
        return Map.of(
                "targetType", targetType,
                "targetId", targetId,
                "voteScore", newScore,
                "viewerVote", value);
    }

    private static int parseValue(Map<String, Object> body) {
        if (body == null || !body.containsKey("value")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "value is required");
        }
        Object v = body.get("value");
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); }
        catch (NumberFormatException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "value must be +1 or -1");
        }
    }

    // -----------------------------------------------------------------
    // Bookmarks
    // -----------------------------------------------------------------

    @PostMapping("/bookmarks/toggle")
    public Map<String, Object> toggleBookmark(@RequestBody Map<String, String> body) {
        String actor = AuthUtils.requireAuthenticatedEmail();
        String targetType = body == null ? null : body.get("targetType");
        String targetKey = body == null ? null : body.get("targetKey");
        boolean now = service.toggleBookmark(actor, targetType, targetKey);
        return Map.of(
                "targetType", targetType,
                "targetKey", targetKey,
                "bookmarked", now);
    }

    @GetMapping("/bookmarks")
    public List<Map<String, Object>> listBookmarks() {
        String actor = AuthUtils.requireAuthenticatedEmail();
        return service.listBookmarks(actor).stream()
                .map(AskResource::bookmarkRow)
                .toList();
    }

    private static Map<String, Object> bookmarkRow(AskBookmark b) {
        return Map.of(
                "id", b.getId(),
                "targetType", b.getTargetType(),
                "targetKey", b.getTargetKey(),
                "createdAt", b.getCreatedAt());
    }

    // -----------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------

    @GetMapping("/search")
    public List<AskSearchHitDto> search(
            @RequestParam("q") String q,
            @RequestHeader(value = AskService.HAZARD_TYPE_HEADER, required = false) String hazards) {
        String viewer = AuthUtils.getCurrentUserEmail();
        return service.search(q, viewer, AskService.parseHazardHeader(hazards));
    }
}
