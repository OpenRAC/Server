package com.openrac.server;

import org.junit.jupiter.api.Assertions;

import com.openrac.net.EmbeddedPeer;
import com.openrac.net.NetworkConstants;
import com.openrac.net.netty.PacketChannelInitializer;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.HelloPacket;
import com.openrac.net.packets.out.PartyPacket;
import com.openrac.server.net.ServerChannelInitializer;

import java.util.Locale;

/**
 * One TCP connection to the server's pipeline, or another one, run on an embedded channel.
 */

class Client extends EmbeddedPeer {
    int id;
    long token; // The UDP token the server sent after hello.
    int features; // The bits of what the server said it takes.
    long resumeToken; // The token that takes this session back.

    /**
     * Builds the server's pipeline on the embedded channel.
     */

    Client() {
        this(new ServerChannelInitializer());
    }

    /**
     * Builds another pipeline on the embedded channel, such as the hub's or a hub link's.
     * @param initializer The pipeline to build.
     */

    Client(PacketChannelInitializer initializer) {
        super(initializer);
    }

    /**
     * Says hello, then reads the session id and the UDP offer.
     * @param name The display name.
     */

    void hello(String name) {
        send(new HelloPacket("0.1.0", name));
        readGreeting();
    }

    /**
     * Says hello as a client that comes back with RESUME.
     * @param name The display name.
     */

    void helloResuming(String name) {
        send(new HelloPacket("0.1.0", name, NetworkConstants.CLIENT_RESUMES));
        readGreeting();
    }

    /**
     * Reads the session id and the UDP offer.
     */

    void readGreeting() {
        id = receive(Packets.HELLO).readInt();

        InputBuffer udp = receive(Packets.UDP);

        token = udp.readLong();
        Assertions.assertEquals(Constants.PORT, udp.readInt());
        features = udp.readUnsignedByte();
        resumeToken = udp.readLong();
    }

    /**
     * Creates a party.
     * @return The party's join code.
     */

    String create() {
        send(new PartyPacket(Opcodes.Party.CREATE));

        InputBuffer info = receiveParty(Opcodes.Party.INFO);
        String code = info.readString();

        Assertions.assertEquals(id, info.readInt());
        Assertions.assertEquals(1, info.readUnsignedByte());

        return code;
    }

    /**
     * Joins a party, and checks the member count it is told.
     * @param code The join code.
     * @param members How many members the party has once it joined.
     */

    void join(String code, int members) {
        send(new PartyPacket(Opcodes.Party.JOIN, code));

        InputBuffer info = receiveParty(Opcodes.Party.INFO);

        Assertions.assertEquals(code.toUpperCase(Locale.ROOT), info.readString());
        info.readInt();
        Assertions.assertEquals(members, info.readUnsignedByte());
    }

    /**
     * Reads the next packet, expecting a party notice with the opcode.
     * @param expected The party opcode expected next.
     * @return The payload after the opcode.
     */

    InputBuffer receiveParty(Opcodes.Party expected) {
        InputBuffer payload = receive(Packets.PARTY);

        Assertions.assertEquals(expected, Opcodes.Party.get(payload.readUnsignedByte()));

        return payload;
    }
}
