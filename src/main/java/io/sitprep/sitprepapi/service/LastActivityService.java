package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks "last active" for authenticated users with a per-user write throttle.
 *
 * <p>{@link io.sitprep.sitprepapi.security.FirebaseAuthFilter} calls
 * {@link #touch(String)} after every successful token verification. We
 * keep an in-memory map of email → last-write-instant; if the user has
 * been "touched" within the throttle window, we no-op. Otherwise we fire
 * an async UPDATE so the request itself stays fast.</p>
 *
 * <p>The map is process-local — on a multi-instance Heroku dyno setup the
 * effective throttle becomes "throttleSeconds × instanceCount". Acceptable;
 * we'd rather over-write a tiny bit than under-write and miss presence.</p>
 */
@Service
public class LastActivityService {

    private static final Logger log = LoggerFactory.getLogger(LastActivityService.class);

    private final UserInfoRepo userInfoRepo;
    private final ConcurrentMap<String, Instant> lastWritten = new ConcurrentHashMap<>();
    private final Duration throttle;

    public LastActivityService(
            UserInfoRepo userInfoRepo,
            @Value("${sitprep.activity.throttle-seconds:300}") long throttleSeconds
    ) {
        this.userInfoRepo = userInfoRepo;
        this.throttle = Duration.ofSeconds(throttleSeconds);
    }

    /**
     * Mark the email as active. May or may not write to the DB depending on
     * the throttle. Fire-and-forget — errors are logged at debug, never
     * thrown back into the request handler.
     */
    public void touch(String email) {
        if (email == null) return;
        String key = email.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return;

        Instant now = Instant.now();
        Instant prev = lastWritten.get(key);
        if (prev != null && now.isBefore(prev.plus(throttle))) {
            return; // within throttle window
        }
        // Race: two threads can both pass the check above, but the worst
        // case is two near-simultaneous UPDATEs — same value, no harm.
        lastWritten.put(key, now);
        writeAsync(key, now);
    }

    @Async
    @Transactional
    void writeAsync(String email, Instant when) {
        try {
            userInfoRepo.findByUserEmailIgnoreCase(email).ifPresent(u -> {
                u.setLastActiveAt(when);
                userInfoRepo.save(u);
            });
        } catch (Exception e) {
            log.debug("LastActivityService update failed for {}: {}", email, e.getMessage());
        }
    }
}
