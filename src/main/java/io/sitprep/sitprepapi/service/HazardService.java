package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.HazardCategory;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HazardReport;
import io.sitprep.sitprepapi.domain.HazardVote;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.dto.HazardDto;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HazardReportRepo;
import io.sitprep.sitprepapi.repo.HazardVoteRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.GeoUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Neighbor hazard reports — report, confirm, clear, read (hazard-reports
 * gameplan HR1; rulings H-1…H-4 in the frontend repo's
 * docs/epics/hazard-reports/GAMEPLAN.md).
 *
 * <p><b>The server owns every judgement here.</b> Whether a report is
 * Reported, Confirmed, Official, Cleared or Expired is computed on each read
 * from the votes and timestamps; no client sends a state. The router will act
 * on "Confirmed" (H-2), so the one thing this class must never do is let a
 * single report reach it.</p>
 */
@Service
public class HazardService {

    /** You must be this close to what you report (the client sends its fix). */
    static final double MAX_REPORT_DISTANCE_M = 3_200;
    /** Reports per person per hour. */
    static final int MAX_REPORTS_PER_HOUR = 5;
    /** A second report of the same kind this close is a "still there" on the first. */
    static final double DUPLICATE_WITHIN_M = 200;
    /** The window votes count in. */
    static final Duration VOTE_WINDOW = Duration.ofMinutes(60);
    /** Distinct "still there" people in the window that make a report Confirmed (H-2). */
    static final int CONFIRMED_AT = 3;
    /** "Gone" votes in the window that, when they outnumber "still", clear a report. */
    static final int CLEARED_AT_GONE = 2;
    /** A still-confirmed report lives at most this many lifetimes past its report. */
    static final int MAX_LIFETIMES = 3;

    /**
     * {@code showName}: the reporter opted in to being named (HR6). Absent or
     * false — the default, owner 2026-10-03 — the post reads "Reported by a
     * neighbor" to everyone but them.
     */
    public record ReportRequest(String category, Double lat, Double lng, String note,
                                List<String> imageKeys, Double reporterLat, Double reporterLng,
                                Boolean showName) {}

    private final HazardReportRepo hazards;
    private final HazardVoteRepo votes;
    private final PostRepo posts;
    private final PostService postService;
    private final UserInfoRepo users;
    private final GroupRepo groups;
    private final AgencyAuthorizationService agencyAuth;
    private final Clock clock;

    @Autowired
    public HazardService(HazardReportRepo hazards, HazardVoteRepo votes, PostRepo posts, PostService postService,
                         UserInfoRepo users, GroupRepo groups, AgencyAuthorizationService agencyAuth) {
        this(hazards, votes, posts, postService, users, groups, agencyAuth, Clock.systemUTC());
    }

    HazardService(HazardReportRepo hazards, HazardVoteRepo votes, PostRepo posts, PostService postService,
                  UserInfoRepo users, GroupRepo groups, AgencyAuthorizationService agencyAuth, Clock clock) {
        this.hazards = hazards;
        this.votes = votes;
        this.posts = posts;
        this.postService = postService;
        this.users = users;
        this.groups = groups;
        this.agencyAuth = agencyAuth;
        this.clock = clock;
    }

    // ── report ──────────────────────────────────────────────────────────────

