package io.sitprep.sitprepapi.readiness;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.notifications.NotificationRoutes;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ItemDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ReadinessJourneyDto;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.NotificationService;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * "Remind me later" is a real reminder (EXEC-A1 task 1, V99).
 *
 * <p>A member who taps Remind me later on a Ready for More step gets a
 * REMIND_LATER row with {@code remind_at} 1, 7 or 30 days out. Every 15
 * minutes this sweep finds the rows that have come due and not yet been
 * handled, and for each one decides:</p>
 * <ul>
 *   <li><b>DEFER</b> — the household has an active response (plan
 *       activation, check-in, official alert). Optional reminders never fire
 *       during an emergency (CONTRACT §3b). The row is left unstamped and
 *       retried on a later sweep, so the reminder is late, not lost.</li>
 *   <li><b>CLOSE</b> — the reminder is no longer true: the step is now done
 *       (and not due for review), marked not relevant, gone from the catalog
 *       or the household's local-risk set, the member left the household, it
 *       is no longer their base household (the link opens the base household's
 *       journey), or the account is gone. Stamped, nothing sent.</li>
 *   <li><b>SEND</b> — one presence-aware notification, type
 *       {@code readiness_reminder}, category {@link Category#READINESS_REMINDER}
 *       (Lane A, not critical: quiet hours and rate caps defer it to the
 *       inbox), deep link {@code /ready-for-more}.</li>
 * </ul>
 *
 * <p><b>Exactly once.</b> A SEND or CLOSE first claims the row with a
 * conditional {@code UPDATE … SET reminded_at WHERE reminded_at IS NULL AND
 * remind_at = :seen}; only a rowcount of 1 proceeds. Two instances racing on
 * one row send once, and a member who re-snoozes between the read and the
 * claim keeps their new reminder. The claim commits before the send, so a
 * crash mid-send loses that one reminder rather than repeating it.</p>
 *
 * <p><b>Paging.</b> Keyset by id, every page, until a short page: DEFERRED rows
 * stay due and unstamped, so a page-0-only loop (the
 * HouseholdChallengeScheduler shape) would re-read them forever and starve the
 * rows behind them.</p>
 *
 * <p>Off with every other job when {@code app.scheduling.enabled=false}
 * ({@code SchedulingConfig} owns {@code @EnableScheduling}).</p>
 */
@Service
public class ReadinessReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReadinessReminderService.class);

    public static final String TYPE = "readiness_reminder";
    static final String TITLE = "A step you saved for later";
    static final String ICON = "/images/icon-120.png";
    static final int BATCH_SIZE = 200;

    enum Decision { SEND, CLOSE, DEFER }

    private final HouseholdReadinessItemStateRepo stateRepo;
    private final ReadinessJourneyService journeyService;
    private final EssentialsReadinessService essentialsService;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notificationService;
    private final Clock clock;

    @Autowired
    public ReadinessReminderService(HouseholdReadinessItemStateRepo stateRepo,
                                    ReadinessJourneyService journeyService,
                                    EssentialsReadinessService essentialsService,
                                    UserInfoRepo userInfoRepo,
                                    NotificationService notificationService) {
        this(stateRepo, journeyService, essentialsService, userInfoRepo, notificationService, Clock.systemUTC());
    }

    ReadinessReminderService(HouseholdReadinessItemStateRepo stateRepo,
                             ReadinessJourneyService journeyService,
                             EssentialsReadinessService essentialsService,
                             UserInfoRepo userInfoRepo,
                             NotificationService notificationService,
                             Clock clock) {
        this.stateRepo = stateRepo;
        this.journeyService = journeyService;
        this.essentialsService = essentialsService;
        this.userInfoRepo = userInfoRepo;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    /**
     * Every 15 minutes. remind_at is "the moment you tapped, plus N days", so
     * a short cadence keeps the reminder near the time of day the member
     * chose. 11-minute initial delay keeps it off the boot path and off the
     * other sweeps' minute.
     */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT11M")
    public void scheduledSweep() {
        try {
            int sent = sweepOnce();
            if (sent > 0) log.info("ReadinessReminder: sent {} reminders", sent);
        } catch (Exception e) {
            log.warn("ReadinessReminder: sweep failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    /** One full pass. Returns the number of notifications sent. */
    public int sweepOnce() {
        Instant now = clock.instant();
        Map<String, Optional<ReadinessJourneyDto>> journeys = new HashMap<>();
        long afterId = 0;
        int sent = 0;
        while (true) {
            List<HouseholdReadinessItemState> page = stateRepo.findDueReminders(
                    ItemStateKind.REMIND_LATER, now, afterId, PageRequest.of(0, BATCH_SIZE));
            if (page == null || page.isEmpty()) break;
            for (HouseholdReadinessItemState row : page) {
                if (row.getId() != null && row.getId() > afterId) afterId = row.getId();
                try {
                    if (handle(row, now, journeys)) sent++;
                } catch (Exception e) {
                    // One bad row must not sink the sweep; it stays unstamped
                    // unless the claim already went through.
                    log.warn("ReadinessReminder: row {} failed: {}", row.getId(), e.getMessage());
                }
            }
            if (page.size() < BATCH_SIZE) break;
        }
        return sent;
    }

    /** @return true when a notification was sent for {@code row}. */
    boolean handle(HouseholdReadinessItemState row, Instant now,
                   Map<String, Optional<ReadinessJourneyDto>> journeys) {
        String email = row.getUserEmail() == null ? "" : row.getUserEmail().trim().toLowerCase(Locale.ROOT);
        String hid = row.getHouseholdId();

        Optional<ReadinessJourneyDto> journey = journeys.computeIfAbsent(hid + "|" + email, k ->
                essentialsService.isRequesterBase(hid, email)
                        ? journeyService.journeyForReminder(hid, email, now)
                        : Optional.empty());
        Optional<ItemDto> item = journey.flatMap(j -> find(j, row.getItemKey()));
        Decision decision = decide(journey, item);
        if (decision == Decision.DEFER) return false;

        Optional<UserInfo> user = decision == Decision.SEND
                ? userInfoRepo.findByUserEmailIgnoreCase(email) : Optional.empty();
        if (decision == Decision.SEND && user.isEmpty()) decision = Decision.CLOSE;

        if (stateRepo.claimReminder(row.getId(), ItemStateKind.REMIND_LATER, row.getRemindAt(), now) != 1) {
            return false;   // another instance has it, or the member changed the row
        }
        if (decision == Decision.CLOSE) return false;

        ItemDto step = item.orElseThrow();
        notificationService.deliverPresenceAware(
                email,
                TITLE,
                step.title(),
                "SitPrep",
                ICON,
                TYPE,
                step.key(),
                NotificationRoutes.READY_FOR_MORE,
                additionalData(hid, step.key()),
                user.get().getFcmtoken(),
                Category.READINESS_REMINDER);
        return true;
    }

    /**
     * What to do with one due reminder, from the journey the member would see
     * now. Empty journey = not a member / not their base / no household.
     */
    static Decision decide(Optional<ReadinessJourneyDto> journey, Optional<ItemDto> item) {
        if (journey.isEmpty()) return Decision.CLOSE;
        if (journey.get().mode() == JourneyMode.ACTIVE_RESPONSE) return Decision.DEFER;
        if (item.isEmpty()) return Decision.CLOSE;
        ItemDto i = item.get();
        if (i.householdState() == ItemStateKind.NOT_RELEVANT) return Decision.CLOSE;
        if (i.completion() == CompletionState.COMPLETE && i.freshness() != Freshness.REVIEW_DUE) return Decision.CLOSE;
        return Decision.SEND;
    }

    private static Optional<ItemDto> find(ReadinessJourneyDto j, String key) {
        if (j.areas() == null || key == null) return Optional.empty();
        return j.areas().stream()
                .flatMap(a -> a.items() == null ? java.util.stream.Stream.<ItemDto>empty() : a.items().stream())
                .filter(i -> key.equals(i.key()))
                .findFirst();
    }

    private static String additionalData(String householdId, String itemKey) {
        return String.format(Locale.ROOT, "{\"householdId\":\"%s\",\"itemKey\":\"%s\"}",
                json(householdId), json(itemKey));
    }

    private static String json(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
