package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.service.GroupService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
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

    public ShareResource(GroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping("/share/group/{groupId}")
    public ResponseEntity<?> shareGroup(
            @PathVariable String groupId,
            @RequestHeader(value = "User-Agent", required = false) String userAgent
    ) {
        boolean isBot = userAgent != null && BOT_UA.matcher(userAgent).find();
        String baseOrigin = trimTrailingSlash(frontendBaseUrl);
        String safeId = URLEncoder.encode(groupId, StandardCharsets.UTF_8);
        String spaUrl = baseOrigin + "/joingroup?groupId=" + safeId;
        String shareUrl = baseOrigin + "/share/group/" + safeId;

        // Try to load the group. If the id is bad / missing, send
        // humans to the SPA (which renders a friendly "invalid invite"
        // message) and bots a generic preview (no group name).
        Group group = null;
        try {
            group = groupService.getGroupByPublicId(groupId);
        } catch (Exception ignored) {
            // Group not found — handled below.
        }

        if (!isBot) {
            // Human path — clean 302 to the SPA. No HTML body.
            HttpHeaders h = new HttpHeaders();
            h.setLocation(URI.create(spaUrl));
            h.setCacheControl("no-store");
            return ResponseEntity.status(302).headers(h).build();
        }

        // Bot path — emit OG HTML.
        String title = group != null
                ? group.getGroupName() + " on SitPrep"
                : "Join a circle on SitPrep";
        String description = buildDescription(group);
        String image = baseOrigin + "/images/sitprep-share-default.png";
        String html = renderOgHtml(title, description, image, shareUrl, spaUrl);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_HTML);
        // 5-minute CDN-friendly cache. Vary on UA so bots and humans
        // get different cached responses.
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
