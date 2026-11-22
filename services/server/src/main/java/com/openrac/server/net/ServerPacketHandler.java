package com.openrac.server.net;

import io.netty.channel.ChannelHandlerContext;

import com.openrac.net.NetworkConstants;
import com.openrac.net.netty.handlers.PacketHandler;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.ChatPacket;
import com.openrac.net.packets.out.DataPacket;
import com.openrac.net.packets.out.ErrorPacket;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.RelayPacket;
import com.openrac.net.packets.out.TimePacket;
import com.openrac.net.packets.out.UdpPacket;
import com.openrac.server.Clock;
import com.openrac.server.Constants;
import com.openrac.server.Session;
import com.openrac.server.SessionRegistry;
import com.openrac.server.hub.RegionController;
import com.openrac.server.limits.ConnectionLimit;
import com.openrac.server.limits.RateLimit;
import com.openrac.server.party.Party;
import com.openrac.server.party.PartyController;
import com.openrac.utils.logger.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Handles one player's connection. It answers the greeting with a session, runs the party
 * requests, and forwards chat and game data inside the party. Each connection gets its own
 * instance, so the session field is only touched on that connection's event loop.
 */

public class ServerPacketHandler extends PacketHandler {
    private Session session; // Null until the client says hello.
    private InetAddress address; // Set while the connection counts against its address.

    private final RateLimit chatLimit = new RateLimit(Constants.CHAT_BURST, Constants.CHAT_PER_SECOND);
    private final RateLimit partyLimit = new RateLimit(Constants.PARTY_BURST, Constants.PARTY_PER_SECOND);
    private final RateLimit dataLimit = new RateLimit(Constants.DATA_BURST, Constants.DATA_PER_SECOND);
    private final RateLimit relayLimit = new RateLimit(Constants.RELAY_BURST, Constants.RELAY_PER_SECOND);
    private final RateLimit timeLimit = new RateLimit(Constants.TIME_BURST, Constants.TIME_PER_SECOND);
    private final RateLimit strikeLimit = new RateLimit(Constants.STRIKE_BURST, Constants.STRIKES_FORGIVEN_PER_SECOND);

    private boolean warned; // Whether this connection's first dropped packet was logged.

