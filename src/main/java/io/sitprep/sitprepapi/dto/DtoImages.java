package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.util.PublicCdn;

import java.net.URI;

/**
 * Image-URL resolver for DTO build sites.
 *
 * <p>Backend owns image filtering/canonicalization. DTOs ship one canonical
 * display field ({@code profileImageUrl}, {@code coverImageUrl}, etc.) that is
 * either directly browser-loadable or {@code null}; the frontend should just
 * pass that value to its image component.
 *
 * <p>Accepted input:
 * <ol>
 *   <li>{@code http://}, {@code https://}, or protocol-relative image URLs
 *       (provider photos such as Google/Facebook and SitPrep CDN uploads).</li>
 *   <li>Legacy bare R2 object keys, normalized via {@link PublicCdn#toPublicUrl(String)}.</li>
 * </ol>
 *
 * <p>Blank values, opaque schemes ({@code data:}, {@code blob:}, {@code file:}),
 * malformed strings, and traversal-ish keys resolve to {@code null}.
 */
public final class DtoImages {

    private DtoImages() {}

    /** Resolve a stored avatar value into a URL the browser can load (or {@code null} if blank). */
    public static String avatar(String raw) {
        return resolve(raw);
    }

    /** Resolve a stored cover value into a URL the browser can load (or {@code null} if blank). */
    public static String cover(String raw) {
        return resolve(raw);
    }

    /**
     * True iff {@link #avatar}/{@link #cover} would resolve this raw value to a
     * non-null URL. Use this for {@code hasProfileImage} / {@code hasCoverImage}
     * flags so the boolean and the shipped URL agree <em>by construction</em> —
     * one resolver, one answer.
     */
    public static boolean isPresent(String raw) {
        return resolve(raw) != null;
    }

    private static String resolve(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        String lower = s.toLowerCase();

        if (lower.startsWith("data:")
                || lower.startsWith("blob:")
                || lower.startsWith("file:")
                || lower.startsWith("javascript:")) {
            return null;
        }

        if (lower.startsWith("//")) {
            return "https:" + s;
        }

        // Absolute provider/CDN URL — pass through after URI validation.
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            if (!isHttpUrl(s)) return null;
            return s;
        }

        if (!looksLikeBareKey(s)) return null;

        String normalized = PublicCdn.toPublicUrl(s);
        return normalized != null && isHttpUrl(normalized) ? normalized : null;
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean looksLikeBareKey(String value) {
        if (value.contains("..")) return false;
        try {
            URI uri = URI.create(value);
            return uri.getScheme() == null && uri.getHost() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
