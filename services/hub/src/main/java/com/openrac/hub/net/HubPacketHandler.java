package com.openrac.hub.net;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import com.openrac.hub.Constants;
import com.openrac.hub.accounts.AccountController;
import com.openrac.hub.servers.GameServer;
import com.openrac.hub.servers.ServerController;
import com.openrac.net.NetworkConstants;
import com.openrac.net.limits.ConnectionLimit;
import com.openrac.net.limits.RateLimit;
import com.openrac.net.netty.handlers.PacketHandler;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.ErrorPacket;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.HubPacket;
import com.openrac.net.packets.out.TimePacket;
import com.openrac.net.packets.out.UdpPacket;
import com.openrac.utils.Clock;
import com.openrac.utils.Utils;
import com.openrac.utils.logger.Log;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles one connection to the hub. A player's client says HELLO and may then log in, list the
 * game servers and watch them change; a game server sends REGISTER instead and then its heartbeat.
 * Each connection gets its own instance, so its fields are only touched on its event loop.
 */

public class HubPacketHandler extends PacketHandler {
    private static final AtomicInteger nextGuestId = new AtomicInteger(1);

    private InetAddress address; // Set while the connection counts against its address.

    private boolean guest; // A client that said hello.
    private GameServer server; // A game server that registered, else null.

    private final RateLimit requestLimit = new RateLimit(Constants.REQUEST_BURST, Constants.REQUEST_PER_SECOND);

