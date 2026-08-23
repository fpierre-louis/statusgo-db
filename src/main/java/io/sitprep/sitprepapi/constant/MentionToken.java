package io.sitprep.sitprepapi.constant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The @-mention wire format — ONE definition, read by every writer.
 *
 * <h2>Why a token and not a display name</h2>
 *
 * <p>The implementation this replaces stored {@code "@Ana "} as plain text and
 * re-derived the reference on read by scanning content for any {@code @label}
 * matching a roster first name. That fails three ways, and all three were
 * documented as known limitations rather than bugs:</p>
 *
 * <ul>
 *   <li><b>Rename breaks the link.</b> The reference lived in the display name,
 *       so changing the name silently unmade the mention.</li>
 *   <li><b>Duplicate first names notify everyone.</b> Two members called Chris
 *       meant both got pushed, because a name is not an identity.</li>
 *   <li><b>Display text was doing a reference's job</b> — the same class as the
 *       reply quote-prefix that leaked into an author name and rendered
 *       {@code Francis "Dione"}.</li>
 * </ul>
 *
 * <h2>Why the token carries the position</h2>
 *
 * <p>The alternative shape was a side table of {@code (comment, user, offset,
 * length)}. It is more normalized and it is wrong here, because <b>comments are
 * editable</b> and stored offsets go stale the moment the surrounding text
 * changes. A token inside the content cannot desynchronize from the content: an
 * edit either keeps the token or deletes it, and both of those are the correct
 * outcome without any reconciliation step.</p>
 *
 * <h2>The honest cost</h2>
 *
 * <p>{@code content} is no longer clean prose. A consumer that does not resolve
 * — a push body, a feed snippet, an export — prints the raw token. That is the
 * accepted trade, and the mitigation is that resolution lives in exactly one
 * place ({@code MentionService}) used by both the DTO fold and the notification
 * body builder. A consumer outside it renders something ugly; it never
 * FABRICATES a name, which is what the plain-text scan did.</p>
 *
 * <h2>Strict UUID matching, on purpose</h2>
 *
 * <p>The id group matches a UUID and nothing else, so {@code @[uid:hello]}
 * typed by a user is text and never resolves. Same rule as {@code HazardType}:
 * unknown values are DROPPED, never coerced into something valid-looking.</p>
 */
public final class MentionToken {

    private MentionToken() {}

    /** Canonical wire form, e.g. {@code @[uid:338ea7ea-4892-4073-b2c6-6f69a5167544]}. */
    private static final String UUID_RE =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    public static final Pattern PATTERN = Pattern.compile("@\\[uid:(" + UUID_RE + ")]");

    /** Build the token for one user id. The FE composer inserts this shape. */
    public static String of(String userId) {
        return "@[uid:" + userId + "]";
    }

    /**
     * Every distinct user id mentioned in {@code content}, in the order they
     * appear. Insertion-ordered so "who was mentioned first" survives, and
     * de-duplicated so mentioning someone twice notifies them once.
     */
    public static List<String> extractIds(String content) {
        if (content == null || content.isEmpty()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PATTERN.matcher(content);
        while (m.find()) out.add(m.group(1).toLowerCase());
        return new ArrayList<>(out);
    }

    /** True when the content carries at least one token. */
    public static boolean hasMention(String content) {
        return content != null && !content.isEmpty() && PATTERN.matcher(content).find();
    }

    /**
     * Replace every token with {@code @DisplayName} for surfaces that cannot
     * render structure — push bodies, feed snippets, notification titles.
     *
     * <p>An id missing from {@code namesById} renders as the tombstone rather
     * than as a raw token or a blank: a deleted account is a knowable state, and
     * printing nothing would silently change what a sentence says.</p>
     */
    public static String toPlainText(String content, Map<String, String> namesById) {
        if (content == null || content.isEmpty()) return content;
        Matcher m = PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = namesById == null ? null : namesById.get(m.group(1).toLowerCase());
            m.appendReplacement(sb, Matcher.quoteReplacement("@" + (name == null ? TOMBSTONE_NAME : name)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * What a mention of a deleted account reads as. Deliberately a person-shaped
     * phrase rather than an id or an empty string — the sentence still has to
     * make sense after the account is gone.
     */
    public static final String TOMBSTONE_NAME = "Former member";
}
