package com.openrac.hub.net.packets.in;

import com.openrac.hub.Constants;
import com.openrac.hub.net.Connection;
import com.openrac.hub.net.packets.model.IncomingPacket;
import com.openrac.net.NetworkConstants;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.UdpPacket;
import com.openrac.utils.Utils;
import com.openrac.utils.logger.Log;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Greets a player's client: an id, and the bits of what the hub takes.
 */

public class IncomingHelloPacket extends IncomingPacket {
    private static final AtomicInteger nextGuestId = new AtomicInteger(1);

    /**
     * Creates the packet; a hello is how a client introduces itself.
     */

    public IncomingHelloPacket() {
        super(false);
    }

    /**
     * Marks the connection as a client and answers with its id and the hub's feature bits.
     * @param connection The connection that said hello.
     * @param payload The hello packet's payload.
     */

    @Override
    public void handle(Connection connection, InputBuffer payload) {
        // One introduction per connection.
        if (connection.isKnown()) {
            connection.reject("sent a second hello");
            return;
        }

        String version = payload.readString();
        String name = payload.readString().strip();

        // Blank or oversized fields only come from a broken client.
        if (!Utils.isValid(version, Constants.MAX_VERSION_LENGTH) || !Utils.isValid(name, Constants.MAX_NAME_LENGTH)) {
            connection.reject("sent an invalid hello");
            return;
        }

        connection.setGuest(true);

        int id = nextGuestId.getAndIncrement();

        Log.info("Guest " + id + " (" + name + ", version " + version + ") from " + connection.getChannel().remoteAddress() + ".");

        connection.getContext().write(new HelloPacket(id));

        // The hub takes no datagrams, so token and port are 0.
        connection.getContext().writeAndFlush(new UdpPacket(0, 0, NetworkConstants.FEATURE_REGIONS | NetworkConstants.FEATURE_SERVERS, 0));
    }
}
