package com.openrac.server;

import io.netty.channel.Channel;

import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.server.party.Party;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A player's connection after its greeting: the session id the server assigned, the display name
 * and the party it is in, if any.
 */

public class Session {
    // Starts at 1 because 0 is the broadcast target.
    private static final AtomicInteger nextId = new AtomicInteger(1);

    private final int id;
    private final String name;
    private final Channel channel;

    // Read from other members' threads, written under the party lock.
    private volatile Party party;

    /**
     * Creates a session with the next free id.
     * @param channel The connection's channel.
     * @param name The player's display name.
     */

    public Session(Channel channel, String name) {
        this.id = nextId.getAndIncrement();
        this.name = name;
        this.channel = channel;
    }

    /**
     * Writes and flushes a packet to this session's connection.
     * @param packet The packet to send.
     */

    public void send(OutgoingPacket packet) {
        channel.writeAndFlush(packet);
    }

    /**
     * @return The session id.
     */

    public int getId() {
        return id;
    }

    /**
     * @return The player's display name.
     */

    public String getName() {
        return name;
    }

    /**
     * @return The party this session is in, or null.
     */

    public Party getParty() {
        return party;
    }

    /**
     * Sets the party this session is in.
     * @param party The party, or null when it leaves one.
     */

    public void setParty(Party party) {
        this.party = party;
    }

    /**
     * Writes the session as party members see it.
     * @return The session id followed by the display name.
     */

    public OutputBuffer serialize() {
        OutputBuffer buffer = new OutputBuffer();

        buffer.writeInt(id);
        buffer.writeString(name);

        return buffer;
    }
}
