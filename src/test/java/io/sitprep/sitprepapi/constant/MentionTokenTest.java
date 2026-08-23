package io.sitprep.sitprepapi.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mention token format. Each test here corresponds to a way the plain-text
 * implementation this replaces got it wrong.
 */
class MentionTokenTest {

    private static final String ANA = "338ea7ea-4892-4073-b2c6-6f69a5167544";
    private static final String BEN = "7c20a1b0-dedb-46a8-8a1d-5b3e4ea6c484";

    @Test
    @DisplayName("ids come out in the order they appear, de-duplicated")
    void extractsInOrderDeduped() {
        String c = MentionToken.of(BEN) + " and " + MentionToken.of(ANA) + " and " + MentionToken.of(BEN) + " again";
        // Order preserved so "who was mentioned first" survives; de-duplicated
        // so mentioning someone twice notifies them once.
        assertThat(MentionToken.extractIds(c)).containsExactly(BEN, ANA);
    }

    @Test
    @DisplayName("an email address is not a mention")
    void emailIsNotAMention() {
        // The plain-text scanner needed a (^|\\s) guard to avoid opening on
        // "ana@example.com". A structured token needs no guard.
        assertThat(MentionToken.extractIds("write to ana@example.com about it")).isEmpty();
        assertThat(MentionToken.hasMention("ana@example.com")).isFalse();
    }

    @Test
    @DisplayName("a token-shaped string that is not a uuid is text, not a mention")
    void nonUuidIsNotCoerced() {
        // Same rule as HazardType: unknown values are DROPPED, never coerced
        // into something valid-looking. A typo must not become a mention.
        assertThat(MentionToken.extractIds("@[uid:hello]")).isEmpty();
        assertThat(MentionToken.extractIds("@[uid:338ea7ea-4892]")).isEmpty();
    }

    @Test
    @DisplayName("a name is never the reference -- @Ana is plain text")
    void displayNameIsNotAReference() {
        // The whole point. Under the old scan this string mentioned someone,
        // and WHICH someone depended on the roster at read time.
        assertThat(MentionToken.extractIds("thanks @Ana!")).isEmpty();
    }

    @Test
    @DisplayName("plain-text rendering substitutes names and keeps surrounding text")
    void plainTextSubstitutes() {
        String c = "morning " + MentionToken.of(ANA) + ", can you check?";
        assertThat(MentionToken.toPlainText(c, Map.of(ANA, "Ana Reyes")))
                .isEqualTo("morning @Ana Reyes, can you check?");
    }

    @Test
    @DisplayName("an unresolvable id renders as the tombstone, not a raw token or a blank")
    void unresolvedRendersTombstone() {
        String c = "ask " + MentionToken.of(ANA);
        // Blank would silently change what the sentence says; the raw uuid
        // would leak an internal id at the moment the person is gone.
        assertThat(MentionToken.toPlainText(c, Map.of()))
                .isEqualTo("ask @" + MentionToken.TOMBSTONE_NAME);
    }

    @Test
    @DisplayName("regex metacharacters in a display name survive substitution")
    void nameWithMetacharactersIsSafe() {
        // appendReplacement treats $ and \\ as syntax -- a name containing them
        // would corrupt the output or throw without quoteReplacement.
        assertThat(MentionToken.toPlainText(MentionToken.of(ANA), Map.of(ANA, "A$B\\C")))
                .isEqualTo("@A$B\\C");
    }

    @Test
    @DisplayName("null and empty content are handled, not thrown on")
    void nullSafe() {
        assertThat(MentionToken.extractIds(null)).isEqualTo(List.of());
        assertThat(MentionToken.toPlainText(null, Map.of())).isNull();
        assertThat(MentionToken.hasMention(null)).isFalse();
    }
}
