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
    private volatile Channel channel; // Replaced when the player resumes.

    // Read from other members' threads, written under the party lock.
    private volatile Party party;

    private final long token = random.nextLong(); // Proves a datagram came from this session's client.
    private final long resumeToken = random.nextLong(); // Proves a RESUME came from this session's client.
    private final RateLimit stateLimit = new RateLimit(Constants.DATA_BURST, Constants.DATA_PER_SECOND); // Datagram loop only.

    private volatile InetSocketAddress udpAddress; // Null until a valid datagram arrives.
    private volatile long udpHeard; // System.nanoTime() of the last valid datagram.

    private final boolean resumes; // This client comes back with RESUME.
    private final boolean readsSlots; // This client understands SLOTS.
    private volatile boolean away; // Its connection dropped and its slot waits for it.
    private volatile long awayAt; // Clock.now() of the drop.
    private volatile long heardAt = Clock.now(); // Clock.now() of the last packet or datagram.

    /**
     * Creates a session with the next free id.
     * @param channel The connection's channel.
     * @param name The player's display name.
     * @param resumes Whether the client said it comes back with RESUME.
     * @param readsSlots Whether the client said it reads SLOTS.
     */

    public Session(Channel channel, String name, boolean resumes, boolean readsSlots) {
        this.id = nextId.getAndIncrement();
        this.name = name;
        this.channel = channel;
        this.resumes = resumes;
        this.readsSlots = readsSlots;
    }

    /**
     * Writes and flushes a packet to this session's connection.
     * @param packet The packet to send.
     */

    public void send(OutgoingPacket packet) {
        // Nothing reaches a connection that has gone.
        if (away) return;

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

        // Blocked UDP never binds, and a quiet one may have broken.
        if (address == null || System.nanoTime() - udpHeard > Constants.UDP_TIMEOUT_SECONDS * 1_000_000_000L) {
            send(fallback);
            return;
        }

        udp.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(datagram), address));
    }

    /**
     * Marks the connection gone, keeping the session for its client to come back to.
     * @param now The time of the drop, as Clock.now().
     */

    public void markAway(long now) {
        away = true;
        awayAt = now;
    }

    /**
     * Moves the session to a new connection, which starts out on TCP.
     * @param channel The connection that proved it holds this session.
     * @return The connection it had.
     */

    public Channel rebind(Channel channel) {
        Channel old = this.channel;

        this.channel = channel;
        udpAddress = null;
        away = false;
        heardAt = Clock.now();

        return old;
    }

    /**
     * Notes that something came from the client.
     */

    public void touch() {
        heardAt = Clock.now();
    }

    /**
     * Closes the connection of a client that went quiet.
     */

    public void close() {
        channel.close();
    }

    /**
     * @param channel A connection.
     * @return Whether the session is on that connection.
     */

    public boolean isOn(Channel channel) {
        return this.channel == channel;
    }

    /**
     * @return The connection this session is on.
     */

    public Channel getChannel() {
        return channel;
    }

    /**
     * @return Whether the client comes back with RESUME.
     */

    public boolean resumes() {
        return resumes;
    }

    /**
     * @return Whether the client reads SLOTS.
     */

    public boolean readsSlots() {
        return readsSlots;
    }

    /**
     * @return Whether the connection dropped and the slot waits.
     */

    public boolean isAway() {
        return away;
    }

    /**
     * @return When the connection dropped, as Clock.now().
     */

    public long getAwayAt() {
        return awayAt;
    }

    /**
     * @return When the client was last heard from, as Clock.now().
     */

    public long getHeardAt() {
        return heardAt;
    }

    /**
     * @return The secret token that takes this session back.
     */

    public long getResumeToken() {
        return resumeToken;
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
     * @param heard When that datagram arrived, as System.nanoTime().
     */

    public void setUdpAddress(InetSocketAddress udpAddress, long heard) {
        this.udpAddress = udpAddress;
        this.udpHeard = heard;
    }

    /**
     * Notes a valid datagram from the bound address, keeping UDP in use.
     * @param heard When it arrived, as System.nanoTime().
     */

    public void heard(long heard) {
        this.udpHeard = heard;
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
