package com.openrac.server.party;

import com.openrac.net.NetworkConstants;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.server.Constants;
import com.openrac.server.Session;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A group of players linked under one host. The host's game is authoritative, so members' game
 * data goes to the host and the host's goes to the members. Membership only changes through
 * {@link PartyController}, while sends iterate a snapshot from any thread.
 */

public class Party {
    private final String code;
    private final Session host;
    private final boolean open; // Whether match finding may place strangers in the party.

    private final List<Session> members = new CopyOnWriteArrayList<>();

    /**
     * Creates a party with its host as the first member.
     * @param code The join code.
     * @param host The session that created the party.
     * @param open Whether match finding may place players in it, rather than only its code.
     */

    public Party(String code, Session host, boolean open) {
        this.code = code;
        this.host = host;
        this.open = open;

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
        // A member's data only ever goes to the authoritative host.
        if (sender != host) {
            host.send(packet);
            return;
        }

        for (Session member : members) {
            // The host already has its own data.
            if (member == host) continue;

            // Broadcast reaches everyone, otherwise only the named member.
            if (target == NetworkConstants.BROADCAST || member.getId() == target) member.send(packet);
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
     * @return The join code.
     */

    public String getCode() {
        return code;
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
     * @return Whether match finding may place players in the party.
     */

    public boolean isOpen() {
        return open;
    }

    /**
     * Writes the party as a new member sees it.
     * @return The code, the host's id, then the member count and each member.
     */

    public OutputBuffer serialize() {
        OutputBuffer buffer = new OutputBuffer();

        buffer.writeString(code);
        buffer.writeInt(host.getId());
        buffer.writeUnsignedByte(members.size());

        for (Session member : members) buffer.append(member.serialize());

        return buffer;
    }
}
