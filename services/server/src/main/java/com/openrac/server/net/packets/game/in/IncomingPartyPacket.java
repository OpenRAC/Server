package com.openrac.server.net.packets.game.in;

import com.openrac.net.NetworkConstants;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.ErrorPacket;
import com.openrac.server.Constants;
import com.openrac.server.Session;
import com.openrac.server.net.Connection;
import com.openrac.server.net.packets.model.IncomingPacket;
import com.openrac.server.party.PartyController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs a party request: create, join, find, list, advert, kick, host handover, resume and leave.
 */

public class IncomingPartyPacket extends IncomingPacket {
    /**
     * Creates the packet, limited like every party request.
     */

    public IncomingPartyPacket() {
        super(true, Connection::getPartyLimit);
    }

    /**
     * Reads the opcode and runs it.
     * @param connection The connection that sent the request.
     * @param payload The party packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        Opcodes.Party opcode = Opcodes.Party.get(payload.readUnsignedByte());

        switch (opcode) {
            case CREATE -> handleCreate(connection, payload);
            case JOIN -> handleJoin(connection, payload);
            case FIND -> handleFind(connection, payload);
            case LIST -> handleList(connection, payload);
            case ADVERT -> handleAdvert(connection, payload);
            case KICK -> PartyController.kick(connection.getSession(), payload.readInt());
            case HOST_CHANGED -> handleHostChanged(connection, payload);
            case RESUME -> handleResume(connection, payload);
            case LEAVE -> handleLeave(connection);

            // The rest are notices only the server sends.
            case null, default -> connection.reject("sent an invalid party opcode");
        }
    }

    /**
     * Opens a party the session hosts.
     * @param connection The connection that sent the request.
     * @param payload The rest of the request.
     */

    private void handleCreate(Connection connection, InputBuffer payload) {
        // Clients from before descriptions send an empty body.
        String description = payload.size() > 0 ? sanitizeDescription(payload.readString()) : "";
        String family = connection.readFamily(payload);

        if (family != null) PartyController.create(connection.getSession(), description, Connection.orDefault(family));
    }

    /**
     * Joins a party by its code.
     * @param connection The connection that sent the request.
     * @param payload The rest of the request.
     */

    private void handleJoin(Connection connection, InputBuffer payload) {
        String code = payload.readString();
        String family = connection.readFamily(payload);

        // A client from before families is told only what it can read.
        if (family != null) PartyController.join(connection.getSession(), code, family.isEmpty() ? null : family);
    }

    /**
     * Finds an open party of a family.
     * @param connection The connection that sent the request.
     * @param payload The rest of the request.
     */

    private void handleFind(Connection connection, InputBuffer payload) {
        String family = connection.readFamily(payload);

        if (family != null) PartyController.find(connection.getSession(), Connection.orDefault(family));
    }

    /**
     * Lists the open parties of a family.
     * @param connection The connection that sent the request.
     * @param payload The rest of the request.
     */

    private void handleList(Connection connection, InputBuffer payload) {
        String family = connection.readFamily(payload);
        // Older clients stop at the family.
        int flags = payload.size() > 0 ? payload.readUnsignedByte() : 0;

        if (family != null) PartyController.list(connection.getSession(), Connection.orDefault(family), (flags & NetworkConstants.LIST_ADVERTS) != 0);
    }

    /**
     * Sets the advert the party lists.
     * @param connection The connection that sent the request.
     * @param payload The rest of the request.
     */

    private void handleAdvert(Connection connection, InputBuffer payload) {
        byte[] advert = payload.readRemainingBytes();

        // An advert past the limit only comes from a broken client.
        if (advert.length > Constants.MAX_ADVERT_LENGTH) {
            connection.reject("sent an oversized advert");
            return;
        }

        PartyController.advert(connection.getSession(), advert);
    }

    /**
     * Names the members the host would hand over to.
     * @param connection The connection that sent the request.
     * @param payload The rest of the request.
     */

    private void handleHostChanged(Connection connection, InputBuffer payload) {
        boolean decline = payload.readUnsignedByte() != 0;
        int count = payload.readUnsignedByte();
        List<Integer> ids = new ArrayList<>();

        for (int i = 0; i < count; i++) ids.add(payload.readInt());

        PartyController.successors(connection.getSession(), decline, ids);
    }

    /**
     * Takes a held session back onto this connection.
     * @param connection The connection that sent the request.
     * @param payload The rest of the request.
     */

    private void handleResume(Connection connection, InputBuffer payload) {
        Session session = connection.getSession();
        Session held = PartyController.resume(session, payload.readString().toUpperCase(Locale.ROOT), payload.readInt(), payload.readLong());

        // A slot given up on, or a token not this player's.
        if (held == null) session.send(new ErrorPacket(Opcodes.Error.RESUME_REFUSED));
        else connection.setSession(held);
    }

    /**
     * Takes the session out of its party.
     * @param connection The connection that sent the request.
     */

    private void handleLeave(Connection connection) {
        Session session = connection.getSession();

        // Leaving nothing is worth telling the client about.
        if (!PartyController.leave(session)) session.send(new ErrorPacket(Opcodes.Error.NOT_IN_PARTY));
    }

    /**
     * Keeps a party description to printable ASCII, trimmed and cut to the limit.
     * @param text The description as the client sent it.
     * @return The description that is listed.
     */

    private static String sanitizeDescription(String text) {
        StringBuilder kept = new StringBuilder();

        // Lists are drawn by clients with a plain font.
        for (char character : text.toCharArray()) if (character >= 0x20 && character <= 0x7E) kept.append(character);

        String trimmed = kept.toString().strip();

        return trimmed.substring(0, Math.min(trimmed.length(), Constants.MAX_DESCRIPTION_LENGTH));
    }
}
