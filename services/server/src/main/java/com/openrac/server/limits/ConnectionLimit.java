package com.openrac.server.limits;

import com.openrac.server.Constants;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts open connections per address, so one machine cannot exhaust the server with sockets.
 * Connections open and close on any worker thread, so the counts are updated atomically.
 */

public class ConnectionLimit {
    private static final Map<InetAddress, Integer> open = new ConcurrentHashMap<>();

    /**
     * Counts a new connection from the address, unless it already has the most allowed.
     * @param address The connecting address.
     * @return Whether the connection may stay open.
     */

    public static boolean open(InetAddress address) {
        boolean[] allowed = { true };

        open.compute(address, (key, count) -> {
            int current = count == null ? 0 : count;

            // At the cap the count stays as it is, and the connection is refused.
            if (current >= Constants.MAX_CONNECTIONS_PER_ADDRESS) {
                allowed[0] = false;
                return current;
            }

            return current + 1;
        });

        return allowed[0];
    }

    /**
     * Forgets a closed connection from the address.
     * @param address The address whose connection closed.
     */

    public static void close(InetAddress address) {
        // The last connection removes the address altogether.
        open.computeIfPresent(address, (key, count) -> count > 1 ? count - 1 : null);
    }
}
