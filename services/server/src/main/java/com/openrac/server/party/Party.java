package com.openrac.server.party;

import com.openrac.net.NetworkConstants;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.server.Constants;
import com.openrac.server.Session;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A group of players linked under one host. The host's game is authoritative, so members' game
 * data goes to the host and the host's goes to the members. Membership only changes through
 * {@link PartyController}, while sends iterate a snapshot from any thread.
 */

public class Party {
    private final String code;
    private final Session host;
    private final String description;

    private final List<Session> members = new CopyOnWriteArrayList<>();
    private final Set<Integer> kicked = new HashSet<>(); // Only touched under the controller's lock.

    /**
     * Creates a party with its host as the first member.
     * @param code The join code.
     * @param host The session that created the party.
     * @param description The sanitised text shown in party lists, empty for none.
     */

    public Party(String code, Session host, String description) {
        this.code = code;
        this.host = host;
        this.description = description;

        members.add(host);
    }

    /**
     * Sends a packet to every member.
     * @param packet The packet to send.
     */

    public void broadcast(OutgoingPacket packet) {
        for (Session member : members) member.send(packet);
    }

    /**
     * Forwards game data along the party's links.
     * @param sender The member that sent the data.
     * @param target From the host, a member's session id or the broadcast target.
     * @param packet The data packet to forward.
     */

    public void forward(Session sender, int target, OutgoingPacket packet) {
        forward(sender, target, member -> member.send(packet));
    }

    /**
     * Hands game data to each recipient along the party's links.
     * @param sender The member that sent the data.
     * @param target From the host, a member's session id or the broadcast target.
     * @param delivery Sends the data to one recipient.
     */

    public void forward(Session sender, int target, Consumer<Session> delivery) {
        // A member's data only ever goes to the authoritative host.
        if (sender != host) {
            delivery.accept(host);
            return;
        }

        for (Session member : members) {
            // The host already has its own data.
            if (member == host) continue;

            // Broadcast reaches everyone, otherwise only the named member.
            if (target == NetworkConstants.BROADCAST || member.getId() == target) delivery.accept(member);
        }
    }

    /**
     * Adds a member. Called under the controller's lock.
     * @param session The session joining.
     */

    void add(Session session) {
        members.add(session);
    }

    /**
     * Removes a member. Called under the controller's lock.
     * @param session The session leaving.
     */

    void remove(Session session) {
        members.remove(session);
    }

    /**
     * Keeps a removed member out of match finding. Called under the controller's lock.
     * @param session The session the host removed.
     */

    void markKicked(Session session) {
        kicked.add(session.getId());
    }

    /**
     * Called under the controller's lock.
     * @param session A session to check.
     * @return Whether the host removed the session.
     */

    boolean hasKicked(Session session) {
        return kicked.contains(session.getId());
    }

    /**
     * @return The join code.
     */

    public String getCode() {
        return code;
    }

    /**
     * @return The host's session.
     */

    public Session getHost() {
        return host;
    }

    /**
     * @return The text shown in party lists, empty for none.
     */

    public String getDescription() {
        return description;
    }

    /**
     * @return The current members, host included.
     */

    public List<Session> getMembers() {
        return members;
    }

    /**
     * @param session A session to check.
     * @return Whether the session is the party's host.
     */

    public boolean isHost(Session session) {
        return session == host;
    }

    /**
     * @return Whether the party has no room left.
     */

    public boolean isFull() {
        return members.size() >= Constants.MAX_PARTY_SIZE;
    }

    /**
     * Writes the party as a party list shows it.
     * @return The code, the host's name, the member count, the size limit and the description.
     */

    public OutputBuffer serializeListing() {
        OutputBuffer buffer = new OutputBuffer();

        buffer.writeString(code);
        buffer.writeString(host.getName());
        buffer.writeUnsignedByte(members.size());
        buffer.writeUnsignedByte(Constants.MAX_PARTY_SIZE);
        buffer.writeString(description);

        return buffer;
    }

    /**
     * Writes the party as a new member sees it.
     * @return The code, the host's id, then the member count, each member and the description.
     */

    public OutputBuffer serialize() {
        OutputBuffer buffer = new OutputBuffer();

        buffer.writeString(code);
        buffer.writeInt(host.getId());
        buffer.writeUnsignedByte(members.size());

        for (Session member : members) buffer.append(member.serialize());

        buffer.writeString(description);

        return buffer;
    }
}
