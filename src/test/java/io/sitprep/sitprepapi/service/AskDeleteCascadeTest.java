package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AskAnswer;
import io.sitprep.sitprepapi.domain.AskQuestion;
import io.sitprep.sitprepapi.domain.AskTip;
import io.sitprep.sitprepapi.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Deleting anything in the Ask family must take its dependents with it.
 *
 * None of these references are JPA relations — {@code AskAnswer.questionId} is
 * a plain Long and votes/bookmarks are polymorphic (targetType + target id) —
 * so there is no cascade and no foreign key to fall back on. Every cleanup is
 * hand-written, which means the only thing stopping it from silently rotting
 * is a test that asserts each call happens.
 *
 * Mockito rather than a Spring slice on purpose: what can regress here is a
 * MISSING CALL, and verifying calls is exactly what this level is for.
 */
class AskDeleteCascadeTest {

    private AskQuestionRepo questionRepo;
    private AskAnswerRepo answerRepo;
    private AskTipRepo tipRepo;
    private AskVoteRepo voteRepo;
    private AskBookmarkRepo bookmarkRepo;
    private AskService service;

    private static final String AUTHOR = "author@example.com";

    @BeforeEach
    void setUp() {
        questionRepo = mock(AskQuestionRepo.class);
        answerRepo = mock(AskAnswerRepo.class);
        tipRepo = mock(AskTipRepo.class);
        voteRepo = mock(AskVoteRepo.class);
        bookmarkRepo = mock(AskBookmarkRepo.class);
        service = askService();
    }

    @Test
    void deletingAQuestionTakesItsAnswersVotesAndBookmarks() {
        AskQuestion q = new AskQuestion();
        q.setId(7L);
        q.setAuthorEmail(AUTHOR);
        when(questionRepo.findById(7L)).thenReturn(Optional.of(q));
        when(answerRepo.findIdsByQuestionId(7L)).thenReturn(List.of(11L, 12L));

        service.deleteQuestion(7L, AUTHOR);

        // The answers' own votes and bookmarks — one level down, and the part
        // most likely to be forgotten.
        verify(voteRepo).deleteAllForTargets("answer", List.of(11L, 12L));
        verify(bookmarkRepo).deleteAllForTargets("answer", List.of("11", "12"));
        verify(answerRepo).deleteByQuestionId(7L);

        // The question's own.
        verify(voteRepo).deleteAllForTarget("question", 7L);
        verify(bookmarkRepo).deleteAllForTarget("question", "7");
        verify(questionRepo).delete(q);
    }

    @Test
    void aQuestionWithNoAnswersSkipsTheAnswerCleanupButStillClearsItsOwn() {
        AskQuestion q = new AskQuestion();
        q.setId(8L);
        q.setAuthorEmail(AUTHOR);
        when(questionRepo.findById(8L)).thenReturn(Optional.of(q));
        when(answerRepo.findIdsByQuestionId(8L)).thenReturn(List.of());

        service.deleteQuestion(8L, AUTHOR);

        // Guard against issuing `IN ()` — an empty IN-list is a syntax error
        // on some databases and a full-table match on others.
        verify(voteRepo, never()).deleteAllForTargets(anyString(), anyCollection());
        verify(bookmarkRepo, never()).deleteAllForTargets(anyString(), anyCollection());
        verify(answerRepo, never()).deleteByQuestionId(anyLong());

        verify(voteRepo).deleteAllForTarget("question", 8L);
        verify(bookmarkRepo).deleteAllForTarget("question", "8");
    }

    @Test
    void deletingAnAnswerTakesItsOwnVotesAndBookmarks() {
        AskAnswer a = new AskAnswer();
        a.setId(21L);
        a.setQuestionId(7L);
        a.setAuthorEmail(AUTHOR);
        when(answerRepo.findById(21L)).thenReturn(Optional.of(a));
        when(questionRepo.findById(7L)).thenReturn(Optional.empty());

        service.deleteAnswer(21L, AUTHOR);

        verify(voteRepo).deleteAllForTarget("answer", 21L);
        verify(bookmarkRepo).deleteAllForTarget("answer", "21");
        verify(answerRepo).delete(a);
    }

    @Test
    void deletingATipTakesItsVotesAndBookmarks() {
        AskTip t = new AskTip();
        t.setId(31L);
        t.setAuthorEmail(AUTHOR);
        when(tipRepo.findById(31L)).thenReturn(Optional.of(t));

        service.deleteTip(31L, AUTHOR);

        verify(voteRepo).deleteAllForTarget("tip", 31L);
        verify(bookmarkRepo).deleteAllForTarget("tip", "31");
        verify(tipRepo).delete(t);
    }

    /**
     * AskService takes a wide constructor; only the repos these paths touch
     * need to be real mocks. Reflection keeps the test from breaking every
     * time an unrelated collaborator is added to the signature.
     */
    private AskService askService() {
        for (var ctor : AskService.class.getDeclaredConstructors()) {
            Class<?>[] types = ctor.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == AskQuestionRepo.class) args[i] = questionRepo;
                else if (types[i] == AskAnswerRepo.class) args[i] = answerRepo;
                else if (types[i] == AskTipRepo.class) args[i] = tipRepo;
                else if (types[i] == AskVoteRepo.class) args[i] = voteRepo;
                else if (types[i] == AskBookmarkRepo.class) args[i] = bookmarkRepo;
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
