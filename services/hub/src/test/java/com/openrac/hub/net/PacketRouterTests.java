package com.openrac.hub.net;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.hub.Constants;
import com.openrac.hub.net.packets.PacketRouter;
import com.openrac.net.EmbeddedPeer;
import com.openrac.net.limits.RateLimit;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.out.TimePacket;

import java.util.EnumSet;
import java.util.Set;

/**
 * Checks which packets the hub takes, what each requires, and how often a client may send them.
 */

public class PacketRouterTests {
    private static final Set<Packets> TAKEN = EnumSet.of(Packets.HELLO, Packets.REGISTER, Packets.HUB, Packets.REGIONS, Packets.SERVERS,
            Packets.ACCOUNT, Packets.STATS, Packets.TIME);

    @Nested
    @DisplayName("Registration Tests")
    class RegistrationTests {
        @Test
        @DisplayName("Every packet the hub takes is registered, and no other")
        public void testRegistered() {
            for (Packets packet : Packets.values()) Assertions.assertEquals(TAKEN.contains(packet), PacketRouter.get(packet) != null, packet.name());
        }
    }

    @Nested
    @DisplayName("Strict Tests")
    class StrictTests {
        @Test
        @DisplayName("Only hello and register are taken from a stranger")
        public void testStrict() {
            for (Packets packet : TAKEN) {
                boolean introduction = packet == Packets.HELLO || packet == Packets.REGISTER;

                Assertions.assertEquals(!introduction, PacketRouter.get(packet).isStrict(), packet.name());
            }
        }

        @Test
        @DisplayName("A strict packet before hello closes the connection")
        public void testRefused() {
            EmbeddedPeer peer = new EmbeddedPeer(new HubChannelInitializer());

            peer.send(new TimePacket(1));

            Assertions.assertFalse(peer.channel.isActive());
        }
    }

    @Nested
    @DisplayName("Rate Limit Tests")
    class RateLimitTests {
        @Test
        @DisplayName("A client's burst past the bucket is refused")
        public void testBurst() {
            Connection connection = newConnection();

            connection.setGuest(true);

            RateLimit limit = PacketRouter.get(Packets.TIME).getRateLimit(connection);

            for (int i = 0; i < Constants.REQUEST_BURST; i++) Assertions.assertTrue(limit.take());

            Assertions.assertFalse(limit.take());
        }

        @Test
        @DisplayName("A game server's packets are not limited")
        public void testServer() {
            Assertions.assertNull(PacketRouter.get(Packets.HUB).getRateLimit(newConnection()));
        }

        private Connection newConnection() {
            EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());

            return new Connection(channel.pipeline().firstContext());
        }
    }
}
