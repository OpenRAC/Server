package com.openrac.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;

import com.openrac.server.net.ServerChannelInitializer;
import com.openrac.system.Environment;
import com.openrac.utils.logger.Log;

/**
 * The entry point. It binds the TCP port, accepts game connections on Netty's event loops and
 * shuts them down cleanly when the process is stopped.
 */

public class Server {
    // One thread accepts connections, the workers run their pipelines.
    private final EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

    private Channel channel;

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

        Server server = new Server();

        server.start(Constants.HOST, Constants.PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "shutdown"));
    }

    /**
     * Binds the address and starts accepting connections.
     * @param host The address to listen on.
     * @param port The TCP port to listen on.
     * @throws InterruptedException If binding the port is interrupted.
     */

    public void start(String host, int port) throws InterruptedException {
        Log.info("Initializing " + Constants.NAME + " " + Constants.VERSION + " (Java " + Runtime.version().feature() + ", log level " + Log.getLevel() + ")...");

        // Game traffic is small and frequent, so never wait to batch it.
        channel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ServerChannelInitializer())
                .bind(host, port)
                .sync()
                .channel();

        Log.notice(Constants.NAME + " is listening on " + host + ":" + port + ".");
    }

    /**
     * Closes the listening channel and shuts the event loops down gracefully.
     */

    public void stop() {
        Log.info("Shutting down " + Constants.NAME + "...");

        // A failed bind leaves no channel to close.
        if (channel != null) channel.close().syncUninterruptibly();

        // Both quiet periods run at once before either is awaited.
        Future<?> boss = bossGroup.shutdownGracefully();
        Future<?> workers = workerGroup.shutdownGracefully();

        boss.syncUninterruptibly();
        workers.syncUninterruptibly();

        Log.notice(Constants.NAME + " stopped.");
    }
}
