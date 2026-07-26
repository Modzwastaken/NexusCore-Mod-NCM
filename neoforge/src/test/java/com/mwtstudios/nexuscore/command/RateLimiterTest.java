package com.mwtstudios.nexuscore.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Token-bucket behaviour, with an injected clock so nothing sleeps. */
class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(0L);

    @Test
    @DisplayName("a fresh subject may burst up to the full capacity, then is refused")
    void burstThenRefuse() {
        RateLimiter limiter = new RateLimiter(60, now::get);
        UUID subject = UUID.randomUUID();

        for (int i = 0; i < 60; i++) {
            assertTrue(limiter.tryAcquire(subject), "permit " + i + " should have been granted");
        }
        assertFalse(limiter.tryAcquire(subject), "the 61st request in the same instant must be refused");
    }

    @Test
    @DisplayName("permits refill over time at the configured rate")
    void permitsRefill() {
        RateLimiter limiter = new RateLimiter(60, now::get);
        UUID subject = UUID.randomUUID();
        for (int i = 0; i < 60; i++) {
            limiter.tryAcquire(subject);
        }
        assertFalse(limiter.tryAcquire(subject));

        // 60 per minute is one per second.
        now.addAndGet(1_000L);

        assertTrue(limiter.tryAcquire(subject));
        assertFalse(limiter.tryAcquire(subject), "only one permit should have accrued");
    }

    @Test
    @DisplayName("the bucket never refills past capacity, however long the subject idles")
    void refillIsCapped() {
        RateLimiter limiter = new RateLimiter(10, now::get);
        UUID subject = UUID.randomUUID();
        limiter.tryAcquire(subject);

        now.addAndGet(1_000_000L);

        assertEquals(10, limiter.available(subject));
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(subject));
        }
        assertFalse(limiter.tryAcquire(subject));
    }

    @Test
    @DisplayName("subjects are limited independently")
    void subjectsAreIndependent() {
        RateLimiter limiter = new RateLimiter(2, now::get);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(limiter.tryAcquire(first));
        assertTrue(limiter.tryAcquire(first));
        assertFalse(limiter.tryAcquire(first));

        assertTrue(limiter.tryAcquire(second), "one player exhausting their bucket must not affect another");
    }

    @Test
    @DisplayName("the tracked-subject map is bounded, so unique UUIDs cannot grow it without limit")
    void trackedSubjectsAreBounded() {
        RateLimiter limiter = new RateLimiter(60, now::get);

        for (int i = 0; i < RateLimiter.MAX_TRACKED * 2; i++) {
            limiter.tryAcquire(UUID.randomUUID());
        }

        assertTrue(limiter.tracked() <= RateLimiter.MAX_TRACKED,
                "a rate limiter that can be exhausted by the traffic it limits is worse than none");
    }

    @Test
    @DisplayName("forgetting a subject resets their bucket")
    void forgetResetsSubject() {
        RateLimiter limiter = new RateLimiter(1, now::get);
        UUID subject = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(subject));
        assertFalse(limiter.tryAcquire(subject));

        limiter.forget(subject);

        assertTrue(limiter.tryAcquire(subject));
    }

    @Test
    @DisplayName("an unknown subject reports full capacity")
    void unknownSubjectHasFullCapacity() {
        assertEquals(120, new RateLimiter(120, now::get).available(UUID.randomUUID()));
    }

    @Test
    @DisplayName("a clock that goes backwards does not grant free permits")
    void backwardsClockIsSafe() {
        RateLimiter limiter = new RateLimiter(2, now::get);
        UUID subject = UUID.randomUUID();
        now.set(10_000L);
        assertTrue(limiter.tryAcquire(subject));
        assertTrue(limiter.tryAcquire(subject));
        assertFalse(limiter.tryAcquire(subject));

        now.set(0L);

        assertFalse(limiter.tryAcquire(subject), "a backwards clock must not refill the bucket");
    }
}
