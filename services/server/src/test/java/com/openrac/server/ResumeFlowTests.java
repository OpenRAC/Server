package com.openrac.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.openrac.net.NetworkConstants;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.server.party.PartyController;

import java.util.ArrayList;
import java.util.List;

/**
 * A dropped player's slot: kept for two minutes, taken back only with its token.
 */

public class ResumeFlowTests {
    private final List<Client> clients = new ArrayList<>();

    /**
     * Disconnects every client.
     */

    @AfterEach
    public void closeClients() {
        for (Client client : clients) client.channel.close();

        // What dropped there would wait for a sweep.
        PartyController.sweep(Clock.now() + 3_600_000L);
    }

    private Client connect(String name) {
        Client client = new Client();

        clients.add(client);
        client.helloResuming(name);

        return client;
    }

    /**
     * A RESUME as a client sends one.
     */

    private static OutgoingPacket resume(String code, int id, long token) {
        return new OutgoingPacket(Packets.PARTY.ordinal(), Opcodes.Party.RESUME.ordinal()) {
            {
                writeString(code);
                writeInt(id);
                writeLong(token);
            }
        };
    }

    /**
     * Reads what a member is told of another's connection.
     */

    private static void assertNotice(Client client, int id, boolean back) {
        InputBuffer notice = client.receiveParty(Opcodes.Party.RESUME);

        Assertions.assertEquals(id, notice.readInt());
        Assertions.assertEquals(back ? 1 : 0, notice.readUnsignedByte());
    }

    @Test
    @DisplayName("Says that it takes RESUME")
    public void testFeature() {
        Assertions.assertNotEquals(0, connect("Ratchet").features & NetworkConstants.FEATURE_RESUME);
    }

    @Test
    @DisplayName("A dropped member keeps its id and slot, and comes back on a new connection")
    public void testResume() {
        Client host = connect("Ratchet");
        String code = host.create();
        Client member = connect("Clank");

        member.join(code, 2);
        host.receiveParty(Opcodes.Party.JOINED);

        int id = member.id;
        long token = member.resumeToken;

        member.channel.close();
        assertNotice(host, id, false);
        host.assertSilent(); // No LEFT: the slot waits.

        Client back = connect("Clank");

        Assertions.assertNotEquals(id, back.id);

        back.send(resume(code, id, token));
        back.readGreeting();
        Assertions.assertEquals(id, back.id);

        InputBuffer info = back.receiveParty(Opcodes.Party.INFO);

        Assertions.assertEquals(code, info.readString());
        Assertions.assertEquals(host.id, info.readInt());
        Assertions.assertEquals(2, info.readUnsignedByte());
        assertNotice(host, id, true);

        // It is a member again, on the new connection.
        back.send(new com.openrac.net.packets.out.DataPacket(0, new byte[] {42}));
        Assertions.assertEquals(id, host.receive(Packets.DATA).readInt());
    }

    @Test
    @DisplayName("A wrong token takes nothing")
    public void testWrongToken() {
        Client host = connect("Ratchet");
        String code = host.create();
        Client member = connect("Clank");

        member.join(code, 2);
        host.receiveParty(Opcodes.Party.JOINED);
        member.channel.close();
        assertNotice(host, member.id, false);

        Client thief = connect("Nefarious");

        thief.send(resume(code, member.id, member.resumeToken ^ 1));
        Assertions.assertEquals(Opcodes.Error.RESUME_REFUSED, thief.receiveError());
        host.assertSilent();
    }

    @Test
    @DisplayName("A slot goes after two minutes, and then nobody takes it")
    public void testExpiry() {
        // A host of an older client is never taken for quiet, so only the slot's time counts.
        Client host = new Client();

        clients.add(host);
        host.hello("Ratchet");

        String code = host.create();
        Client member = connect("Clank");

        member.join(code, 2);
        host.receiveParty(Opcodes.Party.JOINED);

        long dropped = Clock.now();

        member.channel.close();
        assertNotice(host, member.id, false);

        PartyController.sweep(dropped + Constants.RESUME_SECONDS * 1000L - 2000L);
        host.assertSilent();

        PartyController.sweep(dropped + Constants.RESUME_SECONDS * 1000L + 2000L);
        Assertions.assertEquals(member.id, host.receiveParty(Opcodes.Party.LEFT).readInt());

        Client late = connect("Clank");

        late.send(resume(code, member.id, member.resumeToken));
        Assertions.assertEquals(Opcodes.Error.RESUME_REFUSED, late.receiveError());
    }

    @Test
    @DisplayName("A second RESUME moves the session on and shuts the first connection out")
    public void testTwoResumes() {
        Client host = connect("Ratchet");
        String code = host.create();
        Client member = connect("Clank");

        member.join(code, 2);
        host.receiveParty(Opcodes.Party.JOINED);

        int id = member.id;

        // Back before the server saw the old connection go.
        Client first = connect("Clank");

        first.send(resume(code, id, member.resumeToken));
        first.readGreeting();
        first.receiveParty(Opcodes.Party.INFO);
        Assertions.assertFalse(member.channel.isActive());
        assertNotice(host, id, true);

        Client second = connect("Clank");

        second.send(resume(code, id, member.resumeToken));
        second.readGreeting();
        second.receiveParty(Opcodes.Party.INFO);
        Assertions.assertFalse(first.channel.isActive());
        assertNotice(host, id, true);
        host.assertSilent();
    }

    @Test
    @DisplayName("A client gone quiet is taken for dropped, and an old client still just leaves")
    public void testQuietAndOld() {
        Client host = connect("Ratchet");
        String code = host.create();
        Client member = connect("Clank");
        Client old = new Client();

        clients.add(old);
        old.hello("Qwark");
        member.join(code, 2);
        host.receiveParty(Opcodes.Party.JOINED);
        old.join(code, 3);
        host.receiveParty(Opcodes.Party.JOINED);
        member.receiveParty(Opcodes.Party.JOINED);

        // An old client that goes is gone, at once.
        old.channel.close();
        Assertions.assertEquals(old.id, host.receiveParty(Opcodes.Party.LEFT).readInt());
        Assertions.assertEquals(old.id, member.receiveParty(Opcodes.Party.LEFT).readInt());

        // Nothing from the member for the idle time: closed, and its slot waits.
        PartyController.sweep(Clock.now() + Constants.PARTY_IDLE_SECONDS * 1000L + 1000L);
        Assertions.assertFalse(member.channel.isActive());
    }
}
