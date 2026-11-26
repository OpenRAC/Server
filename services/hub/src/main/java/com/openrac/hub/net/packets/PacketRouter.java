package com.openrac.hub.net.packets;

import com.openrac.hub.net.packets.in.IncomingAccountPacket;
import com.openrac.hub.net.packets.in.IncomingHelloPacket;
import com.openrac.hub.net.packets.in.IncomingHubPacket;
import com.openrac.hub.net.packets.in.IncomingRegionsPacket;
import com.openrac.hub.net.packets.in.IncomingRegisterPacket;
import com.openrac.hub.net.packets.in.IncomingServersPacket;
import com.openrac.hub.net.packets.in.IncomingStatsPacket;
import com.openrac.hub.net.packets.in.IncomingTimePacket;
import com.openrac.hub.net.packets.model.IncomingPacket;
import com.openrac.net.packets.Packets;

import java.util.EnumMap;
import java.util.Map;

/**
 * Finds the class that handles each packet id the hub takes. A packet the hub does not take has
 * no entry.
 */

public class PacketRouter {
    private static final Map<Packets, IncomingPacket> incoming = new EnumMap<>(Packets.class);

    static {
        incoming.put(Packets.HELLO, new IncomingHelloPacket());
        incoming.put(Packets.REGISTER, new IncomingRegisterPacket());
        incoming.put(Packets.HUB, new IncomingHubPacket());
        incoming.put(Packets.REGIONS, new IncomingRegionsPacket());
        incoming.put(Packets.SERVERS, new IncomingServersPacket());
        incoming.put(Packets.ACCOUNT, new IncomingAccountPacket());
        incoming.put(Packets.STATS, new IncomingStatsPacket());
        incoming.put(Packets.TIME, new IncomingTimePacket());
    }

    /**
     * @param packet The packet id a peer sent.
     * @return What handles it, or null when the hub does not take it.
     */

    public static IncomingPacket get(Packets packet) {
        return incoming.get(packet);
    }
}
