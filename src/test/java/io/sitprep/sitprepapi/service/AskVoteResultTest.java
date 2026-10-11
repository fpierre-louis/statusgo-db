package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AskQuestion;
import io.sitprep.sitprepapi.domain.AskVote;
import io.sitprep.sitprepapi.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The vote response must report the vote AS PERSISTED after the toggle, not
 * the value the client sent. Tapping the same value twice clears the vote,
 * so the second response says {@code viewerVote: 0}, the same "no vote"
 * value the read DTOs give a signed-in viewer.
 *
 * The repos are mocks backed by one vote row and one score, so a sequence of
 * taps walks through real state: each call sees what the previous one left.
 */
class AskVoteResultTest {

    private static final String VOTER = "voter@example.com";
    private static final long QID = 7L;

    private AskQuestionRepo questionRepo;
    private AskVoteRepo voteRepo;
    private AskService service;

    /** The single (question 7, voter) row, or null when there is no vote. */
    private final AtomicReference<AskVote> row = new AtomicReference<>();
    /** The question's denormalized vote_score. */
    private final AtomicInteger score = new AtomicInteger();

    @BeforeEach
    void setUp() {
        questionRepo = mock(AskQuestionRepo.class);
        voteRepo = mock(AskVoteRepo.class);

        when(voteRepo.findByTargetTypeAndTargetIdAndVoterEmail(eq("question"), eq(QID), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(row.get()));
        when(voteRepo.save(any(AskVote.class))).thenAnswer(inv -> {
            AskVote v = inv.getArgument(0);
            row.set(v);
            return v;
        });
        doAnswer(inv -> { row.set(null); return null; }).when(voteRepo).delete(any(AskVote.class));

        when(questionRepo.bumpVoteScore(eq(QID), anyInt()))
                .thenAnswer(inv -> { score.addAndGet(inv.getArgument(1)); return 1; });
        when(questionRepo.findById(QID)).thenAnswer(inv -> {
            AskQuestion q = new AskQuestion();
            q.setId(QID);
            q.setVoteScore(score.get());
            return Optional.of(q);
        });

        service = askService();
    }

    @Test
    void noneToUpReportsTheUpvote() {
        AskService.VoteResult r = service.vote("question", QID, VOTER, 1);

        assertEquals(1, r.viewerVote());
        assertEquals(1, r.voteScore());
    }

    @Test
    void upThenUpAgainClearsTheVoteAndSaysSo() {
        service.vote("question", QID, VOTER, 1);
        AskService.VoteResult r = service.vote("question", QID, VOTER, 1);

        // The bug: this used to echo the requested 1.
        assertEquals(0, r.viewerVote());
        assertEquals(0, r.voteScore());
        assertEquals(null, row.get(), "the vote row is deleted, not set to 0");
    }

    @Test
    void upThenDownFlipsTheVote() {
        service.vote("question", QID, VOTER, 1);
        AskService.VoteResult r = service.vote("question", QID, VOTER, -1);

        assertEquals(-1, r.viewerVote());
        assertEquals(-1, r.voteScore());
        assertEquals(-1, row.get().getValue());
    }

    @Test
    void theFullSequenceTracksScoreAndVoteAtEveryStep() {
        score.set(4); // other people's votes already on the question

        AskService.VoteResult up = service.vote("question", QID, VOTER, 1);
        assertEquals(1, up.viewerVote());
        assertEquals(5, up.voteScore());

        AskService.VoteResult cleared = service.vote("question", QID, VOTER, 1);
        assertEquals(0, cleared.viewerVote());
        assertEquals(4, cleared.voteScore());

        AskService.VoteResult upAgain = service.vote("question", QID, VOTER, 1);
        AskService.VoteResult down = service.vote("question", QID, VOTER, -1);
        assertEquals(1, upAgain.viewerVote());
        assertEquals(-1, down.viewerVote());
        assertEquals(3, down.voteScore());
    }

    /** Same reflective construction as AskDeleteCascadeTest. */
    private AskService askService() {
        for (var ctor : AskService.class.getDeclaredConstructors()) {
            Class<?>[] types = ctor.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == AskQuestionRepo.class) args[i] = questionRepo;
                else if (types[i] == AskVoteRepo.class) args[i] = voteRepo;
                else args[i] = types[i].isPrimitive() ? 0 : mock(types[i]);
            }
            try {
                ctor.setAccessible(true);
                return (AskService) ctor.newInstance(args);
            } catch (Exception ignored) {
                // try the next constructor
            }
        }
        throw new IllegalStateException("No usable AskService constructor");
    }
}
