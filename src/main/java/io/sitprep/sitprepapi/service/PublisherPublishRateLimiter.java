package io.sitprep.sitprepapi.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class PublisherPublishRateLimiter {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofHours(24);
    private static final int OFFICIAL_HOURLY_LIMIT = 12;
    private static final int OFFICIAL_DAILY_LIMIT = 60;
    private static final int SPONSORED_HOURLY_LIMIT = 3;
    private static final int SPONSORED_DAILY_LIMIT = 10;

    private final ConcurrentMap<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    public boolean tryConsume(String publisherKey, boolean sponsored) {
        if (publisherKey == null || publisherKey.isBlank()) return true;
        String key = (sponsored ? "sponsored:" : "official:")
                + publisherKey.trim().toLowerCase(Locale.ROOT);
        Instant now = Instant.now();
        Deque<Instant> deque = buckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            pruneOlderThan(deque, now, DAY);
            int hourly = countWithin(deque, now, HOUR);
            int hourlyLimit = sponsored ? SPONSORED_HOURLY_LIMIT : OFFICIAL_HOURLY_LIMIT;
            int dailyLimit = sponsored ? SPONSORED_DAILY_LIMIT : OFFICIAL_DAILY_LIMIT;
            if (hourly >= hourlyLimit || deque.size() >= dailyLimit) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    private static void pruneOlderThan(Deque<Instant> deque, Instant now, Duration window) {
        Instant cutoff = now.minus(window);
        Iterator<Instant> it = deque.iterator();
        while (it.hasNext()) {
            if (it.next().isBefore(cutoff)) it.remove();
            else break;
        }
    }

    private static int countWithin(Deque<Instant> deque, Instant now, Duration window) {
        Instant cutoff = now.minus(window);
        int count = 0;
        Iterator<Instant> it = deque.descendingIterator();
        while (it.hasNext()) {
            Instant t = it.next();
            if (t.isBefore(cutoff)) break;
            count++;
        }
        return count;
    }
}
