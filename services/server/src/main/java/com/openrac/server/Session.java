package com.openrac.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;

import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.net.packets.out.DataPacket;
import com.openrac.server.limits.RateLimit;
import com.openrac.server.party.Party;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A player's connection after its greeting: the session id the server assigned, the display name
 * and the party it is in, if any. It also holds the token and address of its optional UDP link.
 */

public class Session {
    // Starts at 1 because 0 is the broadcast target.
    private static final AtomicInteger nextId = new AtomicInteger(1);

    // The token is all that ties a datagram to its session.
    private static final SecureRandom random = new SecureRandom();

    private final int id;
    private final String name;
    private final Channel channel;

    // Read from other members' threads, written under the party lock.
    private volatile Party party;

    private final long token = random.nextLong(); // Proves a datagram came from this session's client.
    private final RateLimit stateLimit = new RateLimit(Constants.DATA_BURST, Constants.DATA_PER_SECOND); // Datagram loop only.

    private volatile InetSocketAddress udpAddress; // Null until a valid datagram arrives.

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
     * Sends game state over UDP once this session has bound an address, otherwise over TCP.
     * @param udp The server's datagram channel.
     * @param datagram The encoded STATE datagram.
     * @param fallback The same state as a data packet.
     */

    public void sendState(Channel udp, byte[] datagram, DataPacket fallback) {
        InetSocketAddress address = udpAddress;

        // A client with blocked UDP never binds, so stays on TCP.
        if (address == null) {
            send(fallback);
            return;
        }

        udp.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(datagram), address));
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
     * @return The secret token the client puts in each datagram.
     */

    public long getToken() {
        return token;
    }

    /**
     * @return The limit on game state arriving over UDP.
     */

    public RateLimit getStateLimit() {
        return stateLimit;
    }

    /**
     * @return Where this session's datagrams come from, or null.
     */

    public InetSocketAddress getUdpAddress() {
        return udpAddress;
    }

    /**
     * Sets the party this session is in.
     * @param party The party, or null when it leaves one.
     */

    public void setParty(Party party) {
        this.party = party;
    }

    /**
     * Sets where datagrams for this session go.
     * @param udpAddress The address its last valid datagram came from.
     */

    public void setUdpAddress(InetSocketAddress udpAddress) {
        this.udpAddress = udpAddress;
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
