package com.openrac.server;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.openrac.server.limits.RateLimit;

/**
 * The token bucket behind every per-connection rate limit.
 */

public class RateLimitTests {

    @Test
    @DisplayName("A full bucket allows its burst, then refuses")
    public void testBurst() {
        RateLimit limit = new RateLimit(3, 0);

        for (int i = 0; i < 3; i++) Assertions.assertTrue(limit.take());

        Assertions.assertFalse(limit.take());
    }

    @Test
    @DisplayName("An empty bucket refills at its rate")
    public void testRefill() throws InterruptedException {
        RateLimit limit = new RateLimit(1, 50);

        Assertions.assertTrue(limit.take());
        Assertions.assertFalse(limit.take());

        // At 50 a second, one token is back within 20 ms.
        Thread.sleep(60);

        Assertions.assertTrue(limit.take());
    }
}
