package io.sitprep.sitprepapi.gamification;

import io.sitprep.sitprepapi.constant.PostKind;
import io.sitprep.sitprepapi.domain.AskAnswer;
import io.sitprep.sitprepapi.domain.AskQuestion;
import io.sitprep.sitprepapi.domain.AskTip;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.repo.AskAnswerRepo;
import io.sitprep.sitprepapi.repo.AskQuestionRepo;
import io.sitprep.sitprepapi.repo.AskTipRepo;
import io.sitprep.sitprepapi.repo.AskVoteRepo;
import io.sitprep.sitprepapi.repo.HazardReportRepo;
import io.sitprep.sitprepapi.repo.HazardVoteRepo;
import io.sitprep.sitprepapi.repo.MapConfirmationRepo;
import io.sitprep.sitprepapi.repo.PostConfirmRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Turns an individual event into the awards it earns, after re-reading the
 * record it names. An event is a claim; the record is the proof.
 *
 * <p>Self-credit is excluded everywhere it is possible: answering or accepting
 * on your own question, voting on your own hazard, confirming or up-voting your
 * own contribution. Thanks/❤ is deliberately not a signal (owner, 2026-10-03:
 * it is becoming "like" in another lane).</p>
 */
@Component
public class UserTokenCriteria {

    /** One award an event earns — {@code email} may be someone other than the actor (an accepted answer's author). */
    public record Candidate(String email, TokenKey key, String sourceType, String sourceId, Map<String, Object> metadata) {}

    private final PostRepo posts;
    private final AskQuestionRepo questions;
    private final AskAnswerRepo answers;
    private final AskTipRepo tips;
    private final AskVoteRepo votes;
    private final HazardReportRepo hazardReports;
    private final HazardVoteRepo hazardVotes;
    private final MapConfirmationRepo mapConfirmations;
    private final PostConfirmRepo postConfirms;

