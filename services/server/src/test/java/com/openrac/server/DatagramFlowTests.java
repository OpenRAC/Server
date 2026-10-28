package com.openrac.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.net.NetworkConstants;
import com.openrac.net.datagrams.Datagrams;
import com.openrac.net.datagrams.OutgoingDatagram;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.server.net.ServerDatagramHandler;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the server's datagram handler on an embedded channel beside TCP clients on theirs, and
 * walks binding, forwarding, the TCP fallback and the drops.
 */

public class DatagramFlowTests {
    private static final InetSocketAddress SERVER = new InetSocketAddress("192.0.2.1", 7770);

    private final EmbeddedChannel udp = new EmbeddedChannel(new ServerDatagramHandler());
    private final List<Client> clients = new ArrayList<>();

    /**
     * Disconnects every client, which also ends their parties, and closes the socket.
     */

    @AfterEach
    public void closeClients() {
        for (Client client : clients) client.channel.close();

        udp.close();
    }

    /**
     * Connects a player and says hello.
     * @param name The display name.
     * @return The greeted client.
     */

    private Client connect(String name) {
        Client client = new Client();

        clients.add(client);
        client.hello(name);

        return client;
    }

    /**
     * @param port The fake client port.
     * @return A documentation address with the port.
     */

    private static InetSocketAddress address(int port) {
        return new InetSocketAddress("198.51.100.7", port);
    }

    /**
     * Feeds a datagram to the server's socket.
     * @param datagram The datagram's bytes.
     * @param sender Where it comes from.
     */

    private void send(byte[] datagram, InetSocketAddress sender) {
        udp.writeInbound(new DatagramPacket(Unpooled.wrappedBuffer(datagram), SERVER, sender));
    }

    /**
     * Binds a client's UDP from the address.
     * @param client The client binding.
     * @param sender Where its datagrams come from.
     */

    private void bind(Client client, InetSocketAddress sender) {
        send(new OutgoingDatagram(client.id, client.token, Datagrams.BIND).toByteArray(), sender);
    }

    /**
     * Sends game state from a client over UDP.
     * @param client The client sending.
     * @param sender Where the datagram comes from.
     * @param target The target session id, or broadcast.
     * @param state The game's bytes.
     */

    private void sendState(Client client, InetSocketAddress sender, int target, byte[] state) {
        send(new OutgoingDatagram(client.id, client.token, target, state).toByteArray(), sender);
    }

    /**
     * Reads the next datagram the server sent and checks it is STATE to the recipient.
     * @param recipient Where it should go.
     * @param senderId The session it should name.
     * @return The state bytes.
     */

    private byte[] receiveState(InetSocketAddress recipient, int senderId) {
        DatagramPacket packet = udp.readOutbound();

        Assertions.assertNotNull(packet, "Expected a datagram to " + recipient + " but nothing was sent.");
        Assertions.assertEquals(recipient, packet.recipient());

        byte[] bytes = new byte[packet.content().readableBytes()];

        packet.content().readBytes(bytes);
        packet.release();

        InputBuffer payload = new InputBuffer(bytes);

        Assertions.assertEquals(Datagrams.STATE, Datagrams.get(payload.readUnsignedByte()));
        Assertions.assertEquals(senderId, payload.readInt());

        return payload.readRemainingBytes();
    }

    /**
     * Checks the server sent no datagram.
     */

    private void assertNoDatagram() {
        Assertions.assertNull(udp.readOutbound());
    }

    @Nested
    @DisplayName("Bind Tests")
    class BindTests {

        @Test
        @DisplayName("A bind is silent and routes later state to its address")
        public void testBind() {
            Client host = connect("Ratchet");
            Client member = connect("Clank");

            member.join(host.create(), 2);
            host.receive(Packets.PARTY);

            bind(member, address(40_001));
            assertNoDatagram();

            sendState(host, address(40_000), NetworkConstants.BROADCAST, new byte[] { 1 });

            Assertions.assertArrayEquals(new byte[] { 1 }, receiveState(address(40_001), host.id));
            member.assertSilent();
        }

        @Test
        @DisplayName("A session whose UDP went quiet gets state over TCP again")
        public void testQuietUdpFallsBack() {
            Client host = connect("Ratchet");
            Client member = connect("Clank");

            member.join(host.create(), 2);
            host.receive(Packets.PARTY);

            bind(member, address(40_001));

            // Its last datagram arrived longer ago than the timeout allows.
            long stale = System.nanoTime() - (Constants.UDP_TIMEOUT_SECONDS + 1) * 1_000_000_000L;

            SessionRegistry.get(member.id).heard(stale);

            sendState(host, address(40_000), NetworkConstants.BROADCAST, new byte[] { 9 });
            assertNoDatagram();

            InputBuffer data = member.receive(Packets.DATA);

            Assertions.assertEquals(host.id, data.readInt());
            Assertions.assertArrayEquals(new byte[] { 9 }, data.readRemainingBytes());

            // A fresh keepalive puts it back on UDP.
            bind(member, address(40_001));
            sendState(host, address(40_000), NetworkConstants.BROADCAST, new byte[] { 10 });

            Assertions.assertArrayEquals(new byte[] { 10 }, receiveState(address(40_001), host.id));
        }

