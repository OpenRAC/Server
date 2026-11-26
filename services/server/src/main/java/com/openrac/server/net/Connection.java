package com.openrac.server.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import com.openrac.net.limits.RateLimit;
import com.openrac.net.netty.handlers.PacketHandler;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.server.Constants;
import com.openrac.server.Session;
import com.openrac.utils.Utils;

import java.net.InetAddress;

/**
 * One player's TCP connection to this server: its channel, its session once it has said hello, and
 * the rate limits its packets draw from. Only the connection's event loop touches it.
 */

public class Connection {
    private final ChannelHandlerContext context;

    private final RateLimit chatLimit = new RateLimit(Constants.CHAT_BURST, Constants.CHAT_PER_SECOND);
    private final RateLimit partyLimit = new RateLimit(Constants.PARTY_BURST, Constants.PARTY_PER_SECOND);
    private final RateLimit dataLimit = new RateLimit(Constants.DATA_BURST, Constants.DATA_PER_SECOND);
    private final RateLimit relayLimit = new RateLimit(Constants.RELAY_BURST, Constants.RELAY_PER_SECOND);
    private final RateLimit timeLimit = new RateLimit(Constants.TIME_BURST, Constants.TIME_PER_SECOND);
    private final RateLimit uiLimit = new RateLimit(Constants.UI_BURST, Constants.UI_PER_SECOND);
    private final RateLimit strikeLimit = new RateLimit(Constants.STRIKE_BURST, Constants.STRIKES_FORGIVEN_PER_SECOND);

    private Session session; // Null until the client says hello.
    private InetAddress address; // Set while the connection counts against its address.

    /**
     * Wraps a connection.
     * @param context The channel handler context of the connection.
     */

    public Connection(ChannelHandlerContext context) {
        this.context = context;
    }

    /**
     * Logs why the player is being dropped and closes the connection.
     * @param reason What the player did wrong.
     */

    public void reject(String reason) {
        PacketHandler.reject(context, reason);
    }

    /**
     * Reads the game family a party request may end with (openrac_api.world_family: "rac1-60-1",
     * "rac2-pal-1"). Shipped clients stop before it.
     * @param payload The rest of the request.
     * @return The family, empty for none, or null when it was invalid and the connection is closed.
     */

    public String readFamily(InputBuffer payload) {
        // Shipped clients stop before the family.
        String family = payload.size() > 0 ? payload.readString() : "";

        // Families are only compared, so anything but short printable ASCII is broken.
        if (!family.isEmpty() && !Utils.isWord(family, Constants.MAX_FAMILY_LENGTH)) {
            reject("sent an invalid game family");
            return null;
        }

        return family;
    }

    /**
     * @param family A family as read, empty for none.
     * @return The family, or the one of every client from before families.
     */

    public static String orDefault(String family) {
        // Clients from before families name none.
        return family.isEmpty() ? Constants.DEFAULT_FAMILY : family;
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
     * @return The session, or null before the client has said hello.
     */

    public Session getSession() {
        return session;
    }

    /**
     * @return The address the connection counts against, or null when it does not.
     */

    public InetAddress getAddress() {
        return address;
    }

    /**
     * @return The bucket for chat messages.
     */

    public RateLimit getChatLimit() {
        return chatLimit;
    }

    /**
     * @return The bucket for party requests and token proofs.
     */

    public RateLimit getPartyLimit() {
        return partyLimit;
    }

    /**
     * @return The bucket for game data.
     */

    public RateLimit getDataLimit() {
        return dataLimit;
    }

    /**
     * @return The bucket for relayed game data.
     */

    public RateLimit getRelayLimit() {
        return relayLimit;
    }

    /**
     * @return The bucket for clock requests.
     */

    public RateLimit getTimeLimit() {
        return timeLimit;
    }

    /**
     * @return The bucket for lobby screen requests.
     */

    public RateLimit getUiLimit() {
        return uiLimit;
    }

    /**
     * @return The bucket that forgives dropped packets over time.
     */

    public RateLimit getStrikeLimit() {
        return strikeLimit;
    }

    /**
     * @param session The session this connection now speaks for.
     */

    public void setSession(Session session) {
        this.session = session;
    }

    /**
     * @param address The address the connection counts against.
     */

    public void setAddress(InetAddress address) {
        this.address = address;
    }
}
