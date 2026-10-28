package com.openrac.utils.logger;

import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * Centralized logging utility that outputs coloured, level-filtered messages to the console. The
 * level comes from the DEBUG_LEVEL environment variable and the debug gate from DEBUGGING.
 */

public class Log {

    private static volatile DebugLevel level = getDebugLevel();
    private static volatile boolean debugging = getDebugging();

    /**
     * Overrides the active debug level (otherwise derived from the DEBUG_LEVEL environment variable).
     * @param debugLevel The level to set.
     */

    public static void setLevel(DebugLevel debugLevel) {
        level = debugLevel;
    }

    /**
     * Returns the active debug level.
     * @return The current level.
     */

    public static DebugLevel getLevel() {
        return level;
    }

    /**
     * Whether {@link #verbose(String)} prints anything, so a hot path can skip building a line nobody
     * will see. It needs debugging mode and a level of {@code VERBOSE} or {@code ALL}.
     * @return Whether verbose lines are printed.
     */

    public static boolean isVerbose() {
        return debugging && (level == DebugLevel.VERBOSE || level == DebugLevel.ALL);
    }

    /**
     * Overrides the debugging gate that controls whether {@link #debug(String)} emits output
     * (otherwise derived from the DEBUGGING environment variable).
     * @param value Whether debug messages should be emitted.
     */

    public static void setDebugging(boolean value) {
        debugging = value;
    }

    /**
     * Outputs an info message to the console if the current debug level is set to show info messages.
     * @param message The message to output.
     */

    public static void info(String message) {
        // Skip if we are not logging any info messages.
        if (level != DebugLevel.INFO && level != DebugLevel.ALL) return;

        log("INFO", message, "28");
    }

    /**
     * Outputs a debug message to the console if the current debug level is set to show debug messages.
     * @param message The message to output.
     */

    public static void debug(String message) {
        // If we do not have debugging mode enabled, we should not output debug messages.
        if (!debugging) return;

        // Skip if we are not logging any debug messages.
        if (level != DebugLevel.INFO && level != DebugLevel.ALL) return;

        log("DEBUG", message, "36");
    }

    /**
     * Outputs a debug error message to the console if the current debug level is set to show debug errors.
     * @param message The message to output.
     */

    public static void debugError(String message) {
        // Skip if we are not in debugging mode.
        if (!debugging) return;

        // Skip if we are not logging any debug errors.
        if (level != DebugLevel.INFO && level != DebugLevel.ALL) return;

        log("DEBUG ERROR", message, "35");
    }

    /**
     * Outputs a verbose message to the console. Only emitted when the debug level is
     * explicitly {@code VERBOSE} or {@code ALL}, gated tighter than {@link #info(String)}
     * so per-packet tracing doesn't drown the default log stream.
     * @param message The message to output.
     */

    public static void verbose(String message) {
        // Skip unless the operator explicitly opted in to verbose output.
        if (!isVerbose()) return;

        log("VERBOSE", message, "90");
    }

    /**
     * Outputs a notice message to the console if the current debug level is set to show notices.
     * @param message The message to output.
     */

    public static void notice(String message) {
        // Skip if we are not logging any notices.
        if (level != DebugLevel.INFO && level != DebugLevel.ALL) return;

        log("NOTICE", message, "32");
    }

    /**
     * Outputs a warning message to the console if the current debug level is set to show warnings.
     * @param message The message to output.
     */

    public static void warning(String message) {
        // Skip if we are not logging any warnings.
        if (level != DebugLevel.WARNING && level != DebugLevel.ALL) return;

        log("WARNING", message, "33");
    }

    /**
     * Outputs an error message to the console if the current debug level is set to show errors.
     * @param message The message to output.
     */

    public static void error(String message) {
        // Skip if we are not logging any errors.
        if (level != DebugLevel.ERROR && level != DebugLevel.ALL) return;

        log("ERROR", message, "31");
    }

    /**
     * Outputs a critical message to the console if the current debug level is set to show critical messages.
     * @param message The message to output.
     */

    public static void critical(String message) {
        // Skip if we are not logging any critical messages.
        if (level != DebugLevel.ERROR && level != DebugLevel.ALL) return;

        log("CRITICAL", message, "41");
    }

    /**
     * Helper function for logging binary data to the console.
     * @param buffer The buffer to log.
     */

    public static void buffer(ByteBuffer buffer) {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < buffer.limit(); i++)
            builder.append(buffer.get(i)).append(" ");

        String formattedMessage = "\u001B[35m" + getTime() + "\u001B[0m" + " " + "[" + "\u001B[36m" + "BUFFER" + "\u001B[0m" + "]" + " " + builder;

        System.out.println(formattedMessage);
    }

    /**
     * Shortcut for logging a raw byte array.
     * @param bytes The bytes to log.
     */

    public static void buffer(byte[] bytes) {
        buffer(ByteBuffer.wrap(bytes));
    }

    /**
     * Outputs a message to the console based on the level and colour. All logs are routed
     * through this method. Format generally follows the pattern:
     * DATE (PURPLE), [LEVEL (COLOUR)], MESSAGE (WHITE)
     * @param level The level of the message.
     * @param message The message to output.
     * @param color The colour to output the message in.
     */

    private static void log(String level, String message, String color) {
        String formattedMessage = "\u001B[35m" + getTime() + "\u001B[0m" + " " + "[" + "\u001B[" + color + "m" + level + "\u001B[0m" + "]" + " " + message;

        System.out.println(formattedMessage);
    }

    /**
     * Obtains the current time and formats it in a human-readable way.
     * @return The formatted time as a string.
     */

    private static String getTime() {
        return LogConstants.TIME_FORMAT.format(Instant.ofEpochMilli(System.currentTimeMillis()));
    }

    /**
     * Converts the DEBUG_LEVEL environment variable to an enum.
     * @return The debug level as an object of the DebugLevel enum.
     */

    private static DebugLevel getDebugLevel() {
        return toLevel(env("DEBUG_LEVEL", "all"));
    }

    /**
     * Converts a level's name, as DEBUG_LEVEL spells it, to an enum.
     * @param name The level's name.
     * @return The debug level as an object of the DebugLevel enum.
     */

    public static DebugLevel toLevel(String name) {
        return switch (name.toLowerCase()) {
            case "none" -> DebugLevel.NONE;
            case "error" -> DebugLevel.ERROR;
            case "warning", "warn" -> DebugLevel.WARNING;
            case "verbose" -> DebugLevel.VERBOSE;

            // By default, we always want to show all logs.
            default -> DebugLevel.ALL;
        };
    }

    /**
     * Reads the DEBUGGING flag from the environment (defaults to false), gating {@link #debug(String)}.
     * @return Whether debug output is enabled.
     */

    private static boolean getDebugging() {
        return Boolean.parseBoolean(env("DEBUGGING", "false"));
    }

    /**
     * Reads a value from the system environment, falling back to a JVM system property and then
     * a default.
     * @param key The key to read.
     * @param defaultValue The fallback value.
     * @return The resolved value.
     */

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);

        if (value == null) value = System.getProperty(key);

        return value == null ? defaultValue : value;
    }
}
