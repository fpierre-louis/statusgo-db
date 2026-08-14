package io.sitprep.sitprepapi.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The excerpt's derivation rules.
 *
 * Kept as a pure unit test on {@code AskService.excerptOf} rather than a
 * Spring slice: the four write paths are one-line calls into this function,
 * and what can actually go wrong is the TEXT — an excerpt that overflows the
 * VARCHAR(200) column, or one that reads as garbage because it was cut
 * mid-word or still carries the answer's hard wrapping.
 *
 * The path wiring itself (accept / un-accept / delete-answer / edit-answer)
 * is covered by the fact that all four funnel through one setter; the case
 * worth guarding here is that null in gives null out, because that is how the
 * excerpt gets CLEARED, and a clear that silently no-ops leaves the list
 * quoting a deleted answer.
 */
class AskAcceptedExcerptTest {

    @Test
    void nullAndBlankBodiesClearTheExcerpt() {
        // Null-in-null-out IS the clear path. If this ever returned "" the
        // column would hold an empty string and the FE would render an empty
        // quote rail rather than omitting it.
        assertNull(AskService.excerptOf(null));
        assertNull(AskService.excerptOf("   "));
        assertNull(AskService.excerptOf("\n\n\t "));
    }

    @Test
    void shortBodiesSurviveIntact() {
        String body = "One gallon per person per day, three days minimum.";
        assertEquals(body, AskService.excerptOf(body));
    }

    @Test
    void hardWrappingCollapsesToOneLine() {
        // An answer typed with newlines must not produce an excerpt with
        // newlines in it — the list row is one line and would render the
        // breaks as spaces anyway, inconsistently across browsers.
        String body = "One gallon per person\nper day,\n\nthree days minimum.";
        assertEquals(
                "One gallon per person per day, three days minimum.",
                AskService.excerptOf(body));
    }

    @Test
    void longBodiesAreCutOnAWordBoundaryAndFitTheColumn() {
        String body = "word ".repeat(200).trim();
        String out = AskService.excerptOf(body);

        assertNotNull(out);
        // Must fit accepted_answer_excerpt VARCHAR(200) with room to spare.
        assertTrue(out.length() <= 200, "excerpt overflows the column: " + out.length());
        assertTrue(out.endsWith("…"), "long excerpt should be elided");
        // Cut on a boundary: no partial word before the ellipsis.
        assertFalse(out.contains("wor…"), "excerpt cut mid-word");
    }

    @Test
    void aSingleUnbrokenTokenStillFitsTheColumn() {
        // The word-boundary search must not defeat the length cap when there
        // is no boundary to find — this is the case that would throw a
        // DataIntegrityViolation on write rather than merely look bad.
        String out = AskService.excerptOf("x".repeat(500));

        assertNotNull(out);
        assertTrue(out.length() <= 200, "unbroken token overflows: " + out.length());
    }
}
