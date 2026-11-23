package com.openrac.hub.servers;

import io.netty.channel.Channel;

import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;

/**
 * A game server the hub lists: it runs lobbies and game rooms in one region, and players connect
 * to it after the hub. Its own connection carries its heartbeat, and the hub drops it when that
 * connection closes or goes quiet.
 */

public class GameServer {
    private final int id;
    private final Channel channel;

    private final String region; // A Regions code.
    private final String name; // Empty: clients show the region alone.
    private final String host; // Empty: the address the player reached the hub on.
    private final int port;

    // Its event loop writes these, the sweeper and list read them.
    private volatile int players;
    private volatile int parties;
    private volatile long heardAt; // Clock.now() of the last registration or status.

    /**
     * Creates a listed server.
     * @param id Its id in the hub's list.
     * @param channel Its connection to the hub.
     * @param region The code of the region players pick it by, such as "US".
     * @param name Its own name, which tells one region's servers apart, empty for none.
     * @param host The address players reach it on, empty for the hub's.
     * @param port Its game port.
     * @param players How many players it has.
     * @param now The time of registration, as Clock.now().
     */

    public GameServer(int id, Channel channel, String region, String name, String host, int port, int players, long now) {
        this.id = id;
        this.channel = channel;
        this.region = region;
        this.name = name;
        this.host = host;
        this.port = port;
        this.players = players;
        this.heardAt = now;
    }

    /**
     * Sends a packet down the server's connection.
     * @param packet The packet to send.
     */

    public void send(OutgoingPacket packet) {
        channel.writeAndFlush(packet);
    }

    /**
     * Closes the server's connection, which takes it off the list.
     */

    public void close() {
        channel.close();
    }

    /**
     * @return Its id in the hub's list.
     */

    public int getId() {
        return id;
    }

    /**
     * @return Its own name, which tells one region's servers apart.
     */

    public String getName() {
        return name;
    }

    /**
     * @return The region players pick it by.
     */

    public String getRegion() {
        return region;
    }

    /**
     * @param now The time, as Clock.now().
     * @return How long the server has been silent, in milliseconds.
     */

    public long getSilence(long now) {
        return now - heardAt;
    }

    /**
     * Takes a heartbeat.
     * @param players How many players it has now.
     * @param parties How many parties it runs now.
     * @param now The time, as Clock.now().
     * @return Whether either count changed.
     */

    public boolean setStatus(int players, int parties, long now) {
        boolean changed = players != this.players || parties != this.parties;

        this.players = players;
        this.parties = parties;
        this.heardAt = now;

        return changed;
    }

    /**
     * Writes the server as the REGIONS list shows it.
     * @return Its region, host, port and players.
     */

    public OutputBuffer serializeRegion() {
        OutputBuffer buffer = new OutputBuffer();

        buffer.writeString(region);
        buffer.writeString(host);
        buffer.writeInt(port);
        buffer.writeInt(players);

        return buffer;
    }

    /**
     * Writes the counts a SERVERS CHANGED carries.
     * @return Its id, players and parties.
     */

    public OutputBuffer serializeCounts() {
        OutputBuffer buffer = new OutputBuffer();

        buffer.writeInt(id);
        buffer.writeInt(players);
        buffer.writeInt(parties);

        return buffer;
    }

    /**
     * Writes the server as a SERVERS ADDED describes it.
     * @return Its id, region, name, host, port, players and parties.
     */

    public OutputBuffer serialize() {
        OutputBuffer buffer = new OutputBuffer();

        buffer.writeInt(id);
        buffer.writeString(region);
        buffer.writeString(name);
        buffer.writeString(host);
        buffer.writeInt(port);
        buffer.writeInt(players);
        buffer.writeInt(parties);

        return buffer;
    }
}
