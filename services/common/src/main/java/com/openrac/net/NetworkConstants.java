package com.openrac.net;

/**
 * Values both ends of the wire have to agree on.
 */

public class NetworkConstants {
    public static final int LENGTH_FIELD_LENGTH = 4; // Bytes in the length prefix ahead of each packet.
    public static final int MAX_FRAME_LENGTH = 65_536; // Longest frame accepted, length prefix included.

    public static final int MAX_DATAGRAM_LENGTH = 1_200; // Longest datagram either way, under common path MTUs.

    public static final int BROADCAST = 0; // Data target for every member, as session ids start at 1.

    public static final int FEATURE_KICK = 1; // UDP offer's feature bit: the server takes KICK.
    public static final int FEATURE_RESUME = 2; // UDP offer's feature bit: the server takes RESUME.
    public static final int FEATURE_ADVERT = 4; // UDP offer's feature bit: the server takes ADVERT.
    public static final int FEATURE_RELAY = 8; // UDP offer's feature bit: the server takes RELAY.
    public static final int FEATURE_REGIONS = 16; // UDP offer's feature bit: the server answers REGIONS.
    public static final int FEATURE_SERVERS = 64; // UDP offer's feature bit: the hub streams SERVERS.

    public static final int LIST_ADVERTS = 1; // LIST request's flag bit: each listing ends with its advert.

    public static final int CLIENT_RESUMES = 1; // HELLO's client bit: this client comes back with RESUME.
    public static final int CLIENT_SLOTS = 2; // HELLO's client bit: this client reads SLOTS.
    public static final int CLIENT_KEEPS_ALIVE = 4; // HELLO's client bit: this client sends TIME every few seconds.
}
