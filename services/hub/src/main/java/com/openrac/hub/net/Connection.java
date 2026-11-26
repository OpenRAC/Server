package com.openrac.hub.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import com.openrac.hub.Constants;
import com.openrac.hub.accounts.AccountController;
import com.openrac.hub.servers.GameServer;
import com.openrac.net.limits.RateLimit;
import com.openrac.net.netty.handlers.PacketHandler;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.utils.Utils;

import java.net.InetAddress;

/**
 * One connection to the hub: a player's client or a game server, and what it has said it is. Only
 * the connection's event loop touches it.
 */

public class Connection {
    private final ChannelHandlerContext context;

    private final RateLimit requestLimit = new RateLimit(Constants.REQUEST_BURST, Constants.REQUEST_PER_SECOND);
    private final RateLimit accountLimit = new RateLimit(Constants.ACCOUNT_REQUEST_BURST, Constants.ACCOUNT_REQUEST_PER_SECOND); // Once logged in.

    private InetAddress address; // Set while the connection counts against its address.
    private boolean guest; // A client that said hello.
    private GameServer server; // A game server that registered, else null.

    /**
     * Wraps a connection.
     * @param context The channel handler context of the connection.
     */

    public Connection(ChannelHandlerContext context) {
        this.context = context;
    }

    /**
     * Logs why the peer is being dropped and closes the connection.
     * @param reason What the peer did wrong.
     */

    public void reject(String reason) {
        PacketHandler.reject(context, reason);
    }

    /**
     * Reads a session token, closing a connection that sends one the hub never gave out.
     * @param payload The rest of the packet.
     * @return The token, or null when it was invalid and the connection is closed.
     */

    public String readToken(InputBuffer payload) {
        String token = payload.readString();

        // A blank or oversized token is no token the hub gave out.
        if (!Utils.isValid(token, Constants.MAX_TOKEN_LENGTH)) {
            reject("sent an invalid token");
            return null;
        }

        return token;
    }

    /**
     * @return The channel handler context of the connection.
     */

    public ChannelHandlerContext getContext() {
        return context;
    }

    /**
     * @return The channel of the connection.
     */

    public Channel getChannel() {
        return context.channel();
    }

    /**
     * @return The address the connection counts against, or null when it does not.
     */

    public InetAddress getAddress() {
        return address;
    }

    /**
     * @return The game server this connection registered as, or null.
     */

    public GameServer getServer() {
        return server;
    }

    /**
     * @return The bucket a client's requests draw from, or null for a game server, which reports on a schedule.
     */

    public RateLimit getRequestLimit() {
        // Servers report on a schedule, so only clients can flood.
        if (!guest) return null;

        // A logged-in game asks for its ladders and career all at once.
        if (AccountController.getAccount(context.channel()) != null) return accountLimit;

        return requestLimit;
    }

    /**
     * @return Whether the connection said hello as a player's client.
     */

    public boolean isGuest() {
        return guest;
    }

    /**
     * @param packet The packet that needs a client.
     * @return Whether the connection is a client; a server is closed.
     */

    public boolean isGuest(Packets packet) {
        // A server sending a client's request is broken.
        if (!guest) reject("sent " + packet + ", which only clients send");

        return guest;
    }

    /**
     * @return Whether the connection has said hello or registered.
     */

    public boolean isKnown() {
        return guest || server != null;
    }

    /**
     * @param address The address the connection counts against.
     */

    public void setAddress(InetAddress address) {
        this.address = address;
    }

    /**
     * @param guest Whether the connection is a player's client.
     */

    public void setGuest(boolean guest) {
        this.guest = guest;
    }

    /**
     * @param server The game server this connection registered as.
     */

    public void setServer(GameServer server) {
        this.server = server;
    }
}
