package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.BriefSlot;
import io.sitprep.sitprepapi.constant.ConditionTiers;
import io.sitprep.sitprepapi.constant.PostKind;
import io.sitprep.sitprepapi.constant.SystemAccounts;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.dto.ConditionsReading;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * SitPrep's daily brief: ONE post, region-specific on read (EXEC-3C,
 * owner ruling 2026-10-03).
 *
 * <ul>
 *   <li><b>One post.</b> Kind {@code daily-brief}, system-authored, no
 *       coordinates, so the feed's existing geo-less rule carries it to every
 *       viewer. Created once at startup when briefs are enabled.</li>
 *   <li><b>What it shows is the viewer's.</b> {@link #viewFor} builds the card
 *       from the viewer's coordinates: "Conditions near {city}", the reading
 *       (°F, wind, AQI — the strict order), and a tip chosen for those
 *       conditions at the viewer's LOCAL time of day.</li>
 *   <li><b>Surfacing is a clock, not a job.</b> {@link #surfacedAt} is the
 *       latest 7:00 / 12:00 / 18:00 America/New_York that has passed;
 *       {@link #insertIndex} places the card below official content and the
 *       alert pin, with only posts newer than that time above it. No
 *       scheduler, no per-area rows, nothing to clean up.</li>
 *   <li><b>Hidden, never faked</b> — no coordinates, no reading, or the area
 *       in alert/crisis mode (the warning stays on top). The caller decides
 *       the mode; this returns null for the rest.</li>
 * </ul>
 */
@Service
public class DailyBriefService {

    private static final Logger log = LoggerFactory.getLogger(DailyBriefService.class);

    /** The one schedule everyone shares (owner, 2026-10-03). Position only — no notification. */
    static final ZoneId BUMP_ZONE = ZoneId.of("America/New_York");
    static final List<LocalTime> BUMPS = List.of(LocalTime.of(7, 0), LocalTime.of(12, 0), LocalTime.of(18, 0));
    /** Past this many newer posts the card is simply further down, as any post would be. */
    static final int MAX_NEWER_ABOVE = 10;

    private final PostRepo postRepo;
    private final ConditionsService conditions;
    private final DailyNudgeCatalog catalog;
    private final DailyBriefComposer composer;
    private final NominatimGeocodeService geocode;
    private final Clock clock;
    private final boolean enabled;
    private final boolean dryRun;

    private volatile Long postId;
    /** City per 0.1° cell. A cell's name doesn't change; Nominatim is throttled. */
    private final Map<String, String> placeByCell = new ConcurrentHashMap<>();

    @Autowired
    public DailyBriefService(PostRepo postRepo, ConditionsService conditions, DailyNudgeCatalog catalog,
                             DailyBriefComposer composer, NominatimGeocodeService geocode,
                             @Value("${briefs.enabled:false}") boolean enabled,
                             @Value("${briefs.dry-run:true}") boolean dryRun) {
        this(postRepo, conditions, catalog, composer, geocode, Clock.systemUTC(), enabled, dryRun);
    }

    DailyBriefService(PostRepo postRepo, ConditionsService conditions, DailyNudgeCatalog catalog,
                      DailyBriefComposer composer, NominatimGeocodeService geocode, Clock clock,
                      boolean enabled, boolean dryRun) {
        this.postRepo = postRepo;
        this.conditions = conditions;
        this.catalog = catalog;
        this.composer = composer;
        this.geocode = geocode;
        this.clock = clock;
        this.enabled = enabled;
        this.dryRun = dryRun;
    }

    /** True when the card is folded into feeds. Dry run = preview endpoint only. */
    public boolean live() {
        return enabled && !dryRun;
    }

    /** True for the one brief post. */
    public static boolean isBrief(PostDto d) {
        return d != null && PostKind.DAILY_BRIEF.wire().equals(d.kind());
    }

    /** Find or create the one brief post. Idempotent; runs once at startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void ensurePost() {
        if (!enabled) return;
        try {
            Post existing = postRepo.findFirstByKindOrderByIdAsc(PostKind.DAILY_BRIEF.wire()).orElse(null);
            if (existing != null) {
                postId = existing.getId();
                return;
            }
            Post p = new Post();
            p.setKind(PostKind.DAILY_BRIEF.wire());
            p.setRequesterEmail(SystemAccounts.SITPREP_EMAIL);
            p.setStatus(PostStatus.OPEN);
            // Never displayed: the card is built per viewer. Present only
            // because a post row needs a body.
            p.setDescription("SitPrep daily brief");
            Post saved = postRepo.save(p);
            postId = saved.getId();
            log.info("DailyBrief: created the brief post id={}", postId);
        } catch (Exception e) {
            log.warn("DailyBrief: could not ensure the brief post: {}", e.toString());
        }
    }

    /**
     * The card as a viewer at (lat, lng) sees it now, or null when it must be
     * hidden (no time zone, no reading, or a reading missing a required value).
     */
    public PostDto.CommunityExtras.BriefView viewFor(double lat, double lng) {
        String tz = conditions.timezoneFor(lat, lng);
        if (tz == null) return null;
        ZonedDateTime local = clock.instant().atZone(ZoneId.of(tz));
        BriefSlot slot = slotFor(local.toLocalTime());
        ConditionsReading r = conditions.readingFor(lat, lng, slot);
        if (r == null || r.now() == null) return null;
        String sentence = composer.weatherSentence(r, null);
        if (sentence == null) return null;
        ConditionTiers.Condition condition;
        try {
            condition = ConditionTiers.Condition.valueOf(r.condition());
        } catch (Exception e) {
            return null;
        }
        String cellKey = cellKey(lat, lng);
        DailyNudgeCatalog.Nudge n = catalog.forView(condition, slot, local.toLocalDate(), cellKey);
        if (n == null) return null;
        ConditionsReading.Now now = r.now();
        return new PostDto.CommunityExtras.BriefView(
                placeFor(cellKey, lat, lng), sentence,
                now.tempF(), now.feelsF(), now.windMph(), now.windDir(), now.gustMph(),
                now.aqi(), now.aqiCategory(), r.condition(), r.tier(),
                n.id(), n.text(), n.label(), n.destination(), n.minutes(),
                r.observedAt(), tz, DailyNudgeCatalog.imageFor(n));
    }

    /** The tip's tone follows the reader's local clock, not the Eastern bump. */
    static BriefSlot slotFor(LocalTime t) {
        int h = t.getHour();
        if (h >= 4 && h < 11) return BriefSlot.MORNING;
        if (h >= 11 && h < 16) return BriefSlot.MIDDAY;
        return BriefSlot.EVENING;
    }

    /** The latest 7:00 / 12:00 / 18:00 America/New_York at or before {@code now}. */
    public static Instant surfacedAt(Instant now) {
        ZonedDateTime et = now.atZone(BUMP_ZONE);
        LocalDate day = et.toLocalDate();
        for (int i = BUMPS.size() - 1; i >= 0; i--) {
            ZonedDateTime bump = ZonedDateTime.of(day, BUMPS.get(i), BUMP_ZONE);
            if (!bump.isAfter(et)) return bump.toInstant();
        }
        return ZonedDateTime.of(day.minusDays(1), BUMPS.get(BUMPS.size() - 1), BUMP_ZONE).toInstant();
    }

    public Instant surfacedAtNow() {
        return surfacedAt(clock.instant());
    }

    /**
     * Where the card goes in a ranked feed: after the leading block of
     * official content (tier ≤ 2) and the pinned alert, then after the posts
     * created since the bump — "newer posts stack above it". Capped so a busy
     * feed doesn't sink it past the first screen before the next bump.
     */
    public static <T> int insertIndex(List<T> merged, Instant surfacedAt, ToIntFunction<T> tier,
                                      Predicate<T> pinned, java.util.function.Function<T, Instant> createdAt) {
        int i = 0;
        while (i < merged.size() && (tier.applyAsInt(merged.get(i)) <= 2 || pinned.test(merged.get(i)))) i++;
        int newer = 0;
        for (int j = i; j < merged.size(); j++) {
            Instant c = createdAt.apply(merged.get(j));
            if (c != null && c.isAfter(surfacedAt)) newer++;
        }
        return Math.min(merged.size(), i + Math.min(newer, MAX_NEWER_ABOVE));
    }

    static String cellKey(double lat, double lng) {
        return String.format(Locale.ROOT, "%.1f|%.1f", ConditionsService.snap(lat), ConditionsService.snap(lng));
    }

    /** City-level name for a cell: a cell is ~11 km, so never a neighbourhood. */
    private String placeFor(String cellKey, double lat, double lng) {
        String cached = placeByCell.get(cellKey);
        if (cached != null) return cached;
        String name = null;
        try {
            NominatimGeocodeService.Place p = geocode.reverse(ConditionsService.snap(lat), ConditionsService.snap(lng));
            if (p != null) name = firstNonBlank(p.city(), p.region(), p.state());
        } catch (Exception ignored) {
            // A missing name degrades to "you"; the reading is still true.
        }
        if (name == null) return "you";
        placeByCell.put(cellKey, name);
        return name;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }
}
