package com.openrac.server.party;

import com.openrac.net.NetworkConstants;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.net.packets.out.ErrorPacket;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.PartyPacket;
import com.openrac.net.packets.out.UdpPacket;
import com.openrac.server.Constants;
import com.openrac.server.Session;
import com.openrac.server.SessionRegistry;
import com.openrac.utils.logger.Log;

import io.netty.channel.Channel;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Creates, joins and ends parties. Sessions arrive on different worker threads, so every change
 * runs under one class-wide lock, and a join can never race a full party or a leaving host.
 */

public class PartyController {
    // Kept in creation order, so match finding fills the oldest party first.
    private static final Map<String, Party> parties = new LinkedHashMap<>();

    // The join code is all that keeps strangers out.
    private static final SecureRandom random = new SecureRandom();

    /**
     * Creates a party hosted by the session and sends it the party's details.
     * @param session The session creating the party.
     * @param description The sanitised description to list it under.
     */

    public static synchronized void create(Session session, String description) {
        // A session belongs to one party at a time.
        if (session.getParty() != null) {
            session.send(new ErrorPacket(Opcodes.Error.ALREADY_IN_PARTY));
            return;
        }

        host(session, description);
    }

    /**
     * Adds the session to the party with the code, telling the members first.
     * @param session The session joining.
     * @param code The join code it entered.
     */

    public static synchronized void join(Session session, String code) {
        // A session belongs to one party at a time.
        if (session.getParty() != null) {
            session.send(new ErrorPacket(Opcodes.Error.ALREADY_IN_PARTY));
            return;
        }

        // Players type the code, so case does not matter.
        Party party = parties.get(code.toUpperCase(Locale.ROOT));

        // A mistyped code, or a party whose host already left.
        if (party == null) {
            session.send(new ErrorPacket(Opcodes.Error.PARTY_NOT_FOUND));
            return;
        }

        // Each game only links so many players.
        if (party.isFull()) {
            session.send(new ErrorPacket(Opcodes.Error.PARTY_FULL));
            return;
        }

        add(session, party);
    }

    /**
     * Places the session in the oldest party with room, or makes it the host of a new party when
     * there is none.
     * @param session The session looking for a match.
     */

    public static synchronized void find(Session session) {
        // A session belongs to one party at a time.
        if (session.getParty() != null) {
            session.send(new ErrorPacket(Opcodes.Error.ALREADY_IN_PARTY));
            return;
        }

        for (Party party : parties.values()) {
            if (party.isFull()) continue;

            // Finding a match must not hand the host back the player it just removed.
            if (party.hasKicked(session)) continue;

            add(session, party);
            return;
        }

        // No party has room, so the finder hosts the next one.
        host(session, "");
    }

    /**
     * Sends the session the parties it could join: oldest first, with room, not its own.
     * @param session The session asking.
     */

    public static synchronized void list(Session session) {
        OutputBuffer listing = new OutputBuffer();
        int count = 0;

        for (Party party : parties.values()) {
            // The list is for finding a party to join, and a full one cannot be joined.
            if (party.isFull()) continue;

            // A session has no use for the party it is already in.
            if (party == session.getParty()) continue;

            // The count is one byte, so the list is cut off here.
            if (count == Constants.MAX_LISTED_PARTIES) break;

            listing.append(party.serializeListing());
            count++;
        }

        OutputBuffer payload = new OutputBuffer();

        payload.writeUnsignedByte(count);
        payload.append(listing);

        session.send(new PartyPacket(Opcodes.Party.LIST, payload));
    }

    /**
     * Makes the session the host of a new party and sends it the party's details.
     * @param session The session hosting.
     * @param description The sanitised description to list it under.
     */

    private static void host(Session session, String description) {
        Party party = new Party(generateCode(), session, description);

        parties.put(party.getCode(), party);
        session.setParty(party);

        Log.info(describe(session) + " is hosting party " + party.getCode() + ".");

        session.send(new PartyPacket(Opcodes.Party.INFO, party.serialize()));
    }

    /**
     * Adds the session to a party with room, telling the members first.
     * @param session The session joining.
     * @param party The party it joins.
     */

    private static void add(Session session, Party party) {
        party.broadcast(new PartyPacket(Opcodes.Party.JOINED, session.serialize()));

        party.add(session);
        session.setParty(party);

        Log.info(describe(session) + " joined party " + party.getCode() + " (" + party.getMembers().size() + "/" + Constants.MAX_PARTY_SIZE + ").");

        session.send(new PartyPacket(Opcodes.Party.INFO, party.serialize()));
    }

    /**
     * Removes a member at its host's word: it is told, the others hear it left. The connection
     * stays, and its code still works, so a kick is not a ban.
     * @param session The session asking.
     * @param target The id of the member to remove.
     */

    public static synchronized void kick(Session session, int target) {
        Party party = session.getParty();

        if (party == null) {
            session.send(new ErrorPacket(Opcodes.Error.NOT_IN_PARTY));
            return;
        }

        // Only the host has the say.
        if (!party.isHost(session)) {
            session.send(new ErrorPacket(Opcodes.Error.NOT_HOST));
            return;
        }

        for (Session member : party.getMembers()) {
            // A host cannot remove itself: leaving is how a party ends.
            if (member.getId() != target || party.isHost(member)) continue;

            party.remove(member);
            party.markKicked(member);
            member.setParty(null);

            member.send(new PartyPacket(Opcodes.Party.KICKED));
            party.broadcast(new PartyPacket(Opcodes.Party.LEFT, member.getId()));

            Log.info(describe(member) + " was removed from party " + party.getCode() + " by its host.");
            return;
        }

        // Left a moment ago, most likely: nothing to do, and nothing worth telling the host.
    }

