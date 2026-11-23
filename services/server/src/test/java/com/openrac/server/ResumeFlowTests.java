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
import com.openrac.utils.Clock;

import java.util.ArrayList;
import java.util.List;

/**
 * A dropped player's slot: kept for two minutes, taken back only with its token.
 */

public class ResumeFlowTests {
    private final List<Client> clients = new ArrayList<>();

    private String code; // The code of the party trio() made.

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
     * A client the sweep leaves alone, however far on it is told the time is.
     */

    private Client connectQuiet(String name) {
        Client client = new Client();

        clients.add(client);
        client.hello(name);

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
        // No LEFT: the slot waits.
        host.assertSilent();

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
        // An older client's host is never quiet; only the slot's time counts.
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

        // A member silent for the idle time is closed; its slot waits.
        PartyController.sweep(Clock.now() + Constants.PARTY_IDLE_SECONDS * 1000L + 1000L);
        Assertions.assertFalse(member.channel.isActive());
    }

    /**
     * A host's word on who follows it, or a new host declining.
     */

    private static OutgoingPacket hostChanged(boolean decline, int... ids) {
        return new OutgoingPacket(Packets.PARTY.ordinal(), Opcodes.Party.HOST_CHANGED.ordinal()) {
            {
                writeUnsignedByte(decline ? 1 : 0);
                writeUnsignedByte(ids.length);

                for (int id : ids) writeInt(id);
            }
        };
    }

    private static void assertHost(Client client, int id, int epoch) {
        InputBuffer change = client.receiveParty(Opcodes.Party.HOST_CHANGED);

        Assertions.assertEquals(id, change.readInt());
        Assertions.assertEquals(epoch, change.readInt());
    }

    /**
     * A party of three whose host named its followers; each client's queue is empty.
     */

    private Client[] trio() {
        Client host = connect("Ratchet");

        code = host.create();

        Client first = connectQuiet("Clank");
        Client second = connectQuiet("Qwark");

        first.join(code, 2);
        host.receiveParty(Opcodes.Party.JOINED);
        second.join(code, 3);
        host.receiveParty(Opcodes.Party.JOINED);
        first.receiveParty(Opcodes.Party.JOINED);
        host.send(hostChanged(false, second.id, first.id));

        return new Client[] {host, first, second};
    }

    @Test
    @DisplayName("Past the grace the host's first choice hosts, and data goes by the new host")
    public void testTakeOver() {
        Client[] party = trio();
        long dropped = Clock.now();

        party[0].channel.close();
        assertNotice(party[1], party[0].id, false);
        assertNotice(party[2], party[0].id, false);

        // Those who waited keep talking.
        PartyController.sweep(dropped + Constants.HOST_GRACE_SECONDS * 1000L - 1000L);
        party[1].assertSilent();

        PartyController.sweep(dropped + Constants.HOST_GRACE_SECONDS * 1000L + 1000L);
        assertHost(party[1], party[2].id, 1);
        assertHost(party[2], party[2].id, 1);

        // The other member and the new host now reach each other.
        party[1].send(new com.openrac.net.packets.out.DataPacket(0, new byte[] {7}));
        Assertions.assertEquals(party[1].id, party[2].receive(Packets.DATA).readInt());
        party[2].send(new com.openrac.net.packets.out.DataPacket(0, new byte[] {8}));
        Assertions.assertEquals(party[2].id, party[1].receive(Packets.DATA).readInt());
    }

    @Test
    @DisplayName("Back within the grace, the host is the host, and nothing changes hands")
    public void testHostBackInTime() {
        Client[] party = trio();
        long dropped = Clock.now();

        party[1].send(hostChanged(false, party[1].id));
        // A member has no say.
        Assertions.assertEquals(Opcodes.Error.NOT_HOST, party[1].receiveError());

        party[0].channel.close();
        assertNotice(party[1], party[0].id, false);
        assertNotice(party[2], party[0].id, false);

        // Nobody finds its way in meanwhile.
        Client stranger = connectQuiet("Nefarious");

        stranger.send(new com.openrac.net.packets.out.PartyPacket(Opcodes.Party.JOIN, code));
        Assertions.assertEquals(Opcodes.Error.PARTY_NOT_FOUND, stranger.receiveError());

        Client back = connect("Ratchet");

        back.send(resume(code, party[0].id, party[0].resumeToken));
        back.readGreeting();
        back.receiveParty(Opcodes.Party.INFO);
        assertNotice(party[1], party[0].id, true);
        assertNotice(party[2], party[0].id, true);

        /* The grace it left runs out for nobody. (That far ahead it looks quiet, and is closed: a new
           drop, with a grace of its own.) */
        PartyController.sweep(dropped + Constants.HOST_GRACE_SECONDS * 1000L + 1000L);
        assertNotice(party[1], party[0].id, false);
        assertNotice(party[2], party[0].id, false);
        party[1].assertSilent();
        party[2].assertSilent();
    }

    @Test
    @DisplayName("A new host that cannot declines, and the next one hosts")
    public void testDecline() {
        Client[] party = trio();

        party[0].send(new com.openrac.net.packets.out.PartyPacket(Opcodes.Party.LEAVE));
        assertHost(party[1], party[2].id, 1);
        assertHost(party[2], party[2].id, 1);

        party[2].send(hostChanged(true));
        assertHost(party[1], party[1].id, 2);
        assertHost(party[2], party[1].id, 2);

        // With no one left, the party is over.
        party[1].send(hostChanged(true));
        party[1].receiveParty(Opcodes.Party.END);
        party[2].receiveParty(Opcodes.Party.END);
    }

    @Test
    @DisplayName("A host that named nobody ends the party, as ever")
    public void testNoSuccessors() {
        Client host = connect("Ratchet");
        String code = host.create();
        Client member = connect("Clank");

        member.join(code, 2);
        host.receiveParty(Opcodes.Party.JOINED);
        host.send(new com.openrac.net.packets.out.PartyPacket(Opcodes.Party.LEAVE));
        member.receiveParty(Opcodes.Party.END);
    }

    @Test
    @DisplayName("An old host that comes back finds a member's place, and who hosts")
    public void testOldHostBack() {
        Client host = connect("Ratchet");
        String code = host.create();
        Client member = connectQuiet("Clank");

        member.join(code, 2);
        host.receiveParty(Opcodes.Party.JOINED);
        host.send(hostChanged(false, member.id));

        long dropped = Clock.now();
        int id = host.id;
        long token = host.resumeToken;

        host.channel.close();
        assertNotice(member, id, false);
        PartyController.sweep(dropped + Constants.HOST_GRACE_SECONDS * 1000L + 1000L);
        assertHost(member, member.id, 1);

        Client back = connect("Ratchet");

        back.send(resume(code, id, token));
        back.readGreeting();

        InputBuffer info = back.receiveParty(Opcodes.Party.INFO);

        Assertions.assertEquals(code, info.readString());
        Assertions.assertEquals(member.id, info.readInt());

        int count = info.readUnsignedByte();

        for (int i = 0; i < count; i++) {
            info.readInt();
            info.readString();
        }

        info.readString();
        Assertions.assertEquals(1, info.readInt());
        assertNotice(member, id, true);

        // Its data is a member's now.
        back.send(new com.openrac.net.packets.out.DataPacket(0, new byte[] {9}));
        Assertions.assertEquals(id, member.receive(Packets.DATA).readInt());
    }
}
