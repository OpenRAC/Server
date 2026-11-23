package com.openrac.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.hub.Hub;
import com.openrac.hub.accounts.AccountController;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.AccountPacket;
import com.openrac.net.packets.out.RegionsPacket;
import com.openrac.net.packets.out.ServersPacket;
import com.openrac.server.hub.HubController;
import com.openrac.server.net.ServerChannelInitializer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs a hub and a game server on real sockets on this machine, the game server linked to the hub
 * as a region is: registration, heartbeats, closing, and a hub login proven at the game server.
 */

public class NetworkFlowTests {
    private static final String FAMILY = "rac3-pal-1";
    private static final long WAIT_MILLIS = 15_000;

    private final List<SocketClient> clients = new ArrayList<>();
    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
    private final Hub hub = new Hub();

    private int hubPort;
    private int gamePort;
    private Channel gameChannel;

    /**
     * Starts the hub, then the game server and its link to the hub.
     * @throws InterruptedException If binding a port is interrupted.
     */

    @BeforeEach
    public void start() throws InterruptedException {
        hubPort = findFreePort();
        gamePort = findFreePort();

        hub.start("127.0.0.1", hubPort);

        gameChannel = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ServerChannelInitializer())
                .bind("127.0.0.1", gamePort)
                .sync()
                .channel();

