package com.openrac.hub.servers;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

import com.openrac.hub.Constants;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.net.packets.out.RegionsPacket;
import com.openrac.net.packets.out.ServersPacket;
import com.openrac.utils.logger.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The hub's list of game servers. Servers register over their own connection, report their counts
 * on it every few seconds, and leave the list when it closes or goes quiet. Clients that watch the
 * list hear of each change as it happens.
 */

public class ServerController {
    // Kept in registration order, so the list reads the same every time.
    private static final Map<Integer, GameServer> servers = new LinkedHashMap<>();

    // Closed channels leave the group on their own.
    private static final ChannelGroup watchers = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    private static int nextId = 1; // Under the class lock.

    /**
     * Lists a server that registered, and tells the watchers.
     * @param channel The server's connection.
     * @param region The code of the region players pick it by.
     * @param name Its own name, empty for none.
     * @param host The address players reach it on, empty for the hub's.
     * @param port Its game port.
     * @param players How many players it has.
     * @param now The time, as Clock.now().
     * @return The listed server, or null when the list is full.
     */

    public static synchronized GameServer add(Channel channel, String region, String name, String host, int port, int players, long now) {
        // The list is bounded like every other the hub sends.
        if (servers.size() >= Constants.MAX_SERVERS) return null;

        GameServer server = new GameServer(nextId++, channel, region, name, host, port, players, now);

        servers.put(server.getId(), server);
        watchers.writeAndFlush(new ServersPacket(Opcodes.Servers.ADDED, server.serialize()));

        // A server without a name is known by its region alone.
        String title = name.isEmpty() ? "" : " (" + name + ")";

        Log.info("Server " + server.getId() + title + " registered in region " + region + ".");

        return server;
    }

    /**
     * Takes a server's heartbeat, and tells the watchers when its counts changed.
     * @param server The server reporting.
     * @param players How many players it has now.
     * @param parties How many parties it runs now.
     * @param now The time, as Clock.now().
     */

    public static synchronized void status(GameServer server, int players, int parties, long now) {
        // A status may still arrive after the sweep dropped the server.
        if (servers.get(server.getId()) != server) return;

        // Only a change is worth a packet to every watcher.
        if (server.setStatus(players, parties, now)) watchers.writeAndFlush(new ServersPacket(Opcodes.Servers.CHANGED, server.serializeCounts()));
    }

    /**
     * Takes a server off the list, and tells the watchers.
     * @param server The server leaving.
     */

    public static synchronized void remove(GameServer server) {
        // Closing after CLOSING, or after the sweep, removes it twice.
        if (servers.remove(server.getId()) == null) return;

        watchers.writeAndFlush(new ServersPacket(server.getId()));

        Log.info("Server " + server.getId() + " left region " + server.getRegion() + ".");
    }

    /**
     * Drops every server that has been silent too long. A machine that dies closes nothing.
     * @param now The time, as Clock.now().
     */

    public static synchronized void sweep(long now) {
        List<GameServer> silent = new ArrayList<>();

        for (GameServer server : servers.values()) {
            // Servers report every few seconds, so long silence means gone.
            if (server.getSilence(now) > Constants.SERVER_TIMEOUT_SECONDS * 1000L) silent.add(server);
        }

        for (GameServer server : silent) {
            Log.warning("Server " + server.getId() + " went quiet, dropping it.");

            remove(server);
            server.close();
        }
    }

    /**
     * Sends a client every listed server, then each change from now on.
     * @param channel The client's connection.
     */

    public static synchronized void watch(Channel channel) {
        for (GameServer server : servers.values()) channel.write(new ServersPacket(Opcodes.Servers.ADDED, server.serialize()));

        channel.flush();

        // Under the lock, so no change slips between snapshot and notices.
        watchers.add(channel);
    }

    /**
     * @return The REGIONS answer: every listed server, as a region players can pick, then their names.
     */

    public static synchronized OutgoingPacket getRegionsPacket() {
        OutputBuffer listing = new OutputBuffer();
        OutputBuffer names = new OutputBuffer();

        for (GameServer server : servers.values()) {
            listing.append(server.serializeRegion());
            names.writeString(server.getName());
        }

        return new RegionsPacket(servers.size(), listing, names);
    }

    /**
     * @return How many servers are listed.
     */

    public static synchronized int getCount() {
        return servers.size();
    }
}
