package com.openrac.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds a session by its id, for the datagrams that name theirs. Sessions are added on hello and
 * removed on disconnect from any worker thread, and looked up from the datagram loop.
 */

public class SessionRegistry {
    private static final Map<Integer, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Registers a session that has said hello.
     * @param session The new session.
     */

    public static void add(Session session) {
        sessions.put(session.getId(), session);
    }

    /**
     * Forgets a session whose connection closed.
     * @param session The closed session.
     */

    public static void remove(Session session) {
        sessions.remove(session.getId());
    }

    /**
     * @param id A session id read from a datagram.
     * @return The session with the id, or null.
     */

    public static Session get(int id) {
        return sessions.get(id);
    }

    /**
     * @return How many sessions are connected.
     */

    public static int getCount() {
        return sessions.size();
    }
}
