package com.openrac.server.limits;

/**
 * A token bucket: it allows a burst of up to its capacity, then refills at a steady rate. Each
 * connection holds its own, and only its event loop uses them, so no locking is needed.
 */

public class RateLimit {
    private final double capacity;
    private final double perNano; // Tokens regained per nanosecond.

    private double tokens;
    private long last = System.nanoTime();

    /**
     * Creates a full bucket.
     * @param capacity The most actions allowed in one burst.
     * @param perSecond The actions regained each second.
     */

    public RateLimit(int capacity, double perSecond) {
        this.capacity = capacity;
        this.perNano = perSecond / 1_000_000_000d;

        tokens = capacity;
    }

    /**
     * Spends one action if the bucket allows it.
     * @return Whether the action is within the limit.
     */

    public boolean take() {
        long now = System.nanoTime();

        tokens = Math.min(capacity, tokens + (now - last) * perNano);
        last = now;

        // An empty bucket refuses until it has refilled.
        if (tokens < 1) return false;

        tokens--;
        return true;
    }
}