        @Test
        @DisplayName("A wrong token, an unknown session or a short datagram binds nothing")
        public void testInvalidBinds() {
            Client host = connect("Ratchet");
            Client member = connect("Clank");

            member.join(host.create(), 2);
            host.receive(Packets.PARTY);

            send(new OutgoingDatagram(member.id, member.token + 1, Datagrams.BIND).toByteArray(), address(40_001));
            send(new OutgoingDatagram(Integer.MAX_VALUE, member.token, Datagrams.BIND).toByteArray(), address(40_001));
            send(new byte[] { 0, 0, 0, 1, 2 }, address(40_001));
            assertNoDatagram();

            // Unbound, the member still gets the host's state over TCP.
            sendState(host, address(40_000), NetworkConstants.BROADCAST, new byte[] { 2 });
            assertNoDatagram();

            InputBuffer data = member.receive(Packets.DATA);

            Assertions.assertEquals(host.id, data.readInt());
            Assertions.assertArrayEquals(new byte[] { 2 }, data.readRemainingBytes());
        }

        @Test
        @DisplayName("A valid datagram from a new port rebinds the session")
        public void testRebind() {
            Client host = connect("Ratchet");
            Client member = connect("Clank");

            member.join(host.create(), 2);
            host.receive(Packets.PARTY);

            bind(host, address(40_000));
            bind(member, address(40_001));

            sendState(host, address(40_000), member.id, new byte[] { 3 });
            Assertions.assertArrayEquals(new byte[] { 3 }, receiveState(address(40_001), host.id));

            // NAT moved the member's port.
            bind(member, address(50_001));

            sendState(host, address(40_000), member.id, new byte[] { 4 });
            Assertions.assertArrayEquals(new byte[] { 4 }, receiveState(address(50_001), host.id));
            assertNoDatagram();
        }

        @Test
        @DisplayName("A disconnected session's datagrams are dropped")
        public void testClosedSession() {
            Client host = connect("Ratchet");
            Client member = connect("Clank");

            member.join(host.create(), 2);
            host.receive(Packets.PARTY);
            bind(host, address(40_000));

            member.channel.close();
            host.receiveParty(Opcodes.Party.LEFT);

            sendState(member, address(40_001), NetworkConstants.BROADCAST, new byte[] { 5 });
            assertNoDatagram();
            host.assertSilent();
        }
    }

    @Nested
    @DisplayName("State Tests")
    class StateTests {

        @Test
        @DisplayName("A member's state goes to the host alone, over UDP")
        public void testMemberToHost() {
            Client host = connect("Ratchet");
            Client first = connect("Clank");
            Client second = connect("Qwark");
            String code = host.create();

            first.join(code, 2);
            host.receive(Packets.PARTY);
            second.join(code, 3);
            host.receive(Packets.PARTY);
            first.receive(Packets.PARTY);

            bind(host, address(40_000));
            bind(second, address(40_002));

            // The target is ignored, as members only reach the host.
            sendState(first, address(40_001), second.id, new byte[] { 6, 7 });

            Assertions.assertArrayEquals(new byte[] { 6, 7 }, receiveState(address(40_000), first.id));
            assertNoDatagram();
            host.assertSilent();
            second.assertSilent();
        }

        @Test
        @DisplayName("The host's broadcast reaches a UDP member by UDP and a TCP member by TCP")
        public void testBroadcastFallback() {
            Client host = connect("Ratchet");
            Client udpMember = connect("Clank");
            Client tcpMember = connect("Qwark");
            String code = host.create();

            udpMember.join(code, 2);
            host.receive(Packets.PARTY);
            tcpMember.join(code, 3);
            host.receive(Packets.PARTY);
            udpMember.receive(Packets.PARTY);

            bind(udpMember, address(40_001));
            sendState(host, address(40_000), NetworkConstants.BROADCAST, new byte[] { 8 });

            Assertions.assertArrayEquals(new byte[] { 8 }, receiveState(address(40_001), host.id));
            assertNoDatagram();
            udpMember.assertSilent();

            InputBuffer data = tcpMember.receive(Packets.DATA);

            Assertions.assertEquals(host.id, data.readInt());
            Assertions.assertArrayEquals(new byte[] { 8 }, data.readRemainingBytes());
            host.assertSilent();
        }

        @Test
        @DisplayName("An oversized datagram is dropped")
        public void testOversized() {
            Client host = connect("Ratchet");
            Client member = connect("Clank");

            member.join(host.create(), 2);
            host.receive(Packets.PARTY);
            bind(host, address(40_000));

            // One byte over the limit, header included.
            byte[] state = new byte[NetworkConstants.MAX_DATAGRAM_LENGTH - 16];

            sendState(member, address(40_001), NetworkConstants.BROADCAST, state);
            assertNoDatagram();
            host.assertSilent();

            // At the limit it goes through.
            sendState(member, address(40_001), NetworkConstants.BROADCAST, new byte[state.length - 1]);
            Assertions.assertEquals(state.length - 1, receiveState(address(40_000), member.id).length);
        }

        @Test
        @DisplayName("State past the session's rate is dropped, and the session stays")
        public void testRateLimit() {
            Client host = connect("Ratchet");
            Client member = connect("Clank");

            member.join(host.create(), 2);
            host.receive(Packets.PARTY);
            bind(host, address(40_000));

            // Twice the burst at once: the bucket refills far slower than this.
            int sent = Constants.DATA_BURST * 2;

            for (int i = 0; i < sent; i++) sendState(member, address(40_001), NetworkConstants.BROADCAST, new byte[] { 1 });

            int forwarded = 0;

            for (DatagramPacket packet = udp.readOutbound(); packet != null; packet = udp.readOutbound()) {
                forwarded++;
                packet.release();
            }

            Assertions.assertTrue(forwarded >= Constants.DATA_BURST);
            Assertions.assertTrue(forwarded < sent);
            Assertions.assertTrue(member.channel.isActive());
            host.assertSilent();
        }
    }
}