    /**
     * Admits a new connection within its address's limit and gives it a deadline to introduce itself.
     * @param ctx The channel handler context.
     */

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Only network connections have an address to count against.
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress remote) {
            // One machine cannot exhaust the hub with sockets.
            if (!ConnectionLimit.open(remote.getAddress(), Constants.MAX_CONNECTIONS_PER_ADDRESS)) {
                reject(ctx, "has too many connections open");
                return;
            }

            address = remote.getAddress();
        }

        // A connection that never introduces itself would hold a socket for nothing.
        ctx.executor().schedule(() -> {
            // Still a stranger and still open when the deadline comes.
            if (!isKnown() && ctx.channel().isActive()) reject(ctx, "did not say hello or register in time");
        }, Constants.HELLO_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        ctx.fireChannelActive();
    }

    /**
     * Takes a closed server's connection off the list.
     * @param ctx The channel handler context.
     */

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // A refused connection never counted against its address.
        if (address != null) ConnectionLimit.close(address);

        // A client's watch ends with its channel, so only servers leave.
        if (server != null) ServerController.remove(server);

        ctx.fireChannelInactive();
    }

    /**
     * Routes one packet.
     * @param ctx The channel handler context.
     * @param packet The packet id as an enum.
     * @param payload The rest of the packet.
     */

    @Override
    protected void route(ChannelHandlerContext ctx, Packets packet, InputBuffer payload) {
        // A connection says what it is before anything else.
        if (!isKnown() && packet != Packets.HELLO && packet != Packets.REGISTER) {
            reject(ctx, "sent " + packet + " before hello or register");
            return;
        }

        // Servers report on a schedule, so only clients can flood.
        if (guest && !requestLimit.take()) return;

        switch (packet) {
            case HELLO -> handleHello(ctx, payload);
            case REGISTER -> handleRegister(ctx, payload);
            case HUB -> handleHub(ctx, payload);
            case REGIONS -> {
                // A server asking for the list is closed by the check.
                if (isGuest(ctx, packet)) ctx.writeAndFlush(ServerController.getRegionsPacket());
            }
            case SERVERS -> handleServers(ctx, payload);
            case ACCOUNT -> handleAccount(ctx, payload);
            case TIME -> ctx.writeAndFlush(new TimePacket(payload.readLong(), Clock.now()));

            // Lobbies, parties and game data live on the game servers.
            default -> reject(ctx, "sent " + packet + ", which the hub does not take");
        }
    }

    /**
     * Greets a player's client: an id, and the bits of what the hub takes.
     * @param ctx The channel handler context.
     * @param payload The hello packet's payload.
     */

    private void handleHello(ChannelHandlerContext ctx, InputBuffer payload) {
        // One introduction per connection.
        if (isKnown()) {
            reject(ctx, "sent a second hello");
            return;
        }

        String version = payload.readString();
        String name = payload.readString().strip();

        // Blank or oversized fields only come from a broken client.
        if (!Utils.isValid(version, Constants.MAX_VERSION_LENGTH) || !Utils.isValid(name, Constants.MAX_NAME_LENGTH)) {
            reject(ctx, "sent an invalid hello");
            return;
        }

        guest = true;

        int id = nextGuestId.getAndIncrement();

        Log.info("Guest " + id + " (" + name + ", version " + version + ") from " + ctx.channel().remoteAddress() + ".");

        ctx.write(new HelloPacket(id));

        // The hub takes no datagrams, so token and port are 0.
        ctx.writeAndFlush(new UdpPacket(0, 0, NetworkConstants.FEATURE_REGIONS | NetworkConstants.FEATURE_SERVERS, 0));
    }

    /**
     * Lists a game server that registers.
     * @param ctx The channel handler context.
     * @param payload The register packet's payload.
     */

    private void handleRegister(ChannelHandlerContext ctx, InputBuffer payload) {
        // One introduction per connection.
        if (isKnown()) {
            reject(ctx, "sent a second registration");
            return;
        }

        String key = payload.readString();
        String region = payload.readString().strip();
        String host = payload.readString().strip();
        int port = payload.readInt();
        int players = payload.readInt();
        String name = payload.size() > 0 ? payload.readString().strip() : region; // Servers from before names send none.

        // A listed server needs names that fit and a real port.
        if (!Utils.isValid(region, Constants.MAX_NAME_LENGTH) || !Utils.isValid(name, Constants.MAX_NAME_LENGTH) || port < 1 || port > 65_535) {
            reject(ctx, "sent an invalid registration");
            return;
        }

        // A keyed hub lists only its operators' servers.
        if (!Constants.REGION_KEY.isEmpty() && !Constants.REGION_KEY.equals(key)) {
            refuse(ctx, Opcodes.Error.REGION_KEY);
            return;
        }

        // A server on the hub's own network leaves players the hub's address.
        if (host.isEmpty() && ctx.channel().remoteAddress() instanceof InetSocketAddress remote && !isPrivate(remote.getAddress()))
            host = remote.getAddress().getHostAddress();

        server = ServerController.add(ctx.channel(), region, name, host, port, players, Clock.now());

        // The list has a bound, and the server tries again later.
        if (server == null) {
            refuse(ctx, Opcodes.Error.HUB_FULL);
            return;
        }

        ctx.writeAndFlush(new HubPacket(server.getId()));
    }

    /**
     * Runs a registered server's heartbeat, its leaving and its session checks.
     * @param ctx The channel handler context.
     * @param payload The hub packet's payload.
     */

    private void handleHub(ChannelHandlerContext ctx, InputBuffer payload) {
        // Only a registered server speaks on the hub link.
        if (server == null) {
            reject(ctx, "sent HUB without registering");
            return;
        }

        switch (Opcodes.Hub.get(payload.readUnsignedByte())) {
            case STATUS -> ServerController.status(server, payload.readInt(), payload.readInt(), Clock.now());
            case CLOSING -> ServerController.remove(server);
            case VERIFY -> {
                int request = payload.readInt();
                String token = payload.readString();

                // A token past the limit is no token the hub gave out.
                if (token.length() > Constants.MAX_TOKEN_LENGTH) {
                    reject(ctx, "sent an oversized token");
                    return;
                }

                AccountController.verify(ctx.channel(), request, token);
            }

            // The rest only the hub sends.
            case null, default -> reject(ctx, "sent an invalid hub opcode");
        }
    }

    /**
     * Starts a client's watch on the server list.
     * @param ctx The channel handler context.
     * @param payload The servers packet's payload.
     */

    private void handleServers(ChannelHandlerContext ctx, InputBuffer payload) {
        // Servers have no use for the list.
        if (!isGuest(ctx, Packets.SERVERS)) return;

        // Only WATCH comes from a client. The family waits for filters.
        if (Opcodes.Servers.get(payload.readUnsignedByte()) != Opcodes.Servers.WATCH) {
            reject(ctx, "sent an invalid servers opcode");
            return;
        }

        ServerController.watch(ctx.channel());
    }

    /**
     * Logs a client in to its account, or out of it.
     * @param ctx The channel handler context.
     * @param payload The account packet's payload.
     */

    private void handleAccount(ChannelHandlerContext ctx, InputBuffer payload) {
        // Servers check tokens through VERIFY, never log in.
        if (!isGuest(ctx, Packets.ACCOUNT)) return;

        switch (Opcodes.Account.get(payload.readUnsignedByte())) {
            case LOGIN -> AccountController.login(ctx.channel(), address, payload.readString(), payload.readString());
            case LOGOUT -> {
                String token = payload.readString();

                // A token past the limit is no token the hub gave out.
                if (token.length() > Constants.MAX_TOKEN_LENGTH) {
                    reject(ctx, "sent an oversized token");
                    return;
                }

                AccountController.logout(ctx.channel(), token);
            }

            // TOKEN belongs to game servers, LOGGED_IN to the hub.
            case null, default -> reject(ctx, "sent an invalid account opcode");
        }
    }

    /**
     * Tells a server why it is refused, then closes its connection.
     * @param ctx The channel handler context.
     * @param reason Why it is refused.
     */

    private void refuse(ChannelHandlerContext ctx, Opcodes.Error reason) {
        Log.warning(ctx.channel().remoteAddress() + " was refused as a server: " + reason + ".");

        ctx.writeAndFlush(new ErrorPacket(reason)).addListener(ChannelFutureListener.CLOSE);
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

    /**
     * @param ctx The channel handler context.
     * @param packet The packet that needs a client.
     * @return Whether the connection is a client; a server is closed.
     */

    private boolean isGuest(ChannelHandlerContext ctx, Packets packet) {
        // A server sending a client's request is broken.
        if (!guest) reject(ctx, "sent " + packet + ", which only clients send");

        return guest;
    }

    /**
     * @return Whether the connection has said hello or registered.
     */

    private boolean isKnown() {
        return guest || server != null;
    }
}