    @Transactional
    public HazardDto report(ReportRequest req, String callerEmail) {
        String me = requireReporter(callerEmail);
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body required");
        HazardCategory cat = HazardCategory.fromWire(req.category())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown hazard category"));
        if (!GeoUtil.validLatLng(req.lat(), req.lng())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The hazard needs a location");
        }
        if (!GeoUtil.validLatLng(req.reporterLat(), req.reporterLng())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Turn on location to report a hazard");
        }
        double metres = GeoUtil.haversineKm(req.lat(), req.lng(), req.reporterLat(), req.reporterLng()) * 1000;
        if (metres > MAX_REPORT_DISTANCE_M) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    String.format(Locale.US, "Move closer to report — you're %.1f mi away", metres / 1609.344));
        }

        Instant now = clock.instant();
        Duration longest = Arrays.stream(HazardCategory.values()).map(HazardCategory::lifetime)
                .max(Comparator.naturalOrder()).orElse(Duration.ofDays(3)).multipliedBy(MAX_LIFETIMES);
        List<HazardReport> mine = hazards.findByReporterSince(me, now.minus(longest));

        // The same person reporting the same thing again nearby is confirming it.
        Map<Long, Post> minePosts = postsById(mine.stream().map(HazardReport::getTaskId).toList());
        for (HazardReport h : mine) {
            Post p = minePosts.get(h.getTaskId());
            if (p == null || !cat.wire().equals(h.getCategory()) || !isActive(h, now)) continue;
            if (GeoUtil.haversineKm(req.lat(), req.lng(), p.getLatitude(), p.getLongitude()) * 1000 <= DUPLICATE_WITHIN_M) {
                return vote(h.getTaskId(), HazardVote.STILL, me);
            }
        }
        long lastHour = mine.stream().filter(h -> !h.getReportedAt().isBefore(now.minus(Duration.ofHours(1)))).count();
        if (lastHour >= MAX_REPORTS_PER_HOUR) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many reports in the last hour");
        }

        // The post itself goes through the ordinary create path: feed, content
        // filter, image ownership, reverse-geocoded place label, broadcast.
        Post incoming = new Post();
        incoming.setKind("hazard");
        incoming.setTitle(cat.label());
        incoming.setDescription(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        incoming.setLatitude(req.lat());
        incoming.setLongitude(req.lng());
        incoming.setAuthorHidden(!Boolean.TRUE.equals(req.showName()));
        List<String> keys = req.imageKeys() == null ? List.of() : req.imageKeys();
        if (!keys.isEmpty()) incoming.setImageKeys(new ArrayList<>(keys));
        Long postId = createPost(incoming, me);

        HazardReport h = new HazardReport();
        h.setTaskId(postId);
        h.setCategory(cat.wire());
        h.setRadiusM(cat.radiusM());
        h.setReportedAt(now);
        h.setExpiresAt(now.plus(cat.lifetime()));
        h.setHasPhoto(!keys.isEmpty());
        hazards.save(h);

        // Reporting it is saying it is there.
        HazardVote v = new HazardVote();
        v.setTaskId(h.getTaskId());
        v.setUserEmail(me);
        v.setVote(HazardVote.STILL);
        v.setVotedAt(now);
        votes.save(v);

        Post post = posts.findById(h.getTaskId()).orElse(null);
        return toDto(h, post, List.of(v), me, now);
    }

    // ── vote ────────────────────────────────────────────────────────────────

    @Transactional
    public HazardDto vote(Long id, String vote, String callerEmail) {
        String me = requireReporter(callerEmail);
        String v = vote == null ? "" : vote.trim().toLowerCase();
        if (!HazardVote.STILL.equals(v) && !HazardVote.GONE.equals(v)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "vote must be still or gone");
        }
        Instant now = clock.instant();
        HazardReport h = hazards.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Post post = posts.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<HazardVote> all = new ArrayList<>(votes.findByTaskIdIn(List.of(id)));
        String state = stateOf(h, all, now);
        if ("cleared".equals(state) || "expired".equals(state)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This report has ended");
        }

        HazardVote row = votes.findByTaskIdAndUserEmail(id, me).orElseGet(() -> {
            HazardVote fresh = new HazardVote();
            fresh.setTaskId(id);
            fresh.setUserEmail(me);
            return fresh;
        });
        row.setVote(v);
        row.setVotedAt(now);
        votes.save(row);
        all.removeIf(x -> me.equalsIgnoreCase(x.getUserEmail()));
        all.add(row);

        if (HazardVote.STILL.equals(v)) {
            HazardCategory cat = HazardCategory.fromWire(h.getCategory()).orElseThrow();
            Instant cap = h.getReportedAt().plus(cat.lifetime().multipliedBy(MAX_LIFETIMES));
            Instant next = now.plus(cat.lifetime());
            if (next.isAfter(cap)) next = cap;
            if (next.isAfter(h.getExpiresAt())) {
                h.setExpiresAt(next);
                hazards.save(h);
            }
        }
        return toDto(h, post, all, me, now);
    }

    // ── agency: official / clear (H-3) ──────────────────────────────────────

    @Transactional
    public HazardDto markOfficial(Long id, String agencyGroupId, String callerEmail) {
        return agencyAct(id, agencyGroupId, callerEmail, true);
    }

    @Transactional
    public HazardDto clear(Long id, String agencyGroupId, String callerEmail) {
        return agencyAct(id, agencyGroupId, callerEmail, false);
    }

    private HazardDto agencyAct(Long id, String agencyGroupId, String callerEmail, boolean official) {
        if (callerEmail == null || callerEmail.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (agencyGroupId == null || agencyGroupId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agencyGroupId required");
        }
        Group agency = groups.findByGroupId(agencyGroupId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // The same gate that decides who may send an area alert for this agency.
        agencyAuth.requireAgencyPostingAllowed(agency, callerEmail.trim().toLowerCase());
        Instant now = clock.instant();
        HazardReport h = hazards.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Post post = posts.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (official) {
            h.setOfficialAt(now);
            h.setOfficialBy(agency.getGroupId());
        } else {
            h.setClearedAt(now);
            h.setClearedBy(agency.getGroupId());
        }
        hazards.save(h);
        return toDto(h, post, votes.findByTaskIdIn(List.of(id)), callerEmail, now);
    }

    // ── read ────────────────────────────────────────────────────────────────

    /** Active reports in the box — Reported, Confirmed or Official. Public. */
    @Transactional(readOnly = true)
    public List<HazardDto> inBox(double minLat, double minLng, double maxLat, double maxLng, String viewerEmail) {
        Instant now = clock.instant();
        List<HazardReport> active = hazards.findByClearedAtIsNullAndExpiresAtAfter(now);
        if (active.isEmpty()) return List.of();
        List<Long> ids = active.stream().map(HazardReport::getTaskId).toList();
        Map<Long, Post> byId = postsById(ids);
        Map<Long, List<HazardVote>> votesById = votes.findByTaskIdIn(ids).stream()
                .collect(Collectors.groupingBy(HazardVote::getTaskId));
        List<HazardDto> out = new ArrayList<>();
        for (HazardReport h : active) {
            Post p = byId.get(h.getTaskId());
            if (p == null || p.getLatitude() == null || p.getLongitude() == null) continue;
            if (p.getLatitude() < minLat || p.getLatitude() > maxLat || p.getLongitude() < minLng || p.getLongitude() > maxLng) continue;
            HazardDto dto = toDto(h, p, votesById.getOrDefault(h.getTaskId(), List.of()), viewerEmail, now);
            if ("cleared".equals(dto.state()) || "expired".equals(dto.state())) continue;
            out.add(dto);
        }
        out.sort(Comparator.comparing(HazardDto::reportedAt).reversed());
        return out;
    }

    // ── state ───────────────────────────────────────────────────────────────

    /** The one place a report's state is decided. Order matters — see the class note. */
    static String stateOf(HazardReport h, Collection<HazardVote> all, Instant now) {
        if (h.getClearedAt() != null) return "cleared";
        if (!now.isBefore(h.getExpiresAt())) return "expired";
        if (h.getOfficialAt() != null) return "official";
        Instant since = now.minus(VOTE_WINDOW);
        long still = recent(all, HazardVote.STILL, since);
        long gone = recent(all, HazardVote.GONE, since);
        if (gone >= CLEARED_AT_GONE && gone > still) return "cleared";
        if (still >= CONFIRMED_AT || h.isHasPhoto()) return "confirmed";
        return "reported";
    }

    private static long recent(Collection<HazardVote> all, String vote, Instant since) {
        return all.stream().filter(v -> vote.equals(v.getVote()) && !v.getVotedAt().isBefore(since))
                .map(v -> v.getUserEmail().toLowerCase()).distinct().count();
    }

    private static boolean isActive(HazardReport h, Instant now) {
        return h.getClearedAt() == null && now.isBefore(h.getExpiresAt());
    }

    private HazardDto toDto(HazardReport h, Post p, Collection<HazardVote> all, String viewerEmail, Instant now) {
        HazardCategory cat = HazardCategory.fromWire(h.getCategory()).orElseThrow();
        Instant lastStill = all.stream().filter(v -> HazardVote.STILL.equals(v.getVote()))
                .map(HazardVote::getVotedAt).max(Comparator.naturalOrder()).orElse(null);
        String viewerVote = viewerEmail == null ? null : all.stream()
                .filter(v -> viewerEmail.equalsIgnoreCase(v.getUserEmail())).map(HazardVote::getVote)
                .findFirst().orElse(null);
        return new HazardDto(
                h.getTaskId(), cat.wire(), cat.label(), cat.blocksRoutes(),
                p == null ? null : p.getLatitude(), p == null ? null : p.getLongitude(),
                h.getRadiusM(), stateOf(h, all, now),
                recent(all, HazardVote.STILL, now.minus(VOTE_WINDOW)), lastStill,
                h.getReportedAt(), h.getExpiresAt(), h.isHasPhoto(),
                p == null ? null : p.getDescription(), viewerVote);
    }

    /** The post goes through the ordinary create path. A seam so tests need not build a PostDto. */
    Long createPost(Post incoming, String me) {
        PostDto created = postService.create(incoming, me);
        return created.id();
    }

    private Map<Long, Post> postsById(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return posts.findAllById(ids).stream().collect(Collectors.toMap(Post::getId, Function.identity()));
    }

    /** Signed in, and not a guest — a guest account is disposable, so its reports and votes are not. */
    private String requireReporter(String email) {
        if (email == null || email.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String me = email.trim().toLowerCase();
        boolean guest = users.findByUserEmailIgnoreCase(me).map(u -> Boolean.TRUE.equals(u.getGuestAccount())).orElse(false);
        if (guest) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Create an account to report or confirm hazards");
        return me;
    }
}
