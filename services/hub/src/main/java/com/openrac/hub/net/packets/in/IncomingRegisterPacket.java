package com.openrac.hub.net.packets.in;

import io.netty.channel.ChannelFutureListener;

import com.openrac.hub.Constants;
import com.openrac.hub.net.Connection;
import com.openrac.hub.net.packets.model.IncomingPacket;
import com.openrac.hub.servers.GameServer;
import com.openrac.hub.servers.ServerController;
import com.openrac.net.NetworkConstants;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Regions;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.ErrorPacket;
import com.openrac.net.packets.out.HubPacket;
import com.openrac.utils.Clock;
import com.openrac.utils.Utils;
import com.openrac.utils.logger.Log;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Lists a game server that registers.
 */

public class IncomingRegisterPacket extends IncomingPacket {
    /**
     * Creates the packet; registering is how a server introduces itself.
     */

    public IncomingRegisterPacket() {
        super(false);
    }

    /**
     * Checks the registration, lists the server and answers with its id.
     * @param connection The connection that registered.
     * @param payload The register packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        // One introduction per connection.
        if (connection.isKnown()) {
            connection.reject("sent a second registration");
            return;
        }

        String key = payload.readString();
        Regions region = Regions.get(payload.readString());
        String host = payload.readString().strip();
        int port = payload.readInt();
        int players = payload.readInt();
        // Servers from before names send none.
        String name = payload.size() > 0 ? payload.readString().strip() : "";

        // Clients name regions by code, so only a listed one.
        if (region == null) {
            connection.reject("sent an unknown region code");
            return;
        }

        // Games must draw the name, and players need a real port.
        if (!Utils.isPrintable(name, NetworkConstants.MAX_SERVER_NAME_LENGTH) || port < 1 || port > 65_535) {
            connection.reject("sent an invalid registration");
            return;
        }

        // A keyed hub lists only its operators' servers.
        if (!Constants.REGION_KEY.isEmpty() && !Constants.REGION_KEY.equals(key)) {
            refuse(connection, Opcodes.Error.REGION_KEY);
            return;
        }

        // A server on the hub's own network leaves players the hub's address.
        if (host.isEmpty() && connection.getChannel().remoteAddress() instanceof InetSocketAddress remote && !isPrivate(remote.getAddress()))
            host = remote.getAddress().getHostAddress();

        GameServer server = ServerController.add(connection.getChannel(), region.name(), name, host, port, players, Clock.now());

        // The list has a bound, and the server tries again later.
        if (server == null) {
            refuse(connection, Opcodes.Error.HUB_FULL);
            return;
        }

        connection.setServer(server);
        connection.getContext().writeAndFlush(new HubPacket(server.getId()));
    }

    /**
     * Tells a server why it is refused, then closes its connection.
     * @param connection The connection that registered.
     * @param reason Why it is refused.
     */

    private void refuse(Connection connection, Opcodes.Error reason) {
        Log.warning(connection.getChannel().remoteAddress() + " was refused as a server: " + reason + ".");

        connection.getContext().writeAndFlush(new ErrorPacket(reason)).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * @param address Where a server connected from.
     * @return Whether it is this machine or a private network: loopback, LAN, link-local or IPv6 ULA.
     */

    private static boolean isPrivate(InetAddress address) {
        // IPv6 unique local addresses (fc00::/7) have no Java check of their own.
        boolean uniqueLocal = address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;

        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || uniqueLocal;
    }
}
