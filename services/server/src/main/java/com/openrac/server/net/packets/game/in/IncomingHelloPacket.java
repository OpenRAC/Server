package com.openrac.server.net.packets.game.in;

import com.openrac.net.NetworkConstants;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.UdpPacket;
import com.openrac.server.Constants;
import com.openrac.server.Session;
import com.openrac.server.SessionRegistry;
import com.openrac.server.net.Connection;
import com.openrac.server.net.packets.model.IncomingPacket;
import com.openrac.utils.Utils;
import com.openrac.utils.logger.Log;

/**
 * Creates the session from the client's version and display name.
 */

public class IncomingHelloPacket extends IncomingPacket {
    /**
     * Creates the packet; the greeting is the one packet that needs no session.
     */

    public IncomingHelloPacket() {
        super(false);
    }

    /**
     * Opens the session and answers with its id and the UDP offer.
     * @param connection The connection that said hello.
     * @param payload The hello packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        // One greeting per connection.
        if (connection.getSession() != null) {
            connection.reject("sent a second hello");
            return;
        }

        String version = payload.readString();
        String name = payload.readString().strip();
        // Older clients send none.
        int features = payload.size() > 0 ? payload.readUnsignedByte() : 0;

        // Blank or oversized fields only come from a broken client.
        if (!Utils.isValid(version, Constants.MAX_VERSION_LENGTH) || !Utils.isValid(name, Constants.MAX_NAME_LENGTH)) {
            connection.reject("sent an invalid hello");
            return;
        }

        Session session = new Session(connection.getChannel(), name, (features & NetworkConstants.CLIENT_RESUMES) != 0,
                (features & NetworkConstants.CLIENT_SLOTS) != 0, (features & NetworkConstants.CLIENT_KEEPS_ALIVE) != 0);

        connection.setSession(session);

        Log.info("Session " + session.getId() + " (" + name + ", version " + version + ") from " + connection.getChannel().remoteAddress() + ".");

        SessionRegistry.add(session);

        session.send(new HelloPacket(session.getId()));

        // Shipped clients read hello as it was, so UDP comes separately.
        session.send(new UdpPacket(session.getToken(), Constants.PORT, Constants.FEATURES, session.getResumeToken()));
    }
}
