package com.openrac.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.openrac.net.NetworkConstants;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.out.UiPacket;
import com.openrac.server.ui.UiController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the UI descriptions on the real pipeline: the built-in one of a game, a host's file for a
 * family or a game, the server block that stays on the server, and the notices answering actions.
 */

public class UiFlowTests {
    private final List<Client> clients = new ArrayList<>();

    @TempDir
    Path directory;

    /**
     * Starts each test with no host files.
     */

    @BeforeEach
    public void clearDescriptions() {
        UiController.load(directory);
    }

    /**
     * Disconnects every client.
     */

    @AfterEach
    public void closeClients() {
        for (Client client : clients) client.channel.close();
    }

    /**
     * Connects a player and says hello.
     * @param name The display name.
     * @return The greeted client.
     */

    private Client connect(String name) {
        Client client = new Client();

        clients.add(client);
        client.hello(name);

        return client;
    }

    /**
     * Asks for a family's description and reads the answer.
     * @param client The asking client.
     * @param family The game family.
     * @return The description's payload after the opcode: revision, then JSON.
     */

    private static InputBuffer get(Client client, String family) {
        client.send(new UiPacket(Opcodes.Ui.GET, family));

        InputBuffer payload = client.receive(Packets.UI);

        Assertions.assertEquals(Opcodes.Ui.DESCRIPTION, Opcodes.Ui.get(payload.readUnsignedByte()));

        return payload;
    }

    /**
     * Writes a host's description file and reloads the directory.
     * @param stem The family or game it is for.
     * @param json The file's contents.
     * @throws IOException If the file cannot be written.
     */

    private void write(String stem, String json) throws IOException {
        Files.writeString(directory.resolve(stem + ".json"), json, StandardCharsets.UTF_8);
        UiController.load(directory);
    }

    @Nested
    @DisplayName("Wire Tests")
    class WireTests {

        @Test
        @DisplayName("The server says it answers UI requests")
        public void testFeatureBit() {
            Assertions.assertEquals(NetworkConstants.FEATURE_UI, connect("Ratchet").features & NetworkConstants.FEATURE_UI);
        }

        @Test
        @DisplayName("An unknown UI opcode closes the connection")
        public void testServerOpcodeRejected() {
            Client client = connect("Ratchet");

            client.send(new UiPacket(Opcodes.Ui.NOTICE, "x"));

            Assertions.assertFalse(client.channel.isActive());
        }
    }

    @Nested
    @DisplayName("Description Tests")
    class DescriptionTests {

        @Test
        @DisplayName("Up Your Arsenal gets its built-in description of the game's own menus")
        public void testBuiltIn() {
            InputBuffer payload = get(connect("Ratchet"), "rac3-pal-1");

            Assertions.assertEquals(1, payload.readInt());

            String json = new String(payload.readRemainingBytes(), StandardCharsets.UTF_8);

            Assertions.assertTrue(json.contains("\"game_list\""));
        }

        @Test
        @DisplayName("A game without a description gets none")
        public void testNone() {
            InputBuffer payload = get(connect("Ratchet"), "rac1-60-1");

            Assertions.assertEquals(0, payload.readInt());
            Assertions.assertEquals(0, payload.readRemainingBytes().length);
        }

        @Test
        @DisplayName("A family's file wins over its game's, which wins over the built-in one")
        public void testFallbacks() throws IOException {
            write("rac3", "{\"schema\": 1, \"revision\": 5}");
            write("rac3-pal-1", "{\"schema\": 1, \"revision\": 7}");

            Assertions.assertEquals(7, get(connect("Ratchet"), "rac3-pal-1").readInt());
            Assertions.assertEquals(5, get(connect("Clank"), "rac3-ntsc-1").readInt());
        }

        @Test
        @DisplayName("Invalid files are skipped and the next description serves")
        public void testInvalidSkipped() throws IOException {
            write("rac3", "{\"schema\": 2, \"revision\": 5}");
            write("rac3-pal-1", "[1, 2]");

            Assertions.assertEquals(1, get(connect("Ratchet"), "rac3-pal-1").readInt());
        }

        @Test
        @DisplayName("The server block never reaches clients")
        public void testServerBlockStays() throws IOException {
            write("rac3", "{\"schema\": 1, \"server\": {\"notices\": {\"vote\": \"Counted\"}}}");

            InputBuffer payload = get(connect("Ratchet"), "rac3-pal-1");

            payload.readInt();

            String json = new String(payload.readRemainingBytes(), StandardCharsets.UTF_8);

            Assertions.assertFalse(json.contains("notices"));
        }
    }

    @Nested
    @DisplayName("Action Tests")
    class ActionTests {

        @Test
        @DisplayName("An action the description names is answered with its notice")
        public void testNotice() throws IOException {
            write("rac3", "{\"schema\": 1, \"server\": {\"notices\": {\"vote\": \"Vote counted\"}}}");

            Client client = connect("Ratchet");

            get(client, "rac3-pal-1");
            client.send(new UiPacket("vote", 1));

            InputBuffer payload = client.receive(Packets.UI);

            Assertions.assertEquals(Opcodes.Ui.NOTICE, Opcodes.Ui.get(payload.readUnsignedByte()));
            Assertions.assertEquals("Vote counted", payload.readString());
        }

        @Test
        @DisplayName("An action without a notice, or before any description, gets no answer")
        public void testSilent() throws IOException {
            write("rac3", "{\"schema\": 1, \"server\": {\"notices\": {\"vote\": \"Vote counted\"}}}");

            Client early = connect("Ratchet");

            early.send(new UiPacket("vote", 1));
            early.assertSilent();

            Client client = connect("Clank");

            get(client, "rac3-pal-1");
            client.send(new UiPacket("other", 0));
            client.assertSilent();
        }
    }
}
