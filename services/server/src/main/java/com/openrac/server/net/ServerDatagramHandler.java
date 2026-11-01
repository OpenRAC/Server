package com.openrac.server.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;

import com.openrac.net.NetworkConstants;
import com.openrac.net.datagrams.Datagrams;
import com.openrac.net.datagrams.OutgoingDatagram;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.DataPacket;
import com.openrac.server.Session;
import com.openrac.server.SessionRegistry;
import com.openrac.server.party.Party;
import com.openrac.utils.logger.Log;

import java.net.InetSocketAddress;
import java.nio.BufferUnderflowException;

/**
 * Handles the server's one UDP socket. Each datagram names its session and carries the token the
 * session was given over TCP. A valid one binds its sender's address to the session, and STATE is
 * forwarded along the party's links. Anything else is dropped without an answer or a strike, so
 * forged datagrams can never close a real session.
 */

public class ServerDatagramHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    /**
     * Checks a datagram's size and reads it, dropping any that is cut short.
     * @param ctx The channel handler context.
     * @param packet The datagram and its sender.
     */

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        InetSocketAddress sender = packet.sender();
        ByteBuf content = packet.content();

        // No client sends more, and it would not fit common paths.
        if (content.readableBytes() > NetworkConstants.MAX_DATAGRAM_LENGTH) {
            drop(sender, "an oversized datagram");
            return;
        }

        byte[] bytes = new byte[content.readableBytes()];

        content.readBytes(bytes);

        try {
            route(ctx, sender, new InputBuffer(bytes));
        } catch (BufferUnderflowException e) {
            drop(sender, "a malformed datagram");
        }
    }

    /**
     * Logs an error without closing the socket every session shares.
     * @param ctx The channel handler context.
     * @param cause The exception that occurred.
     */

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Log.error("Error on the UDP socket: " + cause.getMessage());
    }

    /**
     * Checks a datagram's session and token, binds its sender, then handles its kind.
     * @param ctx The channel handler context.
     * @param sender Where the datagram came from.
     * @param payload The datagram's bytes.
     */

    private void route(ChannelHandlerContext ctx, InetSocketAddress sender, InputBuffer payload) {
        Session session = SessionRegistry.get(payload.readInt());
        long token = payload.readLong();
        Datagrams kind = Datagrams.get(payload.readUnsignedByte());

        // Only the session's own client was told its token.
        if (session == null || session.getToken() != token) {
            drop(sender, "a datagram for an unknown session or token");
            return;
        }

        // A kind we never defined is not this protocol.
        if (kind == null) {
            drop(sender, "a datagram of an unknown kind");
            return;
        }

        session.touch();
        bind(session, sender);

        switch (kind) {
            // Binding the sender is all a keepalive asks for.
            case BIND -> {}
            case STATE -> handleState(ctx, session, payload);
        }
    }

    /**
     * Points the session's datagrams at the sender, logging a first bind or a move.
     * @param session The session the datagram proved itself for.
     * @param sender Where the datagram came from.
     */

    private static void bind(Session session, InetSocketAddress sender) {
        InetSocketAddress bound = session.getUdpAddress();
        long now = System.nanoTime();

        // Most datagrams come from where the last one did.
        if (sender.equals(bound)) {
            session.heard(now);
            return;
        }

        session.setUdpAddress(sender, now);

        // NAT may move the port, so a new sender rebinds the session.
        if (bound == null) Log.info("Session " + session.getId() + " (" + session.getName() + ") bound UDP to " + sender + ".");
        else Log.info("Session " + session.getId() + " (" + session.getName() + ") moved UDP from " + bound + " to " + sender + ".");
    }

    /**
     * Forwards game state along the party's links, over UDP where a recipient has bound it.
     * @param ctx The channel handler context.
     * @param session The session that sent the state.
     * @param payload The rest of the datagram.
     */

    private void handleState(ChannelHandlerContext ctx, Session session, InputBuffer payload) {
        int target = payload.readInt();
        byte[] state = payload.readRemainingBytes();

        // Over its rate, state is dropped and never counted against the session.
        if (!session.getStateLimit().take()) {
            drop(session.getUdpAddress(), "state over session " + session.getId() + "'s rate");
            return;
        }

        Party party = session.getParty();

        // State sent outside a party has nowhere to go.
        if (party == null) return;

        // State flows every frame, so only build the line when shown.
        if (Log.isVerbose()) Log.verbose("State from session " + session.getId() + " to " + target + " in party " + party.getCode() + ": " + state.length + " bytes.");

        byte[] datagram = new OutgoingDatagram(session.getId(), state).toByteArray();
        DataPacket fallback = new DataPacket(session.getId(), state);

        party.forward(session, target, member -> member.sendState(ctx.channel(), datagram, fallback));
    }

    /**
     * Notes a dropped datagram on the debug log, never above it.
     * @param sender Where the datagram came from.
     * @param what What was dropped.
     */

    private static void drop(InetSocketAddress sender, String what) {
        Log.debug("Dropped " + what + " from " + sender + ".");
    }
}