        HubController.start(group, "127.0.0.1:" + hubPort);
    }

    /**
     * Closes the clients, the game server and the hub, and forgets wrong passwords.
     */

    @AfterEach
    public void stop() {
        for (SocketClient client : clients) client.close();

        HubController.stop();
        gameChannel.close().syncUninterruptibly();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        hub.stop();
        AccountController.sweep();
    }

    /**
     * @return A port nothing listens on right now.
     */

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * @param port The port to connect to.
     * @return A new connection, closed after the test.
     */

    private SocketClient connect(int port) {
        SocketClient client = new SocketClient(port);

        clients.add(client);

        return client;
    }

    /**
     * Asks the hub for its regions until one is listed.
     * @param client A client that said hello to the hub.
     * @return The REGIONS payload after its count, which is one.
     * @throws InterruptedException If the wait is interrupted.
     */

    private static InputBuffer awaitListed(SocketClient client) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;

        while (true) {
            client.send(new RegionsPacket(FAMILY));

            InputBuffer regions = client.receive(Packets.REGIONS);
            int count = regions.readUnsignedByte();

            // The link registers on its own thread, so the list may lag.
            if (count == 1) return regions;

            Assertions.assertTrue(System.currentTimeMillis() < deadline, "The game server never registered.");
            Thread.sleep(500);
        }
    }

    /**
     * Logs in at the hub, registering the name.
     * @param client A client that said hello to the hub.
     * @param name The account name.
     * @param password The password.
     * @return The LOGGED_IN payload after its opcode.
     */

    private static InputBuffer login(SocketClient client, String name, String password) {
        client.send(new AccountPacket(name, password));

        InputBuffer payload = client.receive(Packets.ACCOUNT);

        Assertions.assertEquals(Opcodes.Account.LOGGED_IN, Opcodes.Account.get(payload.readUnsignedByte()));

        return payload;
    }

    /**
     * @return An account name no other test registered.
     */

    private static String newName() {
        return "N" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Nested
    @DisplayName("Link Tests")
    class LinkTests {

        @Test
        @DisplayName("A game server registers with the hub and is listed under its region")
        public void testRegistered() throws InterruptedException {
            SocketClient player = connect(hubPort);

            player.hello("Ratchet");

            InputBuffer regions = awaitListed(player);

            Assertions.assertEquals(Constants.REGION.name(), regions.readString());
            Assertions.assertEquals("", regions.readString());
            Assertions.assertEquals(Constants.PORT, regions.readInt());
            Assertions.assertEquals(SessionRegistry.getCount(), regions.readInt());
        }

        @Test
        @DisplayName("The heartbeat carries the game server's player count to the hub's watchers")
        public void testHeartbeat() throws InterruptedException {
            SocketClient watcher = connect(hubPort);

            watcher.hello("Clank");
            awaitListed(watcher);
            watcher.send(new ServersPacket(FAMILY));

            InputBuffer added = watcher.receive(Packets.SERVERS);

            Assertions.assertEquals(Opcodes.Servers.ADDED, Opcodes.Servers.get(added.readUnsignedByte()));

            int serverId = added.readInt();

            added.readString();
            added.readString();
            added.readString();
            added.readInt();

            int players = added.readInt();

            connect(gamePort).hello("Ratchet");

            InputBuffer changed = watcher.receive(Packets.SERVERS);

            Assertions.assertEquals(Opcodes.Servers.CHANGED, Opcodes.Servers.get(changed.readUnsignedByte()));
            Assertions.assertEquals(serverId, changed.readInt());
            Assertions.assertEquals(players + 1, changed.readInt());
        }

        @Test
        @DisplayName("A closing game server leaves the hub's list at once")
        public void testClosing() throws InterruptedException {
            SocketClient watcher = connect(hubPort);

            watcher.hello("Clank");
            awaitListed(watcher);
            watcher.send(new ServersPacket(FAMILY));

            InputBuffer added = watcher.receive(Packets.SERVERS);

            Assertions.assertEquals(Opcodes.Servers.ADDED, Opcodes.Servers.get(added.readUnsignedByte()));

            int serverId = added.readInt();

            HubController.stop();

            InputBuffer removed = watcher.receive(Packets.SERVERS);

            Assertions.assertEquals(Opcodes.Servers.REMOVED, Opcodes.Servers.get(removed.readUnsignedByte()));
            Assertions.assertEquals(serverId, removed.readInt());
        }
    }

    @Nested
    @DisplayName("Account Tests")
    class AccountTests {

        @Test
        @DisplayName("A hub login is proven at the game server through the server's link")
        public void testToken() throws InterruptedException {
            String name = newName();
            SocketClient atHub = connect(hubPort);

            atHub.hello(name);
            awaitListed(atHub);

            InputBuffer loggedIn = login(atHub, name, "lombax");
            long accountId = loggedIn.readLong();

            loggedIn.readString();

            String token = loggedIn.readString();
            SocketClient atServer = connect(gamePort);

            atServer.hello(name);
            atServer.send(new AccountPacket(Opcodes.Account.TOKEN, token));

            InputBuffer proven = atServer.receive(Packets.ACCOUNT);

            Assertions.assertEquals(Opcodes.Account.LOGGED_IN, Opcodes.Account.get(proven.readUnsignedByte()));
            Assertions.assertEquals(accountId, proven.readLong());
            Assertions.assertEquals(name, proven.readString());
        }

        @Test
        @DisplayName("A token the hub never handed out is refused at the game server")
        public void testUnknownToken() throws InterruptedException {
            SocketClient atHub = connect(hubPort);

            atHub.hello("Qwark");
            awaitListed(atHub);

            SocketClient atServer = connect(gamePort);

            atServer.hello("Qwark");
            atServer.send(new AccountPacket(Opcodes.Account.TOKEN, "not-a-token"));

            Assertions.assertEquals(Opcodes.Error.SESSION_INVALID, atServer.receiveError());
        }

        @Test
        @DisplayName("An address past its wrong passwords is told to try later, even with the right one")
        public void testLoginFailures() {
            String name = newName();
            SocketClient owner = connect(hubPort);

            owner.hello(name);
            login(owner, name, "lombax");

            SocketClient guesser = connect(hubPort);

            guesser.hello("Nefarious");

            for (int i = 0; i < com.openrac.hub.Constants.LOGIN_FAILURES; i++) {
                guesser.send(new AccountPacket(name, "guess" + i));
                Assertions.assertEquals(Opcodes.Error.WRONG_PASSWORD, guesser.receiveError());
            }

            guesser.send(new AccountPacket(name, "lombax"));

            Assertions.assertEquals(Opcodes.Error.TRY_LATER, guesser.receiveError());
        }
    }
}
