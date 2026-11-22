package com.openrac.utils;

/**
 * The server's clock, which every player stamps game data against. It counts from start-up on the
 * monotonic timer, so a change of the system time never moves it.
 */

public final class Clock {
    private static final long START = System.nanoTime();

    private Clock() {}

    /**
     * @return Milliseconds since the server started.
     */

    public static long now() {
        return (System.nanoTime() - START) / 1_000_000;
    }
}
