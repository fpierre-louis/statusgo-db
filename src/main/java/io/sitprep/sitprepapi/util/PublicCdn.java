package io.sitprep.sitprepapi.util;

import java.net.URI;

/**
 * Translates between R2 object keys and the public delivery URL on
 * {@code https://sitprepimages.com}. Symmetric — pass an URL or a key,
 * always get back the canonical form.
 *
 * Default base URL is overridable at runtime via {@code R2_PUBLIC_BASE_URL}
 * env var (matches Heroku config).
 */
public final class PublicCdn {

    private PublicCdn() {}

    private static final String DEFAULT_BASE = "https://sitprepimages.com";

    public static String baseUrl() {
        String v = firstNonBlank(System.getenv("R2_PUBLIC_BASE_URL"), DEFAULT_BASE);
        v = v.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    public static String toPublicUrl(String keyOrUrl) {
        String s = safeTrim(keyOrUrl);
        if (s == null) return null;

        String lower = s.toLowerCase();
        if (lower.startsWith("data:") || lower.startsWith("blob:") || lower.startsWith("file:")) return s;
        if (lower.startsWith("//")) s = "https:" + s;

        String base = baseUrl();
        if (s.equals(base) || s.startsWith(base + "/")) return s;

        if (isHttpUrl(s)) {
            String key = toObjectKey(s);
            if (key == null) return s;
            return base + "/" + stripLeadingSlash(key);
        }
        return base + "/" + stripLeadingSlash(s);
    }

    public static String toObjectKey(String keyOrUrl) {
        String s = safeTrim(keyOrUrl);
        if (s == null) return null;

        String lower = s.toLowerCase();
        if (lower.startsWith("data:") || lower.startsWith("blob:") || lower.startsWith("file:")) return null;
        if (lower.startsWith("//")) s = "https:" + s;

        if (!isHttpUrl(s)) {
            s = stripQueryAndFragment(s);
            s = stripLeadingSlash(s);
            return safeTrim(s);
        }

        try {
            URI uri = URI.create(s);

            String host = safeTrim(uri.getHost());
            String path = safeTrim(uri.getPath());
            if (path == null || path.equals("/")) return null;

            path = stripLeadingSlash(path);
            if (path.isBlank()) return null;

            // R2.dev URL: pub-<hash>.r2.dev/<key>
            if (host != null && host.endsWith(".r2.dev")) {
                return path;
            }

            // Direct S3 endpoint: <account>.r2.cloudflarestorage.com/<bucket>/<key>
            if (host != null && host.contains("r2.cloudflarestorage.com")) {
                int slash = path.indexOf('/');
                if (slash > 0 && slash < path.length() - 1) return path.substring(slash + 1);
                return null;
            }

            // Custom domain (sitprepimages.com): host matches our base — path is the key
            String baseHost = safeHost(URI.create(baseUrl()).getHost());
            if (baseHost != null && host != null && host.equalsIgnoreCase(baseHost)) {
                return path;
            }

            // Fallback: treat path as key as long as it doesn't traverse
            if (!path.contains("..")) return path;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isHttpUrl(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static String stripLeadingSlash(String v) {
        if (v == null) return null;
        String s = v.trim();
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    private static String stripQueryAndFragment(String v) {
        if (v == null) return null;
        String s = v.trim();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        return s;
    }

    private static String safeTrim(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    private static String safeHost(String host) {
        if (host == null) return null;
        String s = host.trim();
        return s.isEmpty() ? null : s.toLowerCase();
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null) return null;
        for (String x : xs) {
            if (x != null && !x.trim().isEmpty()) return x;
        }
        return null;
    }
}
