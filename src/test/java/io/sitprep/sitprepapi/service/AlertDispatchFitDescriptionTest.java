package io.sitprep.sitprepapi.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** An issuer's long text is cut to fit a post, not refused (2026-10-09). */
class AlertDispatchFitDescriptionTest {

    @Test
    void shortTextIsUntouched() {
        assertEquals("Move to higher ground.", AlertDispatchService.fitDescription("Move to higher ground."));
        assertNull(AlertDispatchService.fitDescription(null));
    }

    @Test
    void longTextFitsTheLimitAndEndsOnABoundaryWithAPointerToTheAlert() {
        StringBuilder b = new StringBuilder();
        while (b.length() < 9000) b.append("Turn around, don't drown when encountering flooded roads. ");
        String out = AlertDispatchService.fitDescription(b.toString());
        assertTrue(out.length() <= PostService.MAX_DESCRIPTION_CHARS, "fits");
        assertTrue(out.endsWith(AlertDispatchService.CONTINUED));
        String kept = out.substring(0, out.length() - AlertDispatchService.CONTINUED.length());
        assertTrue(kept.endsWith("roads."), "cut at a sentence: " + kept.substring(kept.length() - 20));
        PostService.requireStorableDescription(out); // the guard that used to refuse it
    }

    @Test
    void prefersAParagraphBreak() {
        String para = "A".repeat(2500) + ".\n\n" + "B".repeat(3000);
        String out = AlertDispatchService.fitDescription(para);
        assertTrue(out.startsWith("A".repeat(2500)));
        assertFalse(out.contains("B"));
        assertTrue(out.length() <= PostService.MAX_DESCRIPTION_CHARS);
    }
}
