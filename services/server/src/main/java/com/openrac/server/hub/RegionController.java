package com.openrac.server.hub;

import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.net.packets.out.RegionsPacket;
import com.openrac.server.Constants;
import com.openrac.server.Session;
import com.openrac.server.SessionRegistry;
import com.openrac.utils.logger.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The hub's list of regions. Region servers register over their own connection and leave the list
 * when it closes; in single-process mode the hub lists itself as one region.
 */

public class RegionController {
    // Kept in registration order, so the list reads the same every time.
    private static final Map<Session, Region> regions = new LinkedHashMap<>();

    /**
     * Takes or refreshes a region server's registration.
     * @param session The region server's connection.
     * @param key The key it gave.
     * @param name The region's name.
     * @param host The address players reach it on, empty for the one it connected from.
     * @param port Its port.
     * @param players How many players it has now.
     * @return False when the key is wrong, and the connection should close.
     */

    public static synchronized boolean register(Session session, String key, String name, String host, int port, int players) {
        // A keyed hub lists only its operators' regions.
        if (!Constants.REGION_KEY.isEmpty() && !Constants.REGION_KEY.equals(key)) return false;

        Region known = regions.get(session);

        // A refresh only carries the player count.
        if (known != null) {
            known.setPlayers(players);
            return true;
        }

        // The list is bounded like every other the server sends.
        if (regions.size() >= Constants.MAX_REGIONS) return true;

        regions.put(session, new Region(name, host, port, players));

        Log.info("Region " + name + " registered from session " + session.getId() + ".");
        return true;
    }

    /**
     * Drops a region whose connection closed.
     * @param session The connection that closed.
     */

    public static synchronized void remove(Session session) {
        Region region = regions.remove(session);

        // Most connections are players, not regions.
        if (region != null) Log.info("Region " + region.getName() + " left.");
    }

    /**
     * Sends the session the regions it may play in.
     * @param session The session asking.
     */

    public static synchronized void list(Session session) {
        OutputBuffer listing = new OutputBuffer();
        int count = 0;

        // A hub-only process has no lobbies of its own.
        if (Constants.MODE != Mode.HUB) {
            listing.append(new Region(Constants.REGION_NAME, Constants.REGION_HOST, Constants.PORT, SessionRegistry.getCount()).serialize());
            count++;
        }

        for (Region region : regions.values()) {
            listing.append(region.serialize());
            count++;
        }

        session.send(new RegionsPacket(count, listing));
    }
}
