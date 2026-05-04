package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.*;
import io.sitprep.sitprepapi.dto.*;
import io.sitprep.sitprepapi.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Single service for the /ask surface — questions, answers, tips, votes,
 * bookmarks, and unified search. Kept as one class so the cross-entity
 * concerns (vote-score denormalization, hot-score ranking, viewer-relative
 * hydration) live next to each other rather than scattered across helpers.
 *
 * <h3>Hot-score ranking</h3>
 * <pre>
 *   hot(voteScore, daysOld) = log10(max(voteScore + 1, 1)) + max(0, 14 - daysOld) * 0.15
 * </pre>
 * Items with one of {@code hazardTags} matching an active alert in the
 * viewer's area are pinned in a higher tier (sorted ABOVE all non-matches);
 * within each tier, hot-score DESC then createdAt DESC.
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private static final int MAX_PAGE = 50;
    private static final int DEFAULT_PAGE = 20;
    private static final int SEARCH_CAP = 60;

    /** Used by FE list endpoints to communicate the active hazard set. */
    public static final String HAZARD_TYPE_HEADER = "X-Active-Hazards";

    private final AskQuestionRepo questionRepo;
    private final AskAnswerRepo answerRepo;
    private final AskTipRepo tipRepo;
    private final AskVoteRepo voteRepo;
    private final AskBookmarkRepo bookmarkRepo;
    private final UserInfoRepo userInfoRepo;
    private final NominatimGeocodeService geocode;

    public AskService(AskQuestionRepo questionRepo,
                      AskAnswerRepo answerRepo,
                      AskTipRepo tipRepo,
                      AskVoteRepo voteRepo,
                      AskBookmarkRepo bookmarkRepo,
                      UserInfoRepo userInfoRepo,
                      NominatimGeocodeService geocode) {
        this.questionRepo = questionRepo;
        this.answerRepo = answerRepo;
        this.tipRepo = tipRepo;
        this.voteRepo = voteRepo;
        this.bookmarkRepo = bookmarkRepo;
        this.userInfoRepo = userInfoRepo;
        this.geocode = geocode;
    }

    // =================================================================
    // Questions
    // =================================================================

    @Transactional
    public AskQuestionDto createQuestion(String authorEmail, AskQuestionDto in) {
        if (isBlank(in.getTitle())) bad("Title is required");
        if (isBlank(in.getBody())) bad("Body is required");

        AskQuestion q = new AskQuestion();
        q.setAuthorEmail(normalize(authorEmail));
        q.setTitle(in.getTitle().trim());
        q.setBody(in.getBody().trim());
        q.setTags(normalizeTagSet(in.getTags()));
        q.setHazardTags(normalizeHazardSet(in.getHazardTags()));
        q.setLatitude(in.getLatitude());
        q.setLongitude(in.getLongitude());

        enrichGeo(in.getLatitude(), in.getLongitude(), q::setZipBucket, q::setPlaceLabel);

        return toDto(questionRepo.save(q), authorEmail, activeHazardsFor(authorEmail));
    }

    public List<AskQuestionDto> listQuestions(String viewerEmail,
                                              String zipBucket,
                                              Long beforeId,
                                              int limit,
                                              Set<String> activeHazards) {
        int safe = clamp(limit);
        var pageable = PageRequest.of(0, safe);
        List<AskQuestion> rows = (zipBucket == null || zipBucket.isBlank())
                ? (beforeId == null
                        ? questionRepo.findAllByOrderByIdDesc(pageable)
                        : questionRepo.findByIdLessThanOrderByIdDesc(beforeId, pageable))
                : (beforeId == null
                        ? questionRepo.findByZipBucketOrderByIdDesc(zipBucket, pageable)
                        : questionRepo.findByZipBucketAndIdLessThanOrderByIdDesc(zipBucket, beforeId, pageable));
        return rankAndDtoQuestions(rows, viewerEmail, activeHazards, /*includeAnswers=*/ false);
    }

    public List<AskQuestionDto> topQuestions(String viewerEmail, String window, int limit, Set<String> activeHazards) {
        Instant since = sinceFor(window);
        var pageable = PageRequest.of(0, clamp(limit));
        List<AskQuestion> rows = questionRepo.topSince(since, pageable);
        return rankAndDtoQuestions(rows, viewerEmail, activeHazards, false);
    }

    @Transactional
    public AskQuestionDto getQuestionDetail(Long id, String viewerEmail, Set<String> activeHazards) {
        AskQuestion q = questionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found"));
        // Best-effort view bump; ignore failures so a flaky write doesn't block the read.
        try { questionRepo.incrementViewCount(id); } catch (Exception ignored) {}

        AskQuestionDto dto = toDto(q, viewerEmail, activeHazards);
        dto.setAnswers(loadAnswersFor(q, viewerEmail));
        return dto;
    }

    @Transactional
    public AskQuestionDto editQuestion(Long id, String actorEmail, AskQuestionDto in) {
        AskQuestion q = questionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found"));
        ensureAuthor(q.getAuthorEmail(), actorEmail);
        if (in.getTitle() != null && !in.getTitle().isBlank()) q.setTitle(in.getTitle().trim());
        if (in.getBody() != null && !in.getBody().isBlank()) q.setBody(in.getBody().trim());
        if (in.getTags() != null) q.setTags(normalizeTagSet(in.getTags()));
        if (in.getHazardTags() != null) q.setHazardTags(normalizeHazardSet(in.getHazardTags()));
        q.setEditedAt(Instant.now());
        return toDto(q, actorEmail, activeHazardsFor(actorEmail));
    }

    @Transactional
    public void deleteQuestion(Long id, String actorEmail) {
        AskQuestion q = questionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found"));
        ensureAuthor(q.getAuthorEmail(), actorEmail);
        questionRepo.delete(q);
    }

    // =================================================================
    // Answers
    // =================================================================

    @Transactional
    public AskAnswerDto createAnswer(Long questionId, String authorEmail, String body) {
        AskQuestion q = questionRepo.findById(questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found"));
        if (isBlank(body)) bad("Answer body is required");

        AskAnswer a = new AskAnswer();
        a.setQuestionId(q.getId());
        a.setAuthorEmail(normalize(authorEmail));
        a.setBody(body.trim());
        AskAnswer saved = answerRepo.save(a);
        questionRepo.bumpAnswerCount(q.getId(), +1);
        return toAnswerDto(saved, authorEmail, q.getAcceptedAnswerId());
    }

    @Transactional
    public AskAnswerDto editAnswer(Long answerId, String actorEmail, String body) {
        AskAnswer a = answerRepo.findById(answerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Answer not found"));
        ensureAuthor(a.getAuthorEmail(), actorEmail);
        if (isBlank(body)) bad("Answer body is required");
        a.setBody(body.trim());
        a.setEditedAt(Instant.now());
        Long acceptedId = questionRepo.findById(a.getQuestionId())
                .map(AskQuestion::getAcceptedAnswerId).orElse(null);
        return toAnswerDto(a, actorEmail, acceptedId);
    }

    @Transactional
    public void deleteAnswer(Long answerId, String actorEmail) {
        AskAnswer a = answerRepo.findById(answerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Answer not found"));
        ensureAuthor(a.getAuthorEmail(), actorEmail);
        answerRepo.delete(a);
        questionRepo.bumpAnswerCount(a.getQuestionId(), -1);
        // If this was the accepted answer, clear the accept flag.
        questionRepo.findById(a.getQuestionId()).ifPresent(q -> {
            if (Objects.equals(q.getAcceptedAnswerId(), answerId)) {
                q.setAcceptedAnswerId(null);
            }
        });
    }

    @Transactional
    public AskQuestionDto acceptAnswer(Long questionId, Long answerId, String actorEmail) {
        AskQuestion q = questionRepo.findById(questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found"));
        ensureAuthor(q.getAuthorEmail(), actorEmail);
        AskAnswer a = answerRepo.findById(answerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Answer not found"));
        if (!Objects.equals(a.getQuestionId(), questionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer does not belong to question");
        }
        // Toggle: tapping the already-accepted answer un-accepts.
        if (Objects.equals(q.getAcceptedAnswerId(), answerId)) {
            q.setAcceptedAnswerId(null);
        } else {
            q.setAcceptedAnswerId(answerId);
        }
        return toDto(q, actorEmail, activeHazardsFor(actorEmail));
    }

    // =================================================================
    // Tips
    // =================================================================

    @Transactional
    public AskTipDto createTip(String authorEmail, AskTipDto in) {
        if (isBlank(in.getTitle())) bad("Title is required");
        if (isBlank(in.getBody())) bad("Body is required");

        AskTip t = new AskTip();
        t.setAuthorEmail(normalize(authorEmail));
        t.setTitle(in.getTitle().trim());
        t.setBody(in.getBody().trim());
        t.setCoverImageKey(in.getCoverImageKey());
        if (in.getImageKeys() != null) t.setImageKeys(new ArrayList<>(in.getImageKeys()));
        t.setTags(normalizeTagSet(in.getTags()));
        t.setHazardTags(normalizeHazardSet(in.getHazardTags()));
        t.setLatitude(in.getLatitude());
        t.setLongitude(in.getLongitude());

        enrichGeo(in.getLatitude(), in.getLongitude(), t::setZipBucket, t::setPlaceLabel);

        return toDto(tipRepo.save(t), authorEmail, activeHazardsFor(authorEmail));
    }

    public List<AskTipDto> listTips(String viewerEmail, String zipBucket, Long beforeId, int limit, Set<String> activeHazards) {
        int safe = clamp(limit);
        var pageable = PageRequest.of(0, safe);
        List<AskTip> rows = (zipBucket == null || zipBucket.isBlank())
                ? (beforeId == null
                        ? tipRepo.findAllByOrderByIdDesc(pageable)
                        : tipRepo.findByIdLessThanOrderByIdDesc(beforeId, pageable))
                : (beforeId == null
                        ? tipRepo.findByZipBucketOrderByIdDesc(zipBucket, pageable)
                        : tipRepo.findByZipBucketAndIdLessThanOrderByIdDesc(zipBucket, beforeId, pageable));
        return rankAndDtoTips(rows, viewerEmail, activeHazards);
    }

    @Transactional
    public AskTipDto getTipDetail(Long id, String viewerEmail, Set<String> activeHazards) {
        AskTip t = tipRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tip not found"));
        try { tipRepo.incrementViewCount(id); } catch (Exception ignored) {}
        return toDto(t, viewerEmail, activeHazards);
    }

    @Transactional
    public AskTipDto editTip(Long id, String actorEmail, AskTipDto in) {
        AskTip t = tipRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tip not found"));
        ensureAuthor(t.getAuthorEmail(), actorEmail);
        if (in.getTitle() != null && !in.getTitle().isBlank()) t.setTitle(in.getTitle().trim());
        if (in.getBody() != null && !in.getBody().isBlank()) t.setBody(in.getBody().trim());
        if (in.getCoverImageKey() != null) t.setCoverImageKey(in.getCoverImageKey());
        if (in.getImageKeys() != null) t.setImageKeys(new ArrayList<>(in.getImageKeys()));
        if (in.getTags() != null) t.setTags(normalizeTagSet(in.getTags()));
        if (in.getHazardTags() != null) t.setHazardTags(normalizeHazardSet(in.getHazardTags()));
        t.setEditedAt(Instant.now());
        return toDto(t, actorEmail, activeHazardsFor(actorEmail));
    }

    @Transactional
    public void deleteTip(Long id, String actorEmail) {
        AskTip t = tipRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tip not found"));
        ensureAuthor(t.getAuthorEmail(), actorEmail);
        tipRepo.delete(t);
    }

    // =================================================================
    // Votes
    // =================================================================

    /**
     * Apply a vote ({@code +1} / {@code -1}) on a question / answer / tip.
     * Re-applying the same value un-votes (deletes the row); flipping the
     * value updates and adjusts the parent's denormalized vote score by
     * the delta.
     *
     * @return the new vote score on the parent after the change
     */
    @Transactional
    public int vote(String targetType, Long targetId, String voterEmail, int value) {
        if (value != 1 && value != -1) bad("Vote value must be +1 or -1");
        String tt = normalizeTargetType(targetType, /*allowGuide=*/ false);
        String voter = normalize(voterEmail);

        Optional<AskVote> existing = voteRepo.findByTargetTypeAndTargetIdAndVoterEmail(tt, targetId, voter);

        int delta;
        if (existing.isPresent()) {
            AskVote v = existing.get();
            if (v.getValue() == value) {
                // Same direction → un-vote (toggle off).
                voteRepo.delete(v);
                delta = -value;
            } else {
                // Flip — delta is double, e.g. -1 → +1 = +2.
                delta = value - v.getValue();
                v.setValue(value);
            }
        } else {
            AskVote v = new AskVote();
            v.setTargetType(tt);
            v.setTargetId(targetId);
            v.setVoterEmail(voter);
            v.setValue(value);
            voteRepo.save(v);
            delta = value;
        }

        return bumpScore(tt, targetId, delta);
    }

    private int bumpScore(String targetType, Long targetId, int delta) {
        switch (targetType) {
            case "question" -> questionRepo.bumpVoteScore(targetId, delta);
            case "answer" -> answerRepo.bumpVoteScore(targetId, delta);
            case "tip" -> tipRepo.bumpVoteScore(targetId, delta);
            default -> bad("Unsupported target type: " + targetType);
        }
        // Re-read the score so the response is exact (the Modifying query
        // doesn't return the new value).
        return switch (targetType) {
            case "question" -> questionRepo.findById(targetId).map(AskQuestion::getVoteScore).orElse(0);
            case "answer" -> answerRepo.findById(targetId).map(AskAnswer::getVoteScore).orElse(0);
            case "tip" -> tipRepo.findById(targetId).map(AskTip::getVoteScore).orElse(0);
            default -> 0;
        };
    }

    // =================================================================
    // Bookmarks
    // =================================================================

    /** Toggle a bookmark; returns true if now bookmarked, false if just removed. */
    @Transactional
    public boolean toggleBookmark(String userEmail, String targetType, String targetKey) {
        String tt = normalizeTargetType(targetType, /*allowGuide=*/ true);
        String user = normalize(userEmail);
        if (isBlank(targetKey)) bad("targetKey is required");

        Optional<AskBookmark> existing = bookmarkRepo.findByUserEmailAndTargetTypeAndTargetKey(user, tt, targetKey);
        if (existing.isPresent()) {
            bookmarkRepo.delete(existing.get());
            return false;
        }
        AskBookmark b = new AskBookmark();
        b.setUserEmail(user);
        b.setTargetType(tt);
        b.setTargetKey(targetKey.trim());
        bookmarkRepo.save(b);
        return true;
    }

    public List<AskBookmark> listBookmarks(String userEmail) {
        return bookmarkRepo.findByUserEmailOrderByCreatedAtDesc(normalize(userEmail));
    }

    // =================================================================
    // Search (unified across questions + tips; guides handled by FE
    // hardcoded list since they're not yet DB records)
    // =================================================================

    public List<AskSearchHitDto> search(String q, String viewerEmail, Set<String> activeHazards) {
        if (isBlank(q)) return List.of();
        String like = "%" + q.toLowerCase(Locale.ROOT).trim() + "%";
        var pageable = PageRequest.of(0, SEARCH_CAP);

        List<AskQuestion> qs = questionRepo.searchByTokens(like, pageable);
        List<AskTip> ts = tipRepo.searchByTokens(like, pageable);

        Set<String> hazards = activeHazards == null ? Set.of() : activeHazards;

        List<AskSearchHitDto> hits = new ArrayList<>(qs.size() + ts.size());
        for (AskQuestion item : qs) hits.add(searchHit(item, hazards));
        for (AskTip item : ts) hits.add(searchHit(item, hazards));

        // Hot-score sort: hazard-matched first, then hot DESC, then recency DESC.
        hits.sort(SEARCH_HIT_COMPARATOR);

        // Server-fold author profiles in one batch round trip.
        return foldSearchAuthors(hits);
    }

    // =================================================================
    // Internal — DTO conversion + ranking
    // =================================================================

    private List<AskQuestionDto> rankAndDtoQuestions(List<AskQuestion> rows,
                                                    String viewerEmail,
                                                    Set<String> activeHazards,
                                                    boolean includeAnswers) {
        if (rows == null || rows.isEmpty()) return List.of();
        Set<String> hazards = activeHazards == null ? Set.of() : activeHazards;

        // Sort here so the FE doesn't re-sort.
        rows = new ArrayList<>(rows);
        rows.sort(questionComparator(hazards));

        // Batched author + viewer-vote + viewer-bookmark hydration.
        Map<String, UserInfo> authors = batchAuthors(
                rows.stream().map(AskQuestion::getAuthorEmail).toList());
        Map<Long, Integer> myVotes = batchVotes("question",
                rows.stream().map(AskQuestion::getId).toList(), viewerEmail);
        Map<String, Boolean> myBookmarks = batchBookmarks("question",
                rows.stream().map(q -> String.valueOf(q.getId())).toList(), viewerEmail);

        List<AskQuestionDto> out = new ArrayList<>(rows.size());
        for (AskQuestion q : rows) {
            AskQuestionDto d = toDto(q, viewerEmail, hazards, authors, myVotes, myBookmarks);
            if (includeAnswers) d.setAnswers(loadAnswersFor(q, viewerEmail));
            out.add(d);
        }
        return out;
    }

    private List<AskTipDto> rankAndDtoTips(List<AskTip> rows, String viewerEmail, Set<String> activeHazards) {
        if (rows == null || rows.isEmpty()) return List.of();
        Set<String> hazards = activeHazards == null ? Set.of() : activeHazards;

        rows = new ArrayList<>(rows);
        rows.sort(tipComparator(hazards));

        Map<String, UserInfo> authors = batchAuthors(
                rows.stream().map(AskTip::getAuthorEmail).toList());
        Map<Long, Integer> myVotes = batchVotes("tip",
                rows.stream().map(AskTip::getId).toList(), viewerEmail);
        Map<String, Boolean> myBookmarks = batchBookmarks("tip",
                rows.stream().map(t -> String.valueOf(t.getId())).toList(), viewerEmail);

        List<AskTipDto> out = new ArrayList<>(rows.size());
        for (AskTip t : rows) {
            out.add(toDto(t, viewerEmail, hazards, authors, myVotes, myBookmarks));
        }
        return out;
    }

    private List<AskAnswerDto> loadAnswersFor(AskQuestion q, String viewerEmail) {
        List<AskAnswer> answers = answerRepo.findByQuestionIdOrderByVoteScoreDescIdAsc(q.getId());
        Map<String, UserInfo> authors = batchAuthors(answers.stream().map(AskAnswer::getAuthorEmail).toList());
        Map<Long, Integer> myVotes = batchVotes("answer",
                answers.stream().map(AskAnswer::getId).toList(), viewerEmail);
        // Accepted answer first, then by vote score (already sorted that way; lift accepted).
        List<AskAnswerDto> out = new ArrayList<>(answers.size());
        AskAnswerDto accepted = null;
        for (AskAnswer a : answers) {
            AskAnswerDto d = toAnswerDto(a, viewerEmail, q.getAcceptedAnswerId(), authors, myVotes);
            if (d.isAccepted()) accepted = d; else out.add(d);
        }
        if (accepted != null) out.add(0, accepted);
        return out;
    }

    private AskQuestionDto toDto(AskQuestion q, String viewerEmail, Set<String> hazards) {
        return toDto(q, viewerEmail, hazards,
                batchAuthors(List.of(q.getAuthorEmail())),
                batchVotes("question", List.of(q.getId()), viewerEmail),
                batchBookmarks("question", List.of(String.valueOf(q.getId())), viewerEmail));
    }

    private AskQuestionDto toDto(AskQuestion q,
                                 String viewerEmail,
                                 Set<String> hazards,
                                 Map<String, UserInfo> authors,
                                 Map<Long, Integer> myVotes,
                                 Map<String, Boolean> myBookmarks) {
        AskQuestionDto d = new AskQuestionDto();
        d.setId(q.getId());
        d.setAuthorEmail(q.getAuthorEmail());
        applyAuthor(d, authors.get(normalize(q.getAuthorEmail())),
                AskQuestionDto::setAuthorFirstName,
                AskQuestionDto::setAuthorLastName,
                AskQuestionDto::setAuthorProfileImageURL);
        d.setTitle(q.getTitle());
        d.setBody(q.getBody());
        d.setTags(q.getTags());
        d.setHazardTags(q.getHazardTags());
        d.setLatitude(q.getLatitude());
        d.setLongitude(q.getLongitude());
        d.setZipBucket(q.getZipBucket());
        d.setPlaceLabel(q.getPlaceLabel());
        d.setVoteScore(q.getVoteScore());
        d.setViewCount(q.getViewCount());
        d.setAnswerCount(q.getAnswerCount());
        d.setAcceptedAnswerId(q.getAcceptedAnswerId());
        d.setHasAcceptedAnswer(q.getAcceptedAnswerId() != null);
        d.setCreatedAt(q.getCreatedAt());
        d.setUpdatedAt(q.getUpdatedAt());
        d.setEditedAt(q.getEditedAt());
        d.setHazardMatched(intersects(q.getHazardTags(), hazards));
        if (viewerEmail != null) {
            d.setViewerVote(myVotes.getOrDefault(q.getId(), 0));
            d.setViewerBookmarked(myBookmarks.getOrDefault(String.valueOf(q.getId()), false));
            d.setViewerIsAuthor(equalsIgnoreCase(viewerEmail, q.getAuthorEmail()));
        }
        return d;
    }

    private AskTipDto toDto(AskTip t, String viewerEmail, Set<String> hazards) {
        return toDto(t, viewerEmail, hazards,
                batchAuthors(List.of(t.getAuthorEmail())),
                batchVotes("tip", List.of(t.getId()), viewerEmail),
                batchBookmarks("tip", List.of(String.valueOf(t.getId())), viewerEmail));
    }

    private AskTipDto toDto(AskTip t,
                            String viewerEmail,
                            Set<String> hazards,
                            Map<String, UserInfo> authors,
                            Map<Long, Integer> myVotes,
                            Map<String, Boolean> myBookmarks) {
        AskTipDto d = new AskTipDto();
        d.setId(t.getId());
        d.setAuthorEmail(t.getAuthorEmail());
        applyAuthor(d, authors.get(normalize(t.getAuthorEmail())),
                AskTipDto::setAuthorFirstName,
                AskTipDto::setAuthorLastName,
                AskTipDto::setAuthorProfileImageURL);
        d.setTitle(t.getTitle());
        d.setBody(t.getBody());
        d.setCoverImageKey(t.getCoverImageKey());
        d.setImageKeys(t.getImageKeys());
        d.setTags(t.getTags());
        d.setHazardTags(t.getHazardTags());
        d.setLatitude(t.getLatitude());
        d.setLongitude(t.getLongitude());
        d.setZipBucket(t.getZipBucket());
        d.setPlaceLabel(t.getPlaceLabel());
        d.setVoteScore(t.getVoteScore());
        d.setViewCount(t.getViewCount());
        d.setCreatedAt(t.getCreatedAt());
        d.setUpdatedAt(t.getUpdatedAt());
        d.setEditedAt(t.getEditedAt());
        d.setHazardMatched(intersects(t.getHazardTags(), hazards));
        if (viewerEmail != null) {
            d.setViewerVote(myVotes.getOrDefault(t.getId(), 0));
            d.setViewerBookmarked(myBookmarks.getOrDefault(String.valueOf(t.getId()), false));
            d.setViewerIsAuthor(equalsIgnoreCase(viewerEmail, t.getAuthorEmail()));
        }
        return d;
    }

    private AskAnswerDto toAnswerDto(AskAnswer a, String viewerEmail, Long acceptedAnswerId) {
        return toAnswerDto(a, viewerEmail, acceptedAnswerId,
                batchAuthors(List.of(a.getAuthorEmail())),
                batchVotes("answer", List.of(a.getId()), viewerEmail));
    }

    private AskAnswerDto toAnswerDto(AskAnswer a,
                                     String viewerEmail,
                                     Long acceptedAnswerId,
                                     Map<String, UserInfo> authors,
                                     Map<Long, Integer> myVotes) {
        AskAnswerDto d = new AskAnswerDto();
        d.setId(a.getId());
        d.setQuestionId(a.getQuestionId());
        d.setAuthorEmail(a.getAuthorEmail());
        applyAuthor(d, authors.get(normalize(a.getAuthorEmail())),
                AskAnswerDto::setAuthorFirstName,
                AskAnswerDto::setAuthorLastName,
                AskAnswerDto::setAuthorProfileImageURL);
        d.setBody(a.getBody());
        d.setVoteScore(a.getVoteScore());
        d.setCreatedAt(a.getCreatedAt());
        d.setUpdatedAt(a.getUpdatedAt());
        d.setEditedAt(a.getEditedAt());
        d.setAccepted(Objects.equals(acceptedAnswerId, a.getId()));
        if (viewerEmail != null) {
            d.setViewerVote(myVotes.getOrDefault(a.getId(), 0));
            d.setViewerIsAuthor(equalsIgnoreCase(viewerEmail, a.getAuthorEmail()));
        }
        return d;
    }

    private AskSearchHitDto searchHit(AskQuestion q, Set<String> hazards) {
        AskSearchHitDto h = new AskSearchHitDto();
        h.setKind("question");
        h.setKey(String.valueOf(q.getId()));
        h.setTitle(q.getTitle());
        h.setSnippet(snippet(q.getBody()));
        h.setTags(q.getTags());
        h.setHazardTags(q.getHazardTags());
        h.setVoteScore(q.getVoteScore());
        h.setCreatedAt(q.getCreatedAt());
        h.setHazardMatched(intersects(q.getHazardTags(), hazards));
        h.setHotScore(hotScore(q.getVoteScore(), q.getCreatedAt()));
        h.setAuthorEmail(q.getAuthorEmail());
        h.setHref("/ask/q/" + q.getId());
        return h;
    }

    private AskSearchHitDto searchHit(AskTip t, Set<String> hazards) {
        AskSearchHitDto h = new AskSearchHitDto();
        h.setKind("tip");
        h.setKey(String.valueOf(t.getId()));
        h.setTitle(t.getTitle());
        h.setSnippet(snippet(t.getBody()));
        h.setTags(t.getTags());
        h.setHazardTags(t.getHazardTags());
        h.setVoteScore(t.getVoteScore());
        h.setCreatedAt(t.getCreatedAt());
        h.setHazardMatched(intersects(t.getHazardTags(), hazards));
        h.setHotScore(hotScore(t.getVoteScore(), t.getCreatedAt()));
        h.setAuthorEmail(t.getAuthorEmail());
        h.setHref("/ask/tips/" + t.getId());
        return h;
    }

    private List<AskSearchHitDto> foldSearchAuthors(List<AskSearchHitDto> hits) {
        if (hits.isEmpty()) return hits;
        List<String> emails = hits.stream()
                .map(AskSearchHitDto::getAuthorEmail)
                .filter(Objects::nonNull)
                .map(this::normalize)
                .distinct()
                .toList();
        if (emails.isEmpty()) return hits;
        Map<String, UserInfo> byEmail = batchAuthors(emails);
        for (AskSearchHitDto h : hits) {
            UserInfo u = byEmail.get(normalize(h.getAuthorEmail()));
            if (u != null) {
                h.setAuthorFirstName(u.getUserFirstName());
                h.setAuthorLastName(u.getUserLastName());
                h.setAuthorProfileImageURL(u.getProfileImageURL());
            }
        }
        return hits;
    }

    // =================================================================
    // Comparators + hot score
    // =================================================================

    private static Comparator<AskQuestion> questionComparator(Set<String> hazards) {
        return Comparator
                .comparing((AskQuestion q) -> intersects(q.getHazardTags(), hazards) ? 0 : 1)
                .thenComparing((q) -> -hotScore(q.getVoteScore(), q.getCreatedAt()))
                .thenComparing(AskQuestion::getId, Comparator.reverseOrder());
    }

    private static Comparator<AskTip> tipComparator(Set<String> hazards) {
        return Comparator
                .comparing((AskTip t) -> intersects(t.getHazardTags(), hazards) ? 0 : 1)
                .thenComparing((t) -> -hotScore(t.getVoteScore(), t.getCreatedAt()))
                .thenComparing(AskTip::getId, Comparator.reverseOrder());
    }

    private static final Comparator<AskSearchHitDto> SEARCH_HIT_COMPARATOR = Comparator
            .comparing((AskSearchHitDto h) -> h.isHazardMatched() ? 0 : 1)
            .thenComparing(Comparator.comparingDouble(AskSearchHitDto::getHotScore).reversed())
            .thenComparing(Comparator.comparing(AskSearchHitDto::getCreatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())));

    /**
     * Hot score: log10 of vote score (so 100 votes ≠ 10× weight of 10 votes)
     * plus a 14-day glide-path recency bonus. See class doc for the exact
     * formula.
     */
    static double hotScore(int voteScore, Instant createdAt) {
        double base = Math.log10(Math.max(voteScore + 1, 1));
        double daysOld = createdAt == null
                ? 0.0
                : Duration.between(createdAt, Instant.now()).toMinutes() / (60.0 * 24);
        double recency = Math.max(0.0, 14.0 - daysOld) * 0.15;
        return base + recency;
    }

    // =================================================================
    // Hydration helpers
    // =================================================================

    private Map<String, UserInfo> batchAuthors(List<String> emails) {
        if (emails == null || emails.isEmpty()) return Map.of();
        List<String> distinctNorm = emails.stream()
                .filter(Objects::nonNull)
                .map(this::normalize)
                .distinct()
                .toList();
        if (distinctNorm.isEmpty()) return Map.of();
        return userInfoRepo.findByUserEmailIn(distinctNorm).stream()
                .filter(u -> u.getUserEmail() != null)
                .collect(Collectors.toMap(
                        u -> normalize(u.getUserEmail()),
                        Function.identity(),
                        (a, b) -> a));
    }

    private Map<Long, Integer> batchVotes(String targetType, List<Long> ids, String voterEmail) {
        if (voterEmail == null || ids == null || ids.isEmpty()) return Map.of();
        List<Long> nonNull = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (nonNull.isEmpty()) return Map.of();
        return voteRepo.findVoterVotesIn(normalize(voterEmail), targetType, nonNull).stream()
                .collect(Collectors.toMap(
                        AskVote::getTargetId,
                        AskVote::getValue,
                        (a, b) -> a));
    }

    private Map<String, Boolean> batchBookmarks(String targetType, List<String> keys, String userEmail) {
        if (userEmail == null || keys == null || keys.isEmpty()) return Map.of();
        List<String> nonNull = keys.stream().filter(Objects::nonNull).distinct().toList();
        if (nonNull.isEmpty()) return Map.of();
        Map<String, Boolean> out = new HashMap<>();
        for (AskBookmark b : bookmarkRepo.findUserBookmarksIn(normalize(userEmail), targetType, nonNull)) {
            out.put(b.getTargetKey(), true);
        }
        return out;
    }

    /** Best-effort active-hazard set lookup for a user. Empty when unknown. */
    public Set<String> activeHazardsFor(String userEmail) {
        // v1: rely on the FE to pass active hazards via header — leaving the
        // server-derived path empty means we don't pin without explicit signal.
        // When MeContext exposes the active alert vocabulary BE-side, plug
        // that read here.
        return Set.of();
    }

    public static Set<String> parseHazardHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) return Set.of();
        return Arrays.stream(headerValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    // =================================================================
    // Utils
    // =================================================================

    private void enrichGeo(Double lat, Double lng,
                           java.util.function.Consumer<String> setZip,
                           java.util.function.Consumer<String> setLabel) {
        if (lat == null || lng == null) return;
        try {
            NominatimGeocodeService.Place p = geocode.reverse(lat, lng);
            if (p != null) {
                setZip.accept(p.zipBucket());
                setLabel.accept(p.shortLabel());
            }
        } catch (Exception e) {
            log.debug("Ask geo enrichment failed: {}", e.getMessage());
        }
    }

    private static <T> void applyAuthor(T dto, UserInfo u,
                                        java.util.function.BiConsumer<T, String> setFirst,
                                        java.util.function.BiConsumer<T, String> setLast,
                                        java.util.function.BiConsumer<T, String> setImg) {
        if (u == null) return;
        setFirst.accept(dto, u.getUserFirstName());
        setLast.accept(dto, u.getUserLastName());
        setImg.accept(dto, u.getProfileImageURL());
    }

    private static String snippet(String body) {
        if (body == null) return "";
        String trimmed = body.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return false;
        for (String x : a) {
            if (b.contains(x.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static Instant sinceFor(String window) {
        if (window == null) return Instant.EPOCH;
        return switch (window.toLowerCase(Locale.ROOT)) {
            case "day" -> Instant.now().minus(Duration.ofDays(1));
            case "week" -> Instant.now().minus(Duration.ofDays(7));
            case "month" -> Instant.now().minus(Duration.ofDays(30));
            default -> Instant.EPOCH;
        };
    }

    private void ensureAuthor(String entityAuthor, String actor) {
        if (entityAuthor == null || actor == null
                || !entityAuthor.trim().equalsIgnoreCase(actor.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the original author");
        }
    }

    private static int clamp(int requested) {
        if (requested <= 0) return DEFAULT_PAGE;
        return Math.min(requested, MAX_PAGE);
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void bad(String msg) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static String normalizeTargetType(String raw, boolean allowGuide) {
        if (raw == null) bad("targetType is required");
        String tt = raw.trim().toLowerCase(Locale.ROOT);
        if (tt.equals("question") || tt.equals("answer") || tt.equals("tip")) return tt;
        if (allowGuide && tt.equals("guide")) return tt;
        bad("Unsupported targetType: " + raw);
        return tt;
    }

    private static Set<String> normalizeTagSet(Collection<String> in) {
        if (in == null) return new HashSet<>();
        Set<String> out = new HashSet<>();
        for (String t : in) {
            if (t == null) continue;
            String norm = t.trim().toLowerCase(Locale.ROOT);
            if (!norm.isEmpty()) out.add(norm);
        }
        return out;
    }

    private static final Set<String> ALLOWED_HAZARDS = Set.of(
            "hurricane", "wildfire", "earthquake", "blizzard", "flood", "tornado", "heat", "smoke");

    private static Set<String> normalizeHazardSet(Collection<String> in) {
        if (in == null) return new HashSet<>();
        Set<String> out = new HashSet<>();
        for (String t : in) {
            if (t == null) continue;
            String norm = t.trim().toLowerCase(Locale.ROOT);
            if (ALLOWED_HAZARDS.contains(norm)) out.add(norm);
        }
        return out;
    }
}
