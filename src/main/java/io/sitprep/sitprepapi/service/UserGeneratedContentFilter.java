package io.sitprep.sitprepapi.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Minimal Phase 2 UGC safety guard. This is not a replacement for human
 * reports/review; it blocks the obvious abusive/scam content Apple expects a
 * social surface to filter before it is published.
 */
public final class UserGeneratedContentFilter {

    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("\\bkill\\s+yourself\\b"),
            Pattern.compile("\\bkys\\b"),
            Pattern.compile("\\bi\\s+(?:will|am\\s+going\\s+to|gonna)\\s+(?:kill|shoot|stab)\\s+you\\b"),
            Pattern.compile("\\bguaranteed\\s+(?:crypto|bitcoin|investment)\\s+(?:profit|return)s?\\b"),
            Pattern.compile("\\b(?:cashapp|venmo|zelle)\\b.{0,80}\\b(?:rescue|evacuation|shelter|insulin|water)\\b"),
            Pattern.compile("\\b(?:rescue|evacuation|shelter|insulin|water)\\b.{0,80}\\b(?:cashapp|venmo|zelle)\\b")
    );

    private UserGeneratedContentFilter() {}

    public static void requireAcceptable(String surface, String... values) {
        if (looksObjectionable(values)) {
            String name = surface == null || surface.isBlank() ? "content" : surface;
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This " + name + " needs a quick edit before posting."
            );
        }
    }

    static boolean looksObjectionable(String... values) {
        String text = normalize(values);
        if (text.isBlank()) return false;
        for (Pattern pattern : BLOCKED_PATTERNS) {
            if (pattern.matcher(text).find()) return true;
        }
        return false;
    }

    private static String normalize(String... values) {
        if (values == null || values.length == 0) return "";
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(value);
        }
        return out.toString()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9@$._\\s-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
