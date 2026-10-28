package com.openrac.net;

/**
 * Values both ends of the wire have to agree on.
 */

public class NetworkConstants {
    public static final int LENGTH_FIELD_LENGTH = 4; // Bytes in the length prefix ahead of each packet.
    public static final int MAX_FRAME_LENGTH = 65_536; // Longest frame accepted, length prefix included.

    public static final int MAX_DATAGRAM_LENGTH = 1_200; // Longest datagram either way, under common path MTUs.

    public static final int BROADCAST = 0; // Data target for every member, as session ids start at 1.
}
