package com.openrac.utils.logger;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Constants for the logger.
 */

public class LogConstants {
    // The timestamp that opens each line, shared by every thread.
    public static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault());
}
