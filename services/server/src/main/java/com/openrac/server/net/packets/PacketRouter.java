package com.openrac.server.net.packets;

import io.netty.channel.ChannelHandlerContext;

import com.openrac.net.packets.Packets;
import com.openrac.server.net.packets.game.in.IncomingAccountPacket;
import com.openrac.server.net.packets.game.in.IncomingChatPacket;
import com.openrac.server.net.packets.game.in.IncomingDataPacket;
import com.openrac.server.net.packets.game.in.IncomingHelloPacket;
import com.openrac.server.net.packets.game.in.IncomingPartyPacket;
import com.openrac.server.net.packets.game.in.IncomingRelayPacket;
import com.openrac.server.net.packets.game.in.IncomingTimePacket;
import com.openrac.server.net.packets.game.in.IncomingUiPacket;
import com.openrac.server.net.packets.hub.in.IncomingErrorPacket;
import com.openrac.server.net.packets.hub.in.IncomingHubPacket;
import com.openrac.server.net.packets.model.IncomingPacket;

import java.util.EnumMap;
import java.util.Map;

/**
 * Finds the class that handles each packet id, for players' connections and for the link to the
 * hub. A packet a side does not take has no entry.
 */

public class PacketRouter {
    private static final Map<Packets, IncomingPacket> game = new EnumMap<>(Packets.class);
    private static final Map<Packets, com.openrac.net.packets.model.IncomingPacket<ChannelHandlerContext>> hub = new EnumMap<>(Packets.class);

    static {
        game.put(Packets.HELLO, new IncomingHelloPacket());
        game.put(Packets.PARTY, new IncomingPartyPacket());
        game.put(Packets.CHAT, new IncomingChatPacket());
        game.put(Packets.DATA, new IncomingDataPacket());
        game.put(Packets.RELAY, new IncomingRelayPacket());
        game.put(Packets.ACCOUNT, new IncomingAccountPacket());
        game.put(Packets.TIME, new IncomingTimePacket());
        game.put(Packets.UI, new IncomingUiPacket());

        hub.put(Packets.HUB, new IncomingHubPacket());
        hub.put(Packets.ERROR, new IncomingErrorPacket());
    }

    /**
     * @param packet The packet id a player's client sent.
     * @return What handles it, or null when a game server does not take it.
     */

    public static IncomingPacket getGame(Packets packet) {
        return game.get(packet);
    }

    /**
     * @param packet The packet id the hub sent.
     * @return What handles it, or null when a game server does not take it from the hub.
     */

    public static com.openrac.net.packets.model.IncomingPacket<ChannelHandlerContext> getHub(Packets packet) {
        return hub.get(packet);
    }
}
