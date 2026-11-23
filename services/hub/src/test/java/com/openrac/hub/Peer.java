package com.openrac.hub;

import org.junit.jupiter.api.Assertions;

import com.openrac.hub.net.HubChannelInitializer;
import com.openrac.net.EmbeddedPeer;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.RegisterPacket;

/**
 * One connection to the hub's pipeline, run on an embedded channel: a player's client or a game
 * server.
 */

public class Peer extends EmbeddedPeer {
    /**
     * Builds the hub's pipeline on the embedded channel.
     */

    public Peer() {
        super(new HubChannelInitializer());
    }

    /**
     * Says hello as a client and reads the greeting.
     * @param name The display name.
     * @return The feature bits the hub said it takes.
     */

    public int hello(String name) {
        send(new HelloPacket("0.1.0", name));
        receive(Packets.HELLO).readInt();

        InputBuffer udp = receive(Packets.UDP);

        udp.readLong();
        udp.readInt();

        return udp.readUnsignedByte();
    }

    /**
     * Registers as a game server and reads its id.
     * @param region The region it is listed under.
     * @param port Its game port.
     * @return The id the hub gave it.
     */

    public int register(String region, int port) {
        send(new RegisterPacket("", region, "", port, 0, region + " box"));

        InputBuffer payload = receiveHub(Opcodes.Hub.REGISTERED);

        return payload.readInt();
    }

    /**
     * @param expected The hub opcode expected next.
     * @return The payload after the opcode.
     */

    public InputBuffer receiveHub(Opcodes.Hub expected) {
        InputBuffer payload = receive(Packets.HUB);

        Assertions.assertEquals(expected, Opcodes.Hub.get(payload.readUnsignedByte()));

        return payload;
    }

    /**
     * @param expected The servers opcode expected next.
     * @return The payload after the opcode.
     */

    public InputBuffer receiveServers(Opcodes.Servers expected) {
        InputBuffer payload = receive(Packets.SERVERS);

        Assertions.assertEquals(expected, Opcodes.Servers.get(payload.readUnsignedByte()));

        return payload;
    }
}
