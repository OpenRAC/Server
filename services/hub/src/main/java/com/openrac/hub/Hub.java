package com.openrac.hub;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;

import com.openrac.hub.accounts.AccountController;
import com.openrac.hub.accounts.Database;
import com.openrac.hub.accounts.PostgresAccountStore;
import com.openrac.hub.net.HubChannelInitializer;
import com.openrac.hub.servers.ServerController;
import com.openrac.system.Environment;
import com.openrac.utils.Clock;
import com.openrac.utils.logger.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The hub's entry point: the address players reach first. It binds the hub port, opens the
 * account store, and drops game servers that go quiet. A game server process in "all" mode runs
 * one of these beside itself.
 */

public class Hub {
    private final EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

    private Channel channel;
    private Database database; // Null while accounts live in memory.

    // Drops silent servers and expired sessions; never on an event loop's time.
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hub-sweeper");

        thread.setDaemon(true);

        return thread;
    });

    /**
     * Loads the environment, then starts the hub on its host and port.
     * @param args The command line arguments, as key value pairs (--hub-port 9000).
     * @throws InterruptedException If binding the port is interrupted.
     */

    public static void main(String[] args) throws InterruptedException {
        // Constants read the environment, so it loads before anything else.
        Environment.load(args);

        Log.setLevel(Log.toLevel(Environment.getString("DEBUG_LEVEL", "all")));
        Log.setDebugging(Environment.getBoolean("DEBUGGING", false));

        Hub hub = new Hub();

        hub.start(Constants.HOST, Constants.PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(hub::stop, "shutdown"));
    }

    /**
     * Opens the account store, binds the address and starts accepting connections.
     * @param host The address to listen on.
     * @param port The hub port.
     * @throws InterruptedException If binding the port is interrupted.
     */

    public void start(String host, int port) throws InterruptedException {
        Log.info("Initializing " + Constants.NAME + " " + Constants.VERSION + "...");

        // Without a database the accounts stay in memory, gone on restart.
        if (Constants.DATABASE_URL.isBlank()) Log.notice("No DATABASE_URL: accounts are kept in memory only.");
        else {
            database = new Database(Constants.DATABASE_URL, Constants.DATABASE_USER, Constants.DATABASE_PASSWORD, Constants.DATABASE_POOL_SIZE);
            AccountController.setStore(new PostgresAccountStore(database.getPool()));
        }

        channel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new HubChannelInitializer())
                .bind(host, port)
                .sync()
                .channel();

        sweeper.scheduleWithFixedDelay(() -> ServerController.sweep(Clock.now()), 1, 1, TimeUnit.SECONDS);
        sweeper.scheduleWithFixedDelay(AccountController::sweep, Constants.LOGIN_WINDOW_SECONDS, Constants.LOGIN_WINDOW_SECONDS, TimeUnit.SECONDS);

        Log.notice(Constants.NAME + " is listening on " + host + ":" + port + ".");
    }

    /**
     * Closes the listening channel, the event loops and the database.
     */

    public void stop() {
        Log.info("Shutting down " + Constants.NAME + "...");

        // A failed bind leaves no channel to close.
        if (channel != null) channel.close().syncUninterruptibly();

        sweeper.shutdownNow();

        // Both quiet periods run at once before either is awaited.
        Future<?> boss = bossGroup.shutdownGracefully();
        Future<?> workers = workerGroup.shutdownGracefully();

        boss.syncUninterruptibly();
        workers.syncUninterruptibly();

        // Memory accounts need no closing.
        if (database != null) database.close();

        Log.notice(Constants.NAME + " stopped.");
    }
}
