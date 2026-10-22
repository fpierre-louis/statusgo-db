package io.sitprep.sitprepapi.practice;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

/**
 * Daily sweep keeping 12 months of event-level household Practice history
 * (owner ruling 2026-10-22); beyond that only the operational latest state
 * survives, so nothing accumulates indefinitely.
 *
 * <ul>
 *   <li>{@code scenario_run}: COMPLETED/ABANDONED rows past the window go,
 *       except the newest COMPLETED run per (household, scenario) and per
 *       (user, scenario) for personal runs. That row is what drives "Practiced"
 *       and its last date. IN_PROGRESS rows are never touched.</li>
 *   <li>{@code family_practice_completion}: rows past the window go unless
 *       they are the newest for their (household, activity); deleting that one
 *       would un-complete the activity.</li>
 *   <li>{@code practice_checkin_answer}: rows past the window go. {@code practice_skill_state}
 *       is the recall schedule itself and is kept.</li>
 *   <li>{@code practice_together_session}: expired (24 h) sessions go on every tick.</li>
 *   <li>{@code group_drill_log} is already one row per drill (latest state)
 *       and is left alone. Token ledgers are not Practice history.</li>
 * </ul>
 *
 * <p>Conventions follow {@code RetentionSweepService}: daily UTC cron at a
 * free slot (3:40, after the 3:37 alert-history sweep), batches drained until a
 * short one with a per-tick cap, failures logged and sent to Sentry.</p>
 */
@Service
public class PracticeRetentionSweepService {

    private static final Logger log = LoggerFactory.getLogger(PracticeRetentionSweepService.class);

    /** The event-history window. A property so ops can tighten it; the ruling is 12. */
    static final int DEFAULT_RETENTION_MONTHS = 12;

    private final ScenarioRunRepo runRepo;
    private final FamilyPracticeCompletionRepo completionRepo;
    private final PracticeCheckInAnswerRepo checkInAnswerRepo;
    private final PracticeTogetherSessionRepo sessionRepo;
    private final Clock clock;

    @Value("${app.retention.practiceHistoryMonths:" + DEFAULT_RETENTION_MONTHS + "}")
    private int retentionMonths = DEFAULT_RETENTION_MONTHS;

    @Value("${app.retention.sweepBatchSize:1000}")
    private int sweepBatchSize = 1000;

    @Value("${app.retention.maxBatchesPerTick:50}")
    private int maxBatchesPerTick = 50;

    @Autowired
    public PracticeRetentionSweepService(ScenarioRunRepo runRepo, FamilyPracticeCompletionRepo completionRepo,
                                         PracticeCheckInAnswerRepo checkInAnswerRepo,
                                         PracticeTogetherSessionRepo sessionRepo) {
        this(runRepo, completionRepo, checkInAnswerRepo, sessionRepo, Clock.systemUTC());
    }

    PracticeRetentionSweepService(ScenarioRunRepo runRepo, FamilyPracticeCompletionRepo completionRepo,
                                  PracticeCheckInAnswerRepo checkInAnswerRepo, PracticeTogetherSessionRepo sessionRepo,
                                  Clock clock) {
        this.sessionRepo = sessionRepo;
        this.runRepo = runRepo;
        this.completionRepo = completionRepo;
        this.checkInAnswerRepo = checkInAnswerRepo;
        this.clock = clock;
    }

    /** Daily 3:40am UTC. Each table is its own drain, so one failing does not skip the other. */
    @Scheduled(cron = "0 40 3 * * *", zone = "UTC")
    public void scheduledSweep() {
        sweepAll();
    }

    /** Public so a test or an ops endpoint can run a whole tick; returns rows deleted. */
    public int sweepAll() {
        Instant cutoff = clock.instant().atZone(ZoneOffset.UTC).minusMonths(retentionMonths).toInstant();
        return drain("PracticeScenarioRunRetention", cutoff, this::sweepScenarioRunsOnce)
                + drain("PracticeFamilyCompletionRetention", cutoff, this::sweepFamilyCompletionsOnce)
                + drain("PracticeCheckInAnswerRetention", cutoff, this::sweepCheckInAnswersOnce)
                + drain("PracticeTogetherSessionRetention", cutoff, c -> sweepTogetherSessionsOnce());
    }

    /** One batch; a batch shorter than {@code sweepBatchSize} means nothing more is eligible. */
    int sweepScenarioRunsOnce(Instant cutoff) {
        List<Long> ids = runRepo.findSweepableIds(cutoff, sweepBatchSize);
        return ids.isEmpty() ? 0 : runRepo.deleteByIdIn(ids);
    }

    int sweepFamilyCompletionsOnce(Instant cutoff) {
        List<Long> ids = completionRepo.findSweepableIds(cutoff, sweepBatchSize);
        return ids.isEmpty() ? 0 : completionRepo.deleteByIdIn(ids);
    }

    /** Check-in answers past the window. Skill state ({@code practice_skill_state}) is never swept: it is the schedule. */
    int sweepCheckInAnswersOnce(Instant cutoff) {
        List<Long> ids = checkInAnswerRepo.findSweepableIds(cutoff, sweepBatchSize);
        return ids.isEmpty() ? 0 : checkInAnswerRepo.deleteByIdIn(ids);
    }

    /** Together sessions expire after 24 h; an expired one is deleted whatever the history window. */
    int sweepTogetherSessionsOnce() {
        List<Long> ids = sessionRepo.findExpiredIds(clock.instant(), sweepBatchSize);
        return ids.isEmpty() ? 0 : sessionRepo.deleteByIdIn(ids);
    }

    void configure(int months, int batchSize, int maxBatches) {
        this.retentionMonths = months;
        this.sweepBatchSize = batchSize;
        this.maxBatchesPerTick = maxBatches;
    }

    private int drain(String jobName, Instant cutoff, Function<Instant, Integer> batch) {
        int cap = Math.max(1, maxBatchesPerTick);
        int total = 0;
        int batches = 0;
        try {
            boolean drained = false;
            while (batches < cap) {
                int deleted = batch.apply(cutoff);
                batches++;
                total += deleted;
                if (deleted < sweepBatchSize) {
                    drained = true;
                    break;
                }
            }
            if (!drained) {
                log.warn("{}: hit the per-tick cap ({} batches, {} rows); the rest waits for the next tick",
                        jobName, cap, total);
            }
            if (total > 0) {
                log.info("{}: deleted {} rows in {} batch(es) (retention={}mo, batch={})",
                        jobName, total, batches, retentionMonths, sweepBatchSize);
            } else {
                log.debug("{}: nothing to delete", jobName);
            }
        } catch (Exception e) {
            log.warn("{}: tick failed after {} rows: {}", jobName, total, e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
        return total;
    }
}
