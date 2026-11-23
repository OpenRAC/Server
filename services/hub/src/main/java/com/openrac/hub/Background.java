package com.openrac.hub;

import io.netty.channel.Channel;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.out.ErrorPacket;
import com.openrac.utils.logger.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The hub's threads for work that blocks: password hashes and the database. Work answers down
 * the connection that asked, and a failure answers TRY_LATER.
 */

public class Background {
    private static volatile Executor executor = Executors.newFixedThreadPool(Constants.ACCOUNT_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "hub-work");

        thread.setDaemon(true);

        return thread;
    });

    /**
     * Runs work off the event loops.
     * @param channel The connection that asked, told TRY_LATER if the work fails.
     * @param work The work, which answers on its own.
     */

    public static void run(Channel channel, Runnable work) {
        executor.execute(() -> {
            try {
                work.run();
            } catch (RuntimeException exception) {
                Log.error("Request from " + channel.remoteAddress() + " failed: " + exception.getMessage() + ".");
                channel.writeAndFlush(new ErrorPacket(Opcodes.Error.TRY_LATER));
            }
        });
    }

    /**
     * Runs upkeep nobody waits for, logging a failure.
     * @param what What the work does, for the log.
     * @param work The work.
     */

    public static void run(String what, Runnable work) {
        executor.execute(() -> {
            try {
                work.run();
            } catch (RuntimeException exception) {
                Log.error("Could not " + what + ": " + exception.getMessage() + ".");
            }
        });
    }

    /**
     * @param executor What runs the work from now on; tests run it inline.
     */

    public static void setExecutor(Executor executor) {
        Background.executor = executor;
    }
}
