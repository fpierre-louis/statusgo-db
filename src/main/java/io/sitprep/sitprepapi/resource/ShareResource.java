package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupInvite;
import io.sitprep.sitprepapi.service.GroupInviteService;
import io.sitprep.sitprepapi.service.GroupService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Public share-preview endpoints. Pair with the FE
 * {@code GroupShareSheet} which builds links of the form
 * {@code https://sitprep.app/share/group/{id}} for chat apps and
 * social platforms that scrape OpenGraph tags.
 *
 * <h3>UA branching</h3>
 * The same URL serves two different responses depending on caller:
 * <ul>
 *   <li><b>Bots / scrapers</b> (Facebook, Discord, iMessage, LinkedIn,
 *       Twitter, Slack, etc.) → 200 OK with OpenGraph + Twitter Card
 *       HTML. No redirect — Facebook treats meta-refresh as a redirect
 *       and re-scrapes the destination, which would lose our tags.</li>
 *   <li><b>Humans</b> (real browsers + the SPA) → 302 redirect to the
 *       SPA's {@code /joingroup?groupId=...} handler. The SPA reads
 *       the groupId, prompts auth if needed, lands the user on the
 *       join-confirmation page.</li>
 * </ul>
 *
 * <h3>Why not just redirect everyone</h3>
 * Bots that hit a 302 follow the redirect to the SPA, which is JS-
 * heavy and doesn't render OG tags server-side. The link preview
 * would be empty / use the SPA's default title. Serving OG HTML
 * directly to bots gives a rich preview without any SPA work.
 *
 * <h3>Anti-leak</h3>
 * The share preview pulls from the public {@code GroupPreviewDto}-
 * style sanitized fields (group name, type, member count, owner name,
 * description). Never emits member emails or admin emails into the
 * HTML.
 */
@RestController
public class ShareResource {

    /**
     * Bot regex — UAs we serve OG HTML to. Add a UA here if a new
     * platform's link previews are coming up empty.
     */
    private static final Pattern BOT_UA = Pattern.compile(
            "(?i)("
                    + "facebookexternalhit|Facebot|"
                    + "Twitterbot|"
                    + "LinkedInBot|"
                    + "Slackbot|"
                    + "Discordbot|"
                    + "WhatsApp|"
                    + "TelegramBot|"
                    + "SkypeUriPreview|"
                    + "Googlebot|bingbot|"
                    + "Applebot|"
                    + "redditbot|"
                    + "Pinterest"
                    + ")"
    );

    private final GroupService groupService;
    private final GroupInviteService inviteService;

    /**
     * Public origin used when building absolute URLs in the OG
     * preview. Never derive this from the incoming request — Heroku
     * health checks + misconfigured proxies leak internal hostnames
     * into the rendered tags, sending Facebook etc. off to scrape
     * a non-public URL.
     *
     * Override via env: {@code APP_FRONTEND_BASE_URL=https://sitprep.app}.
     * Default falls back to the canonical prod origin.
     */
    @Value("${app.frontend-base-url:https://sitprep.app}")
    private String frontendBaseUrl;

    public ShareResource(GroupService groupService, GroupInviteService inviteService) {
        this.groupService = groupService;
        this.inviteService = inviteService;
    }

    /**
     * Tokenized invite preview. The canonical share URL is
     * {@code /share/i/{inviteId}} — admins mint these via
     * {@link GroupInviteResource} and the FE share sheet builds
     * URLs from the returned id. Bots get OG tags scoped to the
     * invite's group; humans 302 into the SPA's invite handler.
     *
     * <p>Failure states surface as branded HTML (bots) or a 410-style
     * SPA route (humans):</p>
     * <ul>
     *   <li>NOT_FOUND — token doesn't exist (mistyped or never minted)</li>
     *   <li>EXPIRED  — past expiresAt</li>
     *   <li>REVOKED  — admin killed it</li>
     *   <li>EXHAUSTED — single-use already redeemed</li>
     * </ul>
     */
    @GetMapping("/share/i/{inviteId}")
    public ResponseEntity<?> shareByInvite(
            @PathVariable String inviteId,
            @RequestHeader(value = "User-Agent", required = false) String userAgent
    ) {
        boolean isBot = userAgent != null && BOT_UA.matcher(userAgent).find();
        String baseOrigin = trimTrailingSlash(frontendBaseUrl);

        var result = inviteService.validate(inviteId);
        if (!result.isOk()) {
            // For humans: 302 to a friendly invite-error SPA route so
            // the user sees branded copy, not a generic 410.
            if (!isBot) {
                HttpHeaders h = new HttpHeaders();
                h.setLocation(URI.create(baseOrigin
                        + "/joingroup?inviteError=" + result.state().name().toLowerCase()));
                h.setCacheControl("no-store");
                return ResponseEntity.status(302).headers(h).build();
            }
            // For bots: serve a generic preview so link unfurls don't
            // explode loudly in chat threads — keep the user agnostic
            // about whether the link was wrong vs revoked.
            String html = renderOgHtml(
                    "Join a circle on SitPrep",
                    "This invite is no longer valid. Ask whoever sent it to share a fresh one.",
                    baseOrigin + "/images/sitprep-share-default.png",
                    baseOrigin + "/share/i/" + URLEncoder.encode(inviteId, StandardCharsets.UTF_8),
                    baseOrigin + "/joingroup"
            );
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.TEXT_HTML);
            h.setCacheControl("public, max-age=60");
            h.add(HttpHeaders.VARY, "User-Agent");
            return ResponseEntity.status(HttpStatus.GONE).headers(h).body(html);
        }

        // Resolve token → group, then run the same UA-branched OG/302
        // path the legacy /share/group/{groupId} endpoint uses.
        GroupInvite invite = result.invite();
        return renderShare(invite.getGroupId(), inviteId, isBot, baseOrigin);
    }

    @GetMapping("/share/group/{groupId}")
    public ResponseEntity<?> shareGroup(
            @PathVariable String groupId,
            @RequestHeader(value = "User-Agent", required = false) String userAgent
    ) {
        boolean isBot = userAgent != null && BOT_UA.matcher(userAgent).find();
        String baseOrigin = trimTrailingSlash(frontendBaseUrl);
        return renderShare(groupId, /* inviteId */ null, isBot, baseOrigin);
    }

    /**
     * Common rendering pipeline for both share entry points
     * ({@code /share/group/{id}} and {@code /share/i/{token}}).
     * Looks up the group, then either:
     *   - 302s humans to the SPA's {@code /joingroup?groupId=...} handler
     *   - emits OG-tagged HTML for bots
     *
     * <p>If {@code inviteId} is non-null, the {@code shareUrl} embedded
     * in the OG canonical / og:url tags points at the tokenized URL
     * — so when Facebook re-scrapes a previously cached preview, it
     * comes back to the same canonical URL and doesn't trash its cache.</p>
     */
    private ResponseEntity<?> renderShare(String groupId,
                                           String inviteId,
                                           boolean isBot,
                                           String baseOrigin) {
        String safeGroupId = URLEncoder.encode(groupId == null ? "" : groupId, StandardCharsets.UTF_8);
        String spaUrl = baseOrigin + "/joingroup?groupId=" + safeGroupId;
        String shareUrl = inviteId != null
                ? baseOrigin + "/share/i/" + URLEncoder.encode(inviteId, StandardCharsets.UTF_8)
                : baseOrigin + "/share/group/" + safeGroupId;

        // Try to load the group. If the id is bad / missing, send
        // humans to the SPA (renders a friendly "invalid invite"
        // message) and bots a generic preview (no group name).
        Group group = null;
        try {
            group = groupService.getGroupByPublicId(groupId);
        } catch (Exception ignored) {
            // Group not found — handled below.
        }

        if (!isBot) {
            HttpHeaders h = new HttpHeaders();
            h.setLocation(URI.create(spaUrl));
            h.setCacheControl("no-store");
            return ResponseEntity.status(302).headers(h).build();
        }

        String title = group != null
                ? group.getGroupName() + " on SitPrep"
                : "Join a circle on SitPrep";
        String description = buildDescription(group);
        String image = baseOrigin + "/images/sitprep-share-default.png";
        String html = renderOgHtml(title, description, image, shareUrl, spaUrl);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_HTML);
        h.setCacheControl("public, max-age=300");
        h.add(HttpHeaders.VARY, "User-Agent");
        return ResponseEntity.ok().headers(h).body(html);
    }

    private String buildDescription(Group group) {
        if (group == null) {
            return "SitPrep helps you stay connected with your circle when something happens.";
        }
        StringBuilder sb = new StringBuilder();
        Integer memberCount = group.getMemberCount();
        if (memberCount != null && memberCount > 0) {
            sb.append(memberCount)
                    .append(memberCount == 1 ? " member" : " members");
        }
        if (group.getDescription() != null && !group.getDescription().isBlank()) {
            if (sb.length() > 0) sb.append(" · ");
            String desc = group.getDescription().trim();
            if (desc.length() > 180) desc = desc.substring(0, 177) + "…";
            sb.append(desc);
        }
        if (sb.length() == 0) {
            sb.append("Join this circle on SitPrep.");
        }
        return sb.toString();
    }

    private String renderOgHtml(String title, String description,
                                String image, String shareUrl, String spaUrl) {
        // Strict HTML escaping for the user-controlled fields
        // (groupName, description) so a stray `</title>` in a group
        // name can't break out of its tag and inject markup.
        String t = htmlEscape(title);
        String d = htmlEscape(description);
        String i = htmlEscape(image);
        String s = htmlEscape(shareUrl);
        String x = htmlEscape(spaUrl);

        return "<!DOCTYPE html>"
                + "<html lang=\"en\"><head>"
                + "<meta charset=\"UTF-8\">"
                + "<title>" + t + "</title>"
                + "<meta name=\"description\" content=\"" + d + "\">"
                + "<link rel=\"canonical\" href=\"" + s + "\">"
                + "<meta property=\"og:type\" content=\"website\">"
                + "<meta property=\"og:site_name\" content=\"SitPrep\">"
                + "<meta property=\"og:url\" content=\"" + s + "\">"
                + "<meta property=\"og:title\" content=\"" + t + "\">"
                + "<meta property=\"og:description\" content=\"" + d + "\">"
                + "<meta property=\"og:image\" content=\"" + i + "\">"
                + "<meta property=\"og:image:alt\" content=\"SitPrep — stay connected when it matters\">"
                + "<meta name=\"twitter:card\" content=\"summary_large_image\">"
                + "<meta name=\"twitter:title\" content=\"" + t + "\">"
                + "<meta name=\"twitter:description\" content=\"" + d + "\">"
                + "<meta name=\"twitter:image\" content=\"" + i + "\">"
                + "<meta name=\"twitter:image:alt\" content=\"SitPrep\">"
                + "</head><body>"
                // Visible <noscript>-equivalent fallback link in case a
                // human accidentally hits this URL with a UA that
                // matches the bot regex (rare but possible).
                + "<p>Open in SitPrep: <a href=\"" + x + "\">" + x + "</a></p>"
                + "</body></html>";
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
