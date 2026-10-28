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
     * Starts the server on the port from the first argument, the environment, or the default.
     * @param args The command line arguments.
     * @throws InterruptedException If binding the port is interrupted.
     */

    public static void main(String[] args) throws InterruptedException {
        Server server = new Server();

        server.start(getPort(args));

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "shutdown"));
    }

    /**
     * Binds the port and starts accepting connections.
     * @param port The TCP port to listen on.
     * @throws InterruptedException If binding the port is interrupted.
     */

    public void start(int port) throws InterruptedException {
        Log.info("Initializing " + Constants.NAME + " " + Constants.VERSION + " (Java " + Runtime.version().feature() + ", log level " + Log.getLevel() + ")...");

        // Game traffic is small and frequent, so never wait to batch it.
        channel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ServerChannelInitializer())
                .bind(port)
                .sync()
                .channel();

        Log.notice(Constants.NAME + " is listening on port " + port + ".");
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

    /**
     * @param args The command line arguments.
     * @return The port to listen on.
     */

    private static int getPort(String[] args) {
        // An argument wins over the environment.
        if (args.length > 0) return Integer.parseInt(args[0]);

        String port = System.getenv(Constants.PORT_ENV);

        // An unset variable falls back to the default port.
        return port == null || port.isBlank() ? Constants.DEFAULT_PORT : Integer.parseInt(port.strip());
    }
}
