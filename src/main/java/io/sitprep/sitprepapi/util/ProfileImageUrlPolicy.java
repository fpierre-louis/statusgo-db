package io.sitprep.sitprepapi.util;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a user may store as their {@code profileImageUrl}.
 *
 * <h2>Why a write-time allow-list</h2>
 *
 * <p>The avatar is rendered on other people's screens — rosters, comments, the
 * map. Until 2026-09-27 any string was accepted, so a user could point every
 * viewer's client at an arbitrary host: a tracking pixel that logs who looked
 * at the roster and when, from where. {@code DtoImages} filters opaque schemes
 * on READ, but an {@code https://attacker.example/px.gif} passes that filter by
 * design (it has to — provider photos are plain https URLs). The only place the
 * difference between "our CDN / a sign-in provider" and "anyone" can be drawn is
 * the write.</p>
 *
 * <h2>The hosts, and where each one comes from</h2>
 *
 * <ul>
 *   <li><b>The R2 public base</b> — {@link PublicCdn#baseUrl()}
 *       ({@code https://sitprepimages.com}, overridable by
 *       {@code R2_PUBLIC_BASE_URL}). Every upload through
 *       {@code StorageService.upload} returns {@code PublicCdn.toPublicUrl(key)},
 *       so this is the host of every photo a user uploads.</li>
 *   <li><b>{@code api.dicebear.com}</b> — the avatar builder in the frontend's
 *       {@code AvatarPhotoModal} writes a DiceBear URL as the profile image.</li>
 *   <li><b>{@code *.googleusercontent.com}</b> — Google sign-in {@code photoURL}.</li>
 *   <li><b>{@code graph.facebook.com}, {@code platform-lookaside.fbsbx.com},
 *       {@code *.fbcdn.net}</b> — Facebook sign-in {@code photoURL} (web SDK
 *       returns the Graph URL; the native SDK and the Graph redirect land on the
 *       other two).</li>
 * </ul>
 *
 * <p>Apple Sign-In supplies no photo, so it contributes no host.
 * {@code *.r2.dev} is deliberately NOT here: it is Cloudflare's shared public
 * dev domain, so {@code pub-<anything>.r2.dev} is any Cloudflare customer's
 * bucket — exactly the arbitrary host this policy exists to refuse. Nothing
 * this app writes today uses it.</p>
 *
 * <p>Existing rows are not rewritten. Callers compare against the stored value
 * first, so a legacy value that is merely echoed back unchanged is not
 * re-judged.</p>
 */
public final class ProfileImageUrlPolicy {

    private ProfileImageUrlPolicy() {}

    /** The {@code profile_image_url} column is VARCHAR(255) (V1 baseline). */
    public static final int MAX_LENGTH = 255;

    private static final Set<String> EXACT_HOSTS = Set.of(
            "api.dicebear.com",
            "graph.facebook.com",
            "platform-lookaside.fbsbx.com");

    /** Suffixes are matched on a dot boundary: {@code x.googleusercontent.com} yes, {@code evilgoogleusercontent.com} no. */
    private static final List<String> HOST_SUFFIXES = List.of(
            ".googleusercontent.com",
            ".fbcdn.net");

    /**
     * Normalise a client-supplied value for storage.
     *
     * @return {@code null} for null/blank (the "clear" / "use initials" case),
     *         otherwise the trimmed URL
     * @throws IllegalArgumentException when the value is not an https URL on an
     *         allowed host
     */
    public static String normalizeForWrite(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!isAllowed(s)) {
            throw new IllegalArgumentException(
                    "profileImageUrl must be an https URL on an allowed image host");
        }
        return s;
    }

    /** True when {@code url} is a non-blank https URL on an allowed host. */
    public static boolean isAllowed(String url) {
        if (url == null) return false;
        String s = url.trim();
        if (s.isEmpty() || s.length() > MAX_LENGTH) return false;
        URI uri;
        try {
            uri = URI.create(s);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
        // user:pass@host is how "https://googleusercontent.com@evil.example/"
        // style tricks read to a human as one host and resolve to another.
        if (uri.getRawUserInfo() != null) return false;
        if (uri.getPort() != -1 && uri.getPort() != 443) return false;
        String host = uri.getHost();
        if (host == null || host.isBlank()) return false;
        return isAllowedHost(host.toLowerCase(Locale.ROOT));
    }

    private static boolean isAllowedHost(String host) {
        if (host.equals(cdnHost())) return true;
        if (EXACT_HOSTS.contains(host)) return true;
        for (String suffix : HOST_SUFFIXES) {
            if (host.endsWith(suffix) && host.length() > suffix.length()) return true;
        }
        return false;
    }

    private static String cdnHost() {
        try {
            String h = URI.create(PublicCdn.baseUrl()).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