    /**
     * Takes the session out of its party. The party ends when its host leaves.
     * @param session The session leaving.
     * @return False when the session was not in a party.
     */

    public static synchronized boolean leave(Session session) {
        Party party = session.getParty();

        // Disconnects call this for sessions in no party too.
        if (party == null) return false;

        party.remove(session);
        session.setParty(null);

        // Without the host there is no game left to link to.
        if (party.isHost(session)) {
            parties.remove(party.getCode());

            for (Session member : party.getMembers()) member.setParty(null);

            party.broadcast(new PartyPacket(Opcodes.Party.END));

            Log.info("Party " + party.getCode() + " ended: its host " + describe(session) + " left.");
            return true;
        }

        party.broadcast(new PartyPacket(Opcodes.Party.LEFT, session.getId()));

        Log.info(describe(session) + " left party " + party.getCode() + ".");
        return true;
    }

    /**
     * A connection dropped. A client that comes back keeps its slot, and the others are told; any
     * other leaves as it always did.
     * @param session The session whose connection went.
     * @param now The time of the drop, as Clock.now().
     */

    public static synchronized void dropped(Session session, long now) {
        Party party = session.getParty();

        // In no party, or one with no way back.
        if (party == null || !session.resumes()) {
            leave(session);
            return;
        }

        session.markAway(now);
        party.broadcast(new PartyPacket(session.getId(), false));

        Log.info(describe(session) + " dropped out of party " + party.getCode() + "; its slot waits.");
    }

    /**
     * Takes a session back: the connection that proved it moves onto the old session, which keeps
     * its id, its party and its slot. The session the new connection made for itself is dropped.
     * @param fresh The session of the connection asking.
     * @param code The party's join code.
     * @param id The id of the session to take back.
     * @param token That session's resume token.
     * @return The session taken back, or null when it was refused.
     */

    public static synchronized Session resume(Session fresh, String code, int id, long token) {
        Party party = parties.get(code);

        // A new connection is in no party; the party may have ended since.
        if (fresh.getParty() != null || party == null) return null;

        Session held = null;

        for (Session member : party.getMembers()) if (member.getId() == id) held = member;

        // Compared whole: the token is all that stands between a stranger and the slot.
        byte[] asked = ByteBuffer.allocate(Long.BYTES).putLong(token).array();

        if (held == null || !MessageDigest.isEqual(asked, ByteBuffer.allocate(Long.BYTES).putLong(held.getResumeToken()).array())) return null;

        // Back before the server noticed it had gone, and the old connection is what is stale.
        Channel stale = held.rebind(fresh.getChannel());

        stale.close();

        SessionRegistry.remove(fresh);
        SessionRegistry.add(held);

        // The connection now knows this session by its old id.
        held.send(new HelloPacket(held.getId()));
        held.send(new UdpPacket(held.getToken(), Constants.PORT, NetworkConstants.FEATURE_KICK | NetworkConstants.FEATURE_RESUME, held.getResumeToken()));
        held.send(new PartyPacket(Opcodes.Party.INFO, party.serialize()));

        for (Session member : party.getMembers()) if (member != held) member.send(new PartyPacket(held.getId(), true));

        Log.info(describe(held) + " came back to party " + party.getCode() + ".");

        return held;
    }

    /**
     * Once a second: closes clients gone quiet, and gives up on slots whose time is out.
     * @param now The time, as Clock.now().
     */

    public static synchronized void sweep(long now) {
        for (Party party : new ArrayList<>(parties.values())) {
            for (Session member : party.getMembers()) {
                // Only a client that comes back keeps saying something while it stands still.
                if (member.resumes() && !member.isAway() && now - member.getHeardAt() >= Constants.PARTY_IDLE_SECONDS * 1000L) {
                    Log.info(describe(member) + " went quiet.");
                    member.close();
                }

                if (member.isAway() && now - member.getAwayAt() >= Constants.RESUME_SECONDS * 1000L) leave(member);
            }
        }
    }

    /**
     * @param session A session.
     * @return The session's id and name, as log lines name it.
     */

    private static String describe(Session session) {
        return "Session " + session.getId() + " (" + session.getName() + ")";
    }

    /**
     * Picks a join code no open party uses.
     * @return The new code.
     */

    private static String generateCode() {
        StringBuilder code = new StringBuilder(Constants.CODE_LENGTH);

        // Draw again on the rare clash with an open party.
        while (code.isEmpty() || parties.containsKey(code.toString())) {
            code.setLength(0);

            for (int i = 0; i < Constants.CODE_LENGTH; i++)
                code.append(Constants.CODE_ALPHABET.charAt(random.nextInt(Constants.CODE_ALPHABET.length())));
        }

        return code.toString();
    }

    /**
     * @param code A join code.
     * @return Whether an open party uses the code.
     */

    public static synchronized boolean exists(String code) {
        return parties.containsKey(code);
    }
}
