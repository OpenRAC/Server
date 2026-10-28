package com.openrac.server.party;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.net.packets.out.ErrorPacket;
import com.openrac.net.packets.out.PartyPacket;
import com.openrac.server.Constants;
import com.openrac.server.Session;
import com.openrac.utils.logger.Log;

import java.security.SecureRandom;
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