    public UserTokenCriteria(PostRepo posts,
                             AskQuestionRepo questions,
                             AskAnswerRepo answers,
                             AskTipRepo tips,
                             AskVoteRepo votes,
                             HazardReportRepo hazardReports,
                             HazardVoteRepo hazardVotes,
                             MapConfirmationRepo mapConfirmations,
                             PostConfirmRepo postConfirms) {
        this.posts = posts;
        this.questions = questions;
        this.answers = answers;
        this.tips = tips;
        this.votes = votes;
        this.hazardReports = hazardReports;
        this.hazardVotes = hazardVotes;
        this.mapConfirmations = mapConfirmations;
        this.postConfirms = postConfirms;
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates(TokenEvent event) {
        List<Candidate> out = new ArrayList<>();
        if (event == null || event.type() == null) return out;
        String actor = normalize(event.actorEmail());
        String src = event.sourceId();
        if (actor == null || src == null) return out;
        String type = event.type().name();

        switch (event.type()) {
            case COMMUNITY_POST_CREATED -> post(src).filter(p -> actor.equals(normalize(p.getRequesterEmail())))
                    .filter(UserTokenCriteria::isNeighborPost)
                    .ifPresent(p -> {
                        out.add(new Candidate(actor, TokenKey.FIRST_NEIGHBOR_SIGNAL, type, src, Map.of()));
                        if (PostKind.TIP.wire().equals(p.getKind())) {
                            out.add(new Candidate(actor, TokenKey.PREP_TIP_SHARER, type, src, Map.of()));
                        }
                    });
            case ASK_QUESTION_CREATED -> id(src).flatMap(questions::findById)
                    .filter(q -> actor.equals(normalize(q.getAuthorEmail())))
                    .ifPresent(q -> out.add(new Candidate(actor, TokenKey.HELPFUL_QUESTION, type, src, Map.of())));
            case ASK_TIP_CREATED -> id(src).flatMap(tips::findById)
                    .filter(t -> actor.equals(normalize(t.getAuthorEmail())))
                    .ifPresent(t -> out.add(new Candidate(actor, TokenKey.PREP_TIP_SHARER, type, src, Map.of())));
            case ASK_ANSWER_CREATED -> id(src).flatMap(answers::findById)
                    .filter(a -> actor.equals(normalize(a.getAuthorEmail())))
                    .filter(a -> !actor.equals(questionAuthor(a)))   // not an answer to your own question
                    .ifPresent(a -> out.add(new Candidate(actor, TokenKey.ANSWERED_THE_CALL, type, src, Map.of())));
            case ASK_ANSWER_ACCEPTED -> id(src).flatMap(answers::findById).ifPresent(a -> {
                AskQuestion q = questions.findById(a.getQuestionId()).orElse(null);
                String author = normalize(a.getAuthorEmail());
                // Still accepted, accepted by the asker, and not the asker's own answer.
                if (q != null && Objects.equals(q.getAcceptedAnswerId(), a.getId())
                        && actor.equals(normalize(q.getAuthorEmail()))
                        && author != null && !author.equals(actor)) {
                    out.add(new Candidate(author, TokenKey.TRUSTED_ANSWER, type, src, Map.of()));
                }
            });
            case ASK_VOTE_CAST -> askVote(actor, src, type, out);
            case HAZARD_REPORTED -> id(src).filter(hazardReports::existsById).flatMap(posts::findById)
                    .filter(p -> actor.equals(normalize(p.getRequesterEmail())))
                    .ifPresent(p -> out.add(new Candidate(actor, TokenKey.LOCAL_HAZARD_REPORTER, type, src, Map.of())));
            case HAZARD_VOTED -> id(src).ifPresent(taskId -> {
                boolean voted = hazardVotes.findByTaskIdAndUserEmail(taskId, actor).isPresent();
                String reporter = posts.findById(taskId).map(p -> normalize(p.getRequesterEmail())).orElse(null);
                if (voted && reporter != null && !reporter.equals(actor)) {
                    out.add(new Candidate(actor, TokenKey.GROUND_TRUTH, type, src, Map.of()));
                }
            });
            case MAP_CONFIRMED -> {
                int colon = src.indexOf(':');
                if (colon > 0 && mapConfirmations.findByTargetTypeAndTargetIdAndUserEmail(
                        src.substring(0, colon), src.substring(colon + 1), actor).isPresent()) {
                    out.add(new Candidate(actor, TokenKey.GROUND_TRUTH, type, src, Map.of()));
                }
            }
            case POST_CONFIRMED -> id(src).ifPresent(postId -> posts.findById(postId)
                    .filter(UserTokenCriteria::isNeighborPost)
                    .ifPresent(p -> {
                        String author = normalize(p.getRequesterEmail());
                        if (author != null && !author.equals(actor)
                                && postConfirms.findByPostIdAndUserEmailIgnoreCase(postId, actor).isPresent()) {
                            out.add(new Candidate(author, TokenKey.HELPING_HAND, type, src, Map.of()));
                        }
                    }));
            default -> { /* household events are not individual */ }
        }
        return out;
    }

    /** An up-vote on someone's answer or tip earns its author Helping Hand. {@code src} = "answer:12" | "tip:5". */
    private void askVote(String actor, String src, String type, List<Candidate> out) {
        int colon = src.indexOf(':');
        if (colon <= 0) return;
        String targetType = src.substring(0, colon);
        Long targetId = id(src.substring(colon + 1)).orElse(null);
        if (targetId == null) return;
        boolean upVoted = votes.findByTargetTypeAndTargetIdAndVoterEmail(targetType, targetId, actor)
                .map(v -> v.getValue() == 1).orElse(false);
        if (!upVoted) return;
        String author = switch (targetType) {
            case "answer" -> answers.findById(targetId).map(AskAnswer::getAuthorEmail).orElse(null);
            case "tip" -> tips.findById(targetId).map(AskTip::getAuthorEmail).orElse(null);
            default -> null;
        };
        author = normalize(author);
        if (author != null && !author.equals(actor)) {
            out.add(new Candidate(author, TokenKey.HELPING_HAND, type, src, Map.of()));
        }
    }

    /**
     * A post to neighbours: community scope (no group), not a personal task, not
     * a hazard (hazards have their own token and route), not sponsored, not posted
     * as a group, and written by a person rather than a feed.
     */
    static boolean isNeighborPost(Post p) {
        if (p == null || p.getGroupId() != null || p.getAuthoredAsGroupId() != null || p.isSponsored()) return false;
        String kind = p.getKind();
        if (kind == null || PostKind.isPersonalScope(kind) || PostKind.HAZARD.wire().equals(kind)) return false;
        String source = p.getSourceKey();
        return source == null || source.isBlank() || "user".equalsIgnoreCase(source);
    }

    private String questionAuthor(AskAnswer a) {
        return a.getQuestionId() == null ? null
                : questions.findById(a.getQuestionId()).map(q -> normalize(q.getAuthorEmail())).orElse(null);
    }

    private java.util.Optional<Post> post(String src) {
        return id(src).flatMap(posts::findById);
    }

    private static java.util.Optional<Long> id(String s) {
        try {
            return java.util.Optional.of(Long.valueOf(s.trim()));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    private static String normalize(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
