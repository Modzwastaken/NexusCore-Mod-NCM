package com.mwtstudios.nexuscore.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Per-player token bucket (§15, "Rate limiting").
 *
 * <p>Bounded by construction: the bucket map is an LRU capped at {@link #MAX_TRACKED}
 * subjects, so a stream of unique UUIDs cannot grow it without limit. §16 forbids unbounded
 * UUID maps, and a rate limiter that can be exhausted by the traffic it exists to limit is
 * worse than none.</p>
 *
 * <p>The clock is injected rather than read from {@code System}, so the tests are
 * deterministic and never sleep (§21.4).</p>
 */
public final class RateLimiter {

    /** Maximum distinct subjects tracked at once. */
    public static final int MAX_TRACKED = 2048;

    private final int capacity;
    private final double refillPerMilli;
    private final LongSupplier clock;
    private final Map<UUID, Bucket> buckets;

    /**
     * @param permitsPerMinute sustained rate, which is also the burst capacity
     * @param clock supplies the current time in milliseconds
     */
    public RateLimiter(int permitsPerMinute, LongSupplier clock) {
        this.capacity = Math.max(1, permitsPerMinute);
        this.refillPerMilli = this.capacity / 60_000.0;
        this.clock = clock;
        this.buckets = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Bucket> eldest) {
                return size() > MAX_TRACKED;
            }
        };
    }

    /**
     * Attempts to consume one permit.
     *
     * @param subject the player, or a fixed UUID for the console
     * @return true when the request may proceed
     */
    public synchronized boolean tryAcquire(UUID subject) {
        long now = clock.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(subject, key -> new Bucket(capacity, now));

        long elapsed = Math.max(0L, now - bucket.lastRefillMillis);
        bucket.tokens = Math.min(capacity, bucket.tokens + elapsed * refillPerMilli);
        bucket.lastRefillMillis = now;

        if (bucket.tokens < 1.0) {
            return false;
        }
        bucket.tokens -= 1.0;
        return true;
    }

    /**
     * @param subject the player
     * @return whole permits currently available
     */
    public synchronized int available(UUID subject) {
        Bucket bucket = buckets.get(subject);
        if (bucket == null) {
            return capacity;
        }
        long elapsed = Math.max(0L, clock.getAsLong() - bucket.lastRefillMillis);
        return (int) Math.min(capacity, bucket.tokens + elapsed * refillPerMilli);
    }

    /** Forgets a subject, for example when they disconnect. */
    public synchronized void forget(UUID subject) {
        buckets.remove(subject);
    }

    /** @return how many subjects are currently tracked */
    public synchronized int tracked() {
        return buckets.size();
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillMillis;

        private Bucket(int tokens, long now) {
            this.tokens = tokens;
            this.lastRefillMillis = now;
        }
    }
}