    /**
     * Admits a new connection within its address's limit and gives it a deadline to say hello.
     * @param ctx The channel handler context.
     */

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Only network connections have an address to count against.
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress remote) {
            if (!ConnectionLimit.open(remote.getAddress())) {
                reject(ctx, "has too many connections open");
                return;
            }

            address = remote.getAddress();
        }

        Log.info("Connection from " + ctx.channel().remoteAddress() + ".");

        // A connection that never says hello would hold a socket for nothing.
        ctx.executor().schedule(() -> {
            if (session == null && ctx.channel().isActive()) reject(ctx, "did not say hello in time");
        }, Constants.HELLO_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        ctx.fireChannelActive();
    }

    /**
     * Takes a dropped connection out of its party.
     * @param ctx The channel handler context.
     */

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // A refused connection never counted against its address.
        if (address != null) ConnectionLimit.close(address);

        // A session that moved to a newer connection has not gone anywhere.
        if (session != null && session.isOn(ctx.channel())) {
            // Datagrams naming a closed session are dropped until it comes back.
            SessionRegistry.remove(session);
            RegionController.remove(session);

            PartyController.dropped(session, Clock.now());
        }

        // Without a hello there is only the address to name.
        String who = session == null ? "Connection from " + ctx.channel().remoteAddress() : "Session " + session.getId() + " (" + session.getName() + ")";

        Log.info(who + " disconnected.");

        ctx.fireChannelInactive();
    }

    /**
     * Routes one packet from the client.
     * @param ctx The channel handler context.
     * @param packet The packet id as an enum.
     * @param payload The rest of the packet.
     */

    @Override
    protected void route(ChannelHandlerContext ctx, Packets packet, InputBuffer payload) {
        // Everything but the greeting needs a session first.
        if (session == null && packet != Packets.HELLO) {
            reject(ctx, "sent " + packet + " before hello");
            return;
        }

        if (session != null) session.touch();

        // Over its rate, a packet is dropped and counts as a strike.
        if (!withinLimit(packet)) {
            strike(ctx, packet);
            return;
        }

        switch (packet) {
            case HELLO -> handleHello(ctx, payload);
            case PARTY -> handleParty(ctx, payload);
            case CHAT -> handleChat(ctx, payload);
            case DATA -> handleData(payload);
            case RELAY -> handleRelay(payload);
            case REGIONS -> RegionController.list(session);
            case REGISTER -> handleRegister(ctx, payload);
            case TIME -> handleTime(payload);

            // Only the server reports errors.
            case ERROR -> reject(ctx, "sent an error packet");

            // Only the server hands out UDP tokens.
            case UDP -> reject(ctx, "sent a UDP packet");
        }
    }

    /**
     * @param packet The packet that arrived.
     * @return Whether its kind is still within this connection's rate.
     */

    private boolean withinLimit(Packets packet) {
        return switch (packet) {
            case CHAT -> chatLimit.take();
            case PARTY -> partyLimit.take();
            case DATA -> dataLimit.take();
            case RELAY -> relayLimit.take();
            // Region traffic is as rare as party requests.
            case REGIONS, REGISTER -> partyLimit.take();
            case TIME -> timeLimit.take();

            // The greeting is once only, and server-only packets are rejected outright.
            case HELLO, ERROR, UDP -> true;
        };
    }

    /**
     * Counts a dropped packet, closing a connection that keeps flooding.
     * @param ctx The channel handler context.
     * @param packet The packet that was over its rate.
     */

    private void strike(ChannelHandlerContext ctx, Packets packet) {
        // Strikes are forgiven over time, so only a sustained flood is closed.
        if (!strikeLimit.take()) {
            reject(ctx, "kept flooding " + packet + " packets");
            return;
        }

        // One line per connection, not one per dropped packet.
        if (warned) return;

        warned = true;
        Log.warning("Session " + session.getId() + " (" + session.getName() + ") is over its " + packet + " rate; dropping packets.");
    }

    /**
     * Creates the session from the client's version and display name.
     * @param ctx The channel handler context.
     * @param payload The hello packet's payload.
     */

    private void handleHello(ChannelHandlerContext ctx, InputBuffer payload) {
        // One greeting per connection.
        if (session != null) {
            reject(ctx, "sent a second hello");
            return;
        }

        String version = payload.readString();
        String name = payload.readString().strip();
        int features = payload.size() > 0 ? payload.readUnsignedByte() : 0; // Older clients send none.

        // Blank or oversized fields only come from a broken client.
        if (!isValid(version, Constants.MAX_VERSION_LENGTH) || !isValid(name, Constants.MAX_NAME_LENGTH)) {
            reject(ctx, "sent an invalid hello");
            return;
        }

        session = new Session(ctx.channel(), name, (features & NetworkConstants.CLIENT_RESUMES) != 0,
                (features & NetworkConstants.CLIENT_SLOTS) != 0, (features & NetworkConstants.CLIENT_KEEPS_ALIVE) != 0);

        Log.info("Session " + session.getId() + " (" + name + ", version " + version + ") from " + ctx.channel().remoteAddress() + ".");

        SessionRegistry.add(session);

        session.send(new HelloPacket(session.getId()));

        // Shipped clients read hello as it was, so UDP comes separately.
        session.send(new UdpPacket(session.getToken(), Constants.PORT, Constants.FEATURES, session.getResumeToken()));
    }

    /**
     * Runs a party request.
     * @param ctx The channel handler context.
     * @param payload The party packet's payload.
     */

    private void handleParty(ChannelHandlerContext ctx, InputBuffer payload) {
        Opcodes.Party opcode = Opcodes.Party.get(payload.readUnsignedByte());

        switch (opcode) {
            case CREATE -> {
                // Clients from before descriptions send an empty body.
                String description = payload.size() > 0 ? sanitizeDescription(payload.readString()) : "";
                String family = readFamily(ctx, payload);

                if (family != null) PartyController.create(session, description, orDefault(family));
            }
            case JOIN -> {
                String code = payload.readString();
                String family = readFamily(ctx, payload);

                // A client from before families is told only what it can read.
                if (family != null) PartyController.join(session, code, family.isEmpty() ? null : family);
            }
            case FIND -> {
                String family = readFamily(ctx, payload);

                if (family != null) PartyController.find(session, orDefault(family));
            }
            case LIST -> {
                String family = readFamily(ctx, payload);
                int flags = payload.size() > 0 ? payload.readUnsignedByte() : 0; // Older clients stop at the family.

                if (family != null) PartyController.list(session, orDefault(family), (flags & NetworkConstants.LIST_ADVERTS) != 0);
            }
            case ADVERT -> {
                byte[] advert = payload.readRemainingBytes();

                // An advert past the limit only comes from a broken client.
                if (advert.length > Constants.MAX_ADVERT_LENGTH) {
                    reject(ctx, "sent an oversized advert");
                    return;
                }

                PartyController.advert(session, advert);
            }
            case KICK -> PartyController.kick(session, payload.readInt());
            case HOST_CHANGED -> {
                boolean decline = payload.readUnsignedByte() != 0;
                int count = payload.readUnsignedByte();
                List<Integer> ids = new ArrayList<>();

                for (int i = 0; i < count; i++) ids.add(payload.readInt());

                PartyController.successors(session, decline, ids);
            }
            case RESUME -> {
                Session held = PartyController.resume(session, payload.readString().toUpperCase(Locale.ROOT), payload.readInt(), payload.readLong());

                // A slot given up on, or a token that is not this player's.
                if (held == null) session.send(new ErrorPacket(Opcodes.Error.RESUME_REFUSED));
                else session = held;
            }
            case LEAVE -> {
                // Leaving nothing is worth telling the client about.
                if (!PartyController.leave(session)) session.send(new ErrorPacket(Opcodes.Error.NOT_IN_PARTY));
            }

            // The rest are notices only the server sends.
            case null, default -> reject(ctx, "sent an invalid party opcode");
        }
    }

    /**
     * Sends a chat message to every member of the sender's party, the sender included.
     * @param ctx The channel handler context.
     * @param payload The chat packet's payload.
     */

    private void handleChat(ChannelHandlerContext ctx, InputBuffer payload) {
        String message = payload.readString();

        // Blank or oversized messages only come from a broken client.
        if (!isValid(message, Constants.MAX_CHAT_LENGTH)) {
            reject(ctx, "sent an invalid chat message");
            return;
        }

        Party party = session.getParty();

        // Chat only travels inside a party.
        if (party == null) {
            session.send(new ErrorPacket(Opcodes.Error.NOT_IN_PARTY));
            return;
        }

        Log.debug("Chat from session " + session.getId() + " in party " + party.getCode() + ": " + message);

        party.broadcast(new ChatPacket(session.getId(), message));
    }

    /**
     * Forwards game data along the party's links, unread.
     * @param payload The data packet's payload.
     */

    private void handleData(InputBuffer payload) {
        int target = payload.readInt();
        byte[] data = payload.readRemainingBytes();

        Party party = session.getParty();

        // Data sent outside a party has nowhere to go.
        if (party == null) return;

        // Game data flows every frame, so only build the line when shown.
        if (Log.isVerbose()) Log.verbose("Data from session " + session.getId() + " to " + target + " in party " + party.getCode() + ": " + data.length + " bytes.");

        party.forward(session, target, new DataPacket(session.getId(), data));
    }

    /**
     * Relays game data from one member to another, or to every other, unread.
     * @param payload The relay packet's payload.
     */

    private void handleRelay(InputBuffer payload) {
        int target = payload.readInt();
        byte[] data = payload.readRemainingBytes();

        Party party = session.getParty();

        // Data sent outside a party has nowhere to go.
        if (party == null) return;

        // Game data flows every frame, so only build the line when shown.
        if (Log.isVerbose()) Log.verbose("Relay from session " + session.getId() + " to " + target + " in party " + party.getCode() + ": " + data.length + " bytes.");

        party.relay(session, target, new RelayPacket(session.getId(), data));
    }

    /**
     * Lists or refreshes a region server that registers with this hub.
     * @param ctx The channel handler context.
     * @param payload The register packet's payload.
     */

    private void handleRegister(ChannelHandlerContext ctx, InputBuffer payload) {
        String key = payload.readString();
        String name = payload.readString().strip();
        String host = payload.readString().strip();
        int port = payload.readInt();
        int players = payload.readInt();

        // A listed region needs a name that fits and a real port.
        if (!isValid(name, Constants.MAX_NAME_LENGTH) || port < 1 || port > 65_535) {
            reject(ctx, "sent an invalid region");
            return;
        }

        // A wrong key means another operator's region.
        if (!RegionController.register(session, key, name, host, port, players)) reject(ctx, "sent a wrong region key");
    }

    /**
     * Answers a clock request with the client's own time and the server's.
     * @param payload The time packet's payload.
     */

    private void handleTime(InputBuffer payload) {
        session.send(new TimePacket(payload.readLong(), Clock.now()));
    }

    /**
     * Reads the game family a party request may end with (openrac_api.world_family: "rac1-60-1",
     * "rac2-pal-1"). Shipped clients stop before it.
     * @param ctx The channel handler context.
     * @param payload The rest of the party request.
     * @return The family, empty for none, or null when it was invalid and the connection is closed.
     */

    private String readFamily(ChannelHandlerContext ctx, InputBuffer payload) {
        String family = payload.size() > 0 ? payload.readString() : "";

        // A family is compared, never shown, so anything but short printable ASCII is a broken client.
        if (family.length() > Constants.MAX_FAMILY_LENGTH || !family.chars().allMatch(c -> c > 0x20 && c <= 0x7E)) {
            reject(ctx, "sent an invalid game family");
            return null;
        }

        return family;
    }

    /**
     * @param family A family as read, empty for none.
     * @return The family, or the one of every client from before families.
     */

    private static String orDefault(String family) {
        return family.isEmpty() ? Constants.DEFAULT_FAMILY : family;
    }

    /**
     * Keeps a party description to printable ASCII, trimmed and cut to the limit.
     * @param text The description as the client sent it.
     * @return The description that is listed.
     */

    static String sanitizeDescription(String text) {
        StringBuilder kept = new StringBuilder();

        // Lists are drawn by clients with a plain font.
        for (char character : text.toCharArray()) if (character >= 0x20 && character <= 0x7E) kept.append(character);

        String trimmed = kept.toString().strip();

        return trimmed.substring(0, Math.min(trimmed.length(), Constants.MAX_DESCRIPTION_LENGTH));
    }

    /**
     * @param text A string read from the client.
     * @param maxLength The most characters it may have.
     * @return Whether the string is neither blank nor too long.
     */

    private static boolean isValid(String text, int maxLength) {
        return !text.isBlank() && text.length() <= maxLength;
    }
}
