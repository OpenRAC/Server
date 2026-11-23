package com.openrac.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;

import com.openrac.hub.Hub;
import com.openrac.net.NetworkConstants;
import com.openrac.server.http.HttpChannelInitializer;
import com.openrac.server.http.ModSetStore;
import com.openrac.server.hub.HubController;
import com.openrac.server.hub.Mode;
import com.openrac.server.net.ServerChannelInitializer;
import com.openrac.server.net.ServerDatagramHandler;
import com.openrac.server.party.PartyController;
import com.openrac.server.ui.UiController;
import com.openrac.system.Environment;
import com.openrac.utils.Clock;
import com.openrac.utils.logger.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The entry point. It binds the TCP and UDP port, accepts game connections on Netty's event loops
 * and shuts them down cleanly when the process is stopped. A second TCP port takes HTTP requests
 * for the launcher's mod sets. In "all" mode it also runs the hub, on its own port.
 */

public class Server {
    // One thread accepts connections, the workers run their pipelines.
    private final EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

    private Channel channel;
    private Channel udpChannel; // The one datagram socket every session shares.
    private Channel httpChannel; // The launcher's mod sets.
    private Hub hub; // Null unless this process runs its own hub.

    // Gives up on what waited too long, off the event loops.
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "party-sweeper");

        thread.setDaemon(true);

        return thread;
    });

    private final ModSetStore modSets = new ModSetStore();
    private final Path modSetFile = Path.of(Constants.MODSET_FILE);

    // Saving writes a file, which must not hold up an event loop.
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mod-set-saver");

        thread.setDaemon(true);

        return thread;
    });

    /**
     * Loads the environment, then starts the server on its host and port.
     * @param args The command line arguments, as key value pairs (--port 9000).
     * @throws InterruptedException If binding the port is interrupted.
     */

    public static void main(String[] args) throws InterruptedException {
        // Constants read the environment, so it loads before anything else.
        Environment.load(args);

        Log.setLevel(Log.toLevel(Environment.getString("DEBUG_LEVEL", "all")));
        Log.setDebugging(Environment.getBoolean("DEBUGGING", false));

        // A server the hub or its players could not name never starts.
        if (!checkSettings()) System.exit(1);

        UiController.load(Path.of(Constants.UI_DIRECTORY));

        Server server = new Server();

        server.start(Constants.HOST, Constants.PORT);
        server.startModSets(Constants.HOST, Constants.HTTP_PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "shutdown"));
    }

    /**
     * Checks the region code and the server name before anything binds, logging what is wrong.
     * @return Whether both are usable.
     */

    private static boolean checkSettings() {
        // REGION_NAME is read for one release, then dropped.
        if (!Constants.getOldRegion().isEmpty()) Log.warning("REGION_NAME is deprecated, set REGION to a region code (docs/configuration.md).");

        // Clients can only name the codes they know.
        if (Constants.REGION == null) {
            Log.error("Unknown region code \"" + Constants.REGION_SETTING + "\", not starting. The codes are in docs/configuration.md.");
            return false;
        }

        // The hub refuses a long name, and games draw ASCII only.
        if (!Constants.isServerNameValid()) {
            Log.error("SERVER_NAME must be at most " + NetworkConstants.MAX_SERVER_NAME_LENGTH + " printable ASCII characters, not starting.");
            return false;
        }

        return true;
    }

    /**
     * Binds the address and starts accepting connections.
     * @param host The address to listen on.
     * @param port The TCP and UDP port to listen on.
     * @throws InterruptedException If binding the port is interrupted.
     */

    public void start(String host, int port) throws InterruptedException {
        Log.info("Initializing " + Constants.NAME + " " + Constants.VERSION + " (Java " + Runtime.version().feature() + ", log level " + Log.getLevel() + ")...");

        // One process may run the hub its region registers with, started first.
        if (Constants.MODE == Mode.ALL) {
            hub = new Hub();
            hub.start(host, com.openrac.hub.Constants.PORT);
        }

        // Game traffic is small and frequent, so never wait to batch it.
        channel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ServerChannelInitializer())
                .bind(host, port)
                .sync()
                .channel();

        // Game state may also travel over UDP, on the same port.
        udpChannel = new Bootstrap()
                .group(workerGroup)
                .channel(NioDatagramChannel.class)
                .handler(new ServerDatagramHandler())
                .bind(host, port)
                .sync()
                .channel();

        sweeper.scheduleWithFixedDelay(() -> PartyController.sweep(Clock.now()), 1, 1, TimeUnit.SECONDS);

        HubController.start(workerGroup, HubController.getAddress(Constants.MODE));

        Log.notice(Constants.NAME + " is listening on " + host + ":" + port + " (TCP and UDP), as " + Constants.MODE.getName() + ".");
    }

    /**
     * Loads the stored mod sets, binds the HTTP port and saves the sets now and then.
     * @param host The address to listen on.
     * @param port The HTTP port to listen on.
     * @throws InterruptedException If binding the port is interrupted.
     */

    public void startModSets(String host, int port) throws InterruptedException {
        try {
            modSets.load(modSetFile);
        } catch (IOException exception) {
            // Codes lost at worst; the launcher then asks for a new one.
            Log.error("Could not read " + modSetFile + ", starting empty: " + exception.getMessage() + ".");
        }

        httpChannel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new HttpChannelInitializer(modSets))
                .bind(host, port)
                .sync()
                .channel();

        saver.scheduleWithFixedDelay(this::saveModSets, Constants.MODSET_SAVE_MINUTES, Constants.MODSET_SAVE_MINUTES, TimeUnit.MINUTES);

        Log.notice("Mod sets are on " + host + ":" + port + " (HTTP), " + modSets.getSize() + " stored.");
    }

    /**
     * Writes the mod sets to their file, so that a restart keeps the codes.
     */

    private void saveModSets() {
        try {
            modSets.save(modSetFile);
        } catch (IOException exception) {
            Log.error("Could not write " + modSetFile + ": " + exception.getMessage() + ".");
        }
    }

    /**
     * Closes the listening channel and shuts the event loops down gracefully.
     */

    public void stop() {
        Log.info("Shutting down " + Constants.NAME + "...");

        // Players stop being sent here before the ports close.
        HubController.stop();

        // A failed bind leaves no channel to close.
        if (channel != null) channel.close().syncUninterruptibly();
        if (udpChannel != null) udpChannel.close().syncUninterruptibly();

        // Without the side-port there is nothing new to save.
        if (httpChannel != null) {
            httpChannel.close().syncUninterruptibly();
            saveModSets();
        }

        saver.shutdownNow();
        sweeper.shutdownNow();

        // Both quiet periods run at once before either is awaited.
        Future<?> boss = bossGroup.shutdownGracefully();
        Future<?> workers = workerGroup.shutdownGracefully();

        boss.syncUninterruptibly();
        workers.syncUninterruptibly();

        // Only "all" mode runs a hub of its own.
        if (hub != null) hub.stop();

        Log.notice(Constants.NAME + " stopped.");
    }
}
