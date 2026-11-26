package com.openrac.server.net;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.net.EmbeddedPeer;
import com.openrac.net.limits.RateLimit;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.out.ChatPacket;
import com.openrac.server.Constants;
import com.openrac.server.net.packets.PacketRouter;
import com.openrac.server.net.packets.model.IncomingPacket;

import java.util.EnumSet;
import java.util.Set;

/**
 * Checks which packets a game server takes, what each requires, and how often a connection may send them.
 */

public class PacketRouterTests {
    private static final Set<Packets> FROM_PLAYERS = EnumSet.of(Packets.HELLO, Packets.PARTY, Packets.CHAT, Packets.DATA, Packets.RELAY,
            Packets.ACCOUNT, Packets.TIME, Packets.UI);
    private static final Set<Packets> FROM_HUB = EnumSet.of(Packets.HUB, Packets.ERROR);

    @Nested
    @DisplayName("Registration Tests")
    class RegistrationTests {
        @Test
        @DisplayName("Every packet a player may send is registered, and no other")
        public void testGame() {
            for (Packets packet : Packets.values()) Assertions.assertEquals(FROM_PLAYERS.contains(packet), PacketRouter.getGame(packet) != null, packet.name());
        }

        @Test
        @DisplayName("Every packet the hub may send is registered, and no other")
        public void testHub() {
            for (Packets packet : Packets.values()) Assertions.assertEquals(FROM_HUB.contains(packet), PacketRouter.getHub(packet) != null, packet.name());
        }
    }

    @Nested
    @DisplayName("Strict Tests")
    class StrictTests {
        @Test
        @DisplayName("Only the greeting is taken before login")
        public void testStrict() {
            for (Packets packet : FROM_PLAYERS) Assertions.assertEquals(packet != Packets.HELLO, PacketRouter.getGame(packet).isStrict(), packet.name());
        }

        @Test
        @DisplayName("A strict packet before hello closes the connection")
        public void testRefused() {
            EmbeddedPeer peer = new EmbeddedPeer(new ServerChannelInitializer());

            peer.send(new ChatPacket("hi"));

            Assertions.assertFalse(peer.channel.isActive());
        }
    }

    @Nested
    @DisplayName("Rate Limit Tests")
    class RateLimitTests {
        @Test
        @DisplayName("A burst past the bucket is refused")
        public void testBurst() {
            Connection connection = newConnection();

            assertBurst(PacketRouter.getGame(Packets.CHAT), connection, Constants.CHAT_BURST);
            assertBurst(PacketRouter.getGame(Packets.PARTY), connection, Constants.PARTY_BURST);
            assertBurst(PacketRouter.getGame(Packets.DATA), connection, Constants.DATA_BURST);
            assertBurst(PacketRouter.getGame(Packets.RELAY), connection, Constants.RELAY_BURST);
            assertBurst(PacketRouter.getGame(Packets.TIME), connection, Constants.TIME_BURST);
            assertBurst(PacketRouter.getGame(Packets.UI), connection, Constants.UI_BURST);
        }

        @Test
        @DisplayName("The greeting is not limited")
        public void testHello() {
            Assertions.assertNull(PacketRouter.getGame(Packets.HELLO).getRateLimit(newConnection()));
        }

        @Test
        @DisplayName("Account tokens share the party bucket")
        public void testAccount() {
            Connection connection = newConnection();

            Assertions.assertSame(connection.getPartyLimit(), PacketRouter.getGame(Packets.ACCOUNT).getRateLimit(connection));
        }

        private Connection newConnection() {
            EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());

            return new Connection(channel.pipeline().firstContext());
        }

        private void assertBurst(IncomingPacket packet, Connection connection, int burst) {
            RateLimit limit = packet.getRateLimit(connection);

            for (int i = 0; i < burst; i++) Assertions.assertTrue(limit.take());

            Assertions.assertFalse(limit.take());
        }
    }
}
