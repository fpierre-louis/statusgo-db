package io.sitprep.sitprepapi.gamification;

import io.sitprep.sitprepapi.domain.AskAnswer;
import io.sitprep.sitprepapi.domain.AskQuestion;
import io.sitprep.sitprepapi.domain.AskVote;
import io.sitprep.sitprepapi.domain.HazardVote;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.PostConfirm;
import io.sitprep.sitprepapi.repo.AskAnswerRepo;
import io.sitprep.sitprepapi.repo.AskQuestionRepo;
import io.sitprep.sitprepapi.repo.AskTipRepo;
import io.sitprep.sitprepapi.repo.AskVoteRepo;
import io.sitprep.sitprepapi.repo.HazardReportRepo;
import io.sitprep.sitprepapi.repo.HazardVoteRepo;
import io.sitprep.sitprepapi.repo.MapConfirmationRepo;
import io.sitprep.sitprepapi.repo.PostConfirmRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.sitprep.sitprepapi.gamification.TokenEventType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T4: individual criteria re-read the record and refuse self-credit. */
class UserTokenCriteriaTest {

    private static final String ME = "ana@x.com";
    private static final String BO = "bo@x.com";

    private PostRepo posts;
    private AskQuestionRepo questions;
    private AskAnswerRepo answers;
    private AskVoteRepo votes;
    private HazardVoteRepo hazardVotes;
    private PostConfirmRepo postConfirms;
    private UserTokenCriteria criteria;

    @BeforeEach
    void setUp() {
        posts = mock(PostRepo.class);
        questions = mock(AskQuestionRepo.class);
        answers = mock(AskAnswerRepo.class);
        votes = mock(AskVoteRepo.class);
        hazardVotes = mock(HazardVoteRepo.class);
        postConfirms = mock(PostConfirmRepo.class);
        criteria = new UserTokenCriteria(posts, questions, answers, mock(AskTipRepo.class), votes,
                mock(HazardReportRepo.class), hazardVotes, mock(MapConfirmationRepo.class), postConfirms);
    }

    private Post post(long id, String author, String kind) {
        Post p = new Post();
        p.setId(id);
        p.setRequesterEmail(author);
        p.setKind(kind);
        when(posts.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    private AskAnswer answer(long id, long questionId, String author, String asker, Long accepted) {
        AskAnswer a = new AskAnswer();
        a.setId(id);
        a.setQuestionId(questionId);
        a.setAuthorEmail(author);
        AskQuestion q = new AskQuestion();
        q.setId(questionId);
        q.setAuthorEmail(asker);
        q.setAcceptedAnswerId(accepted);
        when(answers.findById(id)).thenReturn(Optional.of(a));
        when(questions.findById(questionId)).thenReturn(Optional.of(q));
        return a;
    }

    @Test
    void aNeighborPostEarnsFirstSignalButAHazardOrGroupPostDoesNot() {
        post(1, ME, "post");
        assertThat(criteria.candidates(TokenEvent.user(COMMUNITY_POST_CREATED, ME, 1L)))
                .extracting(UserTokenCriteria.Candidate::key).containsExactly(TokenKey.FIRST_NEIGHBOR_SIGNAL);

        post(2, ME, "tip");
        assertThat(criteria.candidates(TokenEvent.user(COMMUNITY_POST_CREATED, ME, 2L)))
                .extracting(UserTokenCriteria.Candidate::key)
                .containsExactly(TokenKey.FIRST_NEIGHBOR_SIGNAL, TokenKey.PREP_TIP_SHARER);

        post(3, ME, "hazard");
        assertThat(criteria.candidates(TokenEvent.user(COMMUNITY_POST_CREATED, ME, 3L))).isEmpty();

        post(4, ME, "post").setGroupId("g-1");
        assertThat(criteria.candidates(TokenEvent.user(COMMUNITY_POST_CREATED, ME, 4L))).isEmpty();
    }

    @Test
    void answeringOrAcceptingOnYourOwnQuestionEarnsNothing() {
        answer(10, 100, ME, ME, 10L);
        assertThat(criteria.candidates(TokenEvent.user(ASK_ANSWER_CREATED, ME, 10L))).isEmpty();
        assertThat(criteria.candidates(TokenEvent.user(ASK_ANSWER_ACCEPTED, ME, 10L))).isEmpty();
    }

    @Test
    void anAcceptedAnswerCreditsItsAuthorNotTheAsker() {
        answer(11, 101, BO, ME, 11L);
        assertThat(criteria.candidates(TokenEvent.user(ASK_ANSWER_ACCEPTED, ME, 11L)))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.email()).isEqualTo(BO);
                    assertThat(c.key()).isEqualTo(TokenKey.TRUSTED_ANSWER);
                });
        // Un-accepted before the evaluator ran: nothing.
        answer(12, 102, BO, ME, null);
        assertThat(criteria.candidates(TokenEvent.user(ASK_ANSWER_ACCEPTED, ME, 12L))).isEmpty();
    }

    @Test
    void votingOnYourOwnHazardIsNotGroundTruth() {
        post(20, ME, "hazard");
        when(hazardVotes.findByTaskIdAndUserEmail(eq(20L), anyString())).thenReturn(Optional.of(new HazardVote()));
        assertThat(criteria.candidates(TokenEvent.user(HAZARD_VOTED, ME, 20L))).isEmpty();
        assertThat(criteria.candidates(TokenEvent.user(HAZARD_VOTED, BO, 20L)))
                .extracting(UserTokenCriteria.Candidate::key).containsExactly(TokenKey.GROUND_TRUTH);
    }

    @Test
    void helpingHandNeedsSomeoneElse() {
        post(30, ME, "post");
        when(postConfirms.findByPostIdAndUserEmailIgnoreCase(eq(30L), anyString())).thenReturn(Optional.of(new PostConfirm()));
        assertThat(criteria.candidates(TokenEvent.user(POST_CONFIRMED, ME, 30L))).isEmpty();
        assertThat(criteria.candidates(TokenEvent.user(POST_CONFIRMED, BO, 30L)))
                .singleElement().satisfies(c -> assertThat(c.email()).isEqualTo(ME));

        AskVote up = new AskVote();
        up.setValue(1);
        answer(40, 400, ME, BO, null);
        when(votes.findByTargetTypeAndTargetIdAndVoterEmail(eq("answer"), eq(40L), anyString())).thenReturn(Optional.of(up));
        assertThat(criteria.candidates(TokenEvent.user(ASK_VOTE_CAST, ME, "answer:40"))).isEmpty();
        assertThat(criteria.candidates(TokenEvent.user(ASK_VOTE_CAST, BO, "answer:40")))
                .extracting(UserTokenCriteria.Candidate::key).containsExactly(TokenKey.HELPING_HAND);
    }
}
