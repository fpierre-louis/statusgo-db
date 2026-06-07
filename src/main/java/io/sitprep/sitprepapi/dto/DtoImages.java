package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.util.PublicCdn;

import java.net.URI;

/**
 * Strict image-URL resolver for DTO build sites.
 *
 * <p>DTOs ship one of three things to the browser:
 * <ol>
 *   <li>A fully-resolved {@code https://} URL on the configured R2 public base — pass through.</li>
 *   <li>A bare R2 object key — normalize via {@link PublicCdn#toPublicUrl(String)}.</li>
 *   <li>Anything else (legacy Firebase URL, signed S3 URL, {@code data:} URI, blank) — drop to {@code null}.</li>
 * </ol>
 *
 * <p>This is intentionally stricter than {@link PublicCdn}: {@code PublicCdn} will salvage keys out of
 * {@code *.r2.dev} / {@code r2.cloudflarestorage.com} URLs and will pass {@code data:}/{@code blob:} through.
 * For DTO payloads we want a hard guarantee that the FE either receives a usable
 * {@code https://<r2-public-base>/<key>} URL or nothing at all — no stale Firebase canaries, no signed URLs
 * that will 403 the moment they expire, no opaque legacy strings the {@code <img>} tag can't resolve.
 *
 * <p>See audit BE-01 in {@code docs/audit/RACE_AUDIT_GAMEPLAN.md}.
 */
public final class DtoImages {

    private DtoImages() {}

    /** Resolve a raw avatar value into a public R2 URL, or {@code null} if it can't be safely resolved. */
    public static String avatar(String raw) {
        return resolve(raw);
    }

    /** Resolve a raw cover value into a public R2 URL, or {@code null} if it can't be safely resolved. */
    public static String cover(String raw) {
        return resolve(raw);
    }

    private static String resolve(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        String lower = s.toLowerCase();

        // Reject opaque non-http payloads outright (data:, blob:, file:).
        if (lower.startsWith("data:") || lower.startsWith("blob:") || lower.startsWith("file:")) {
            return null;
        }

        String base = PublicCdn.baseUrl();

        // Already a URL on our configured R2 public base — pass through unchanged.
        if (s.equals(base) || s.startsWith(base + "/")) {
            return s;
        }

        // Any other http(s) URL is legacy (Firebase, signed S3, r2.dev, etc.) — drop it.
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//")) {
            return null;
        }

        // Bare key — must be a plausible R2 object key (no scheme, no traversal, no host-looking content).
        if (!looksLikeBareKey(s)) return null;

        String normalized = PublicCdn.toPublicUrl(s);
        if (normalized == null) return null;

        // Defensive: only return if normalization landed on the configured base.
        if (normalized.equals(base) || normalized.startsWith(base + "/")) {
            return normalized;
        }
        return null;
    }

    private static boolean looksLikeBareKey(String s) {
        if (s.contains("..")) return false;
        // Reject anything that parses as having a scheme or authority.
        try {
            URI u = URI.create(s);
            if (u.getScheme() != null) return false;
            if (u.getHost() != null) return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
        return true;
    }
}
