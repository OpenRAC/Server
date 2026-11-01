package com.openrac.server.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.openrac.server.Constants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;

/**
 * The mod set store: its rules, its life and eviction, its file, and the real HTTP pipeline.
 */

public class ModSetFlowTests {
    private static final long DAY = 86_400_000L;

    private static final String SHA = "a".repeat(64);

    private static final String MOD = "{\"id\":\"difficulty-mod\",\"version\":\"1.4.0\",\"kind\":\"gameplay\","
            + "\"source\":{\"url\":\"https://github.com/OpenRAC/rac1-mods\",\"path\":\"difficulty-mod\",\"tag\":\"difficulty-mod-v1.4.0\"},"
            + "\"sums\":\"" + SHA + "\",\"settings\":{\"enemy_count\":2,\"hard\":true,\"mode\":\"a\"}}";

    /**
     * A channel with a real remote address, which embedded ones lack.
     */

    private static final class AddressedChannel extends EmbeddedChannel {
        private final InetSocketAddress remote;

        private AddressedChannel(String address) throws IOException {
            super(false, false);

            remote = new InetSocketAddress(InetAddress.getByName(address), 40000);
        }

        @Override
        protected SocketAddress remoteAddress0() {
            return remote;
        }
    }

    /**
     * @param text JSON text.
     * @return It compressed as the launcher does.
     */

    private static byte[] deflate(String text) {
        Deflater deflater = new Deflater();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];

        deflater.setInput(text.getBytes(StandardCharsets.UTF_8));
        deflater.finish();

        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk));

        deflater.end();

        return out.toByteArray();
    }

    /**
     * @param created The set's date.
     * @return A valid set.
     */

    private static String set(String created) {
        return "{\"format\":1,\"name\":\"Ada's set\",\"created\":\"" + created + "\",\"game\":\"rac1\",\"discs\":[\"pal\",\"ntsc\"],"
                + "\"openrac\":\"0.10.1\",\"mods\":[" + MOD + "],\"extras\":[],\"manual\":[{\"id\":\"local-thing\",\"version\":\"1.0.0\",\"name\":\"Thing\"}],"
                + "\"packs\":[{\"id\":\"hd-pack\"}],\"digest\":\"3fa9\"}";
    }

    private static String set() {
        return set("2026-10-31T12:00:00Z");
    }

    /**
     * @param replace The text to change in a valid set.
     * @param with What to change it to.
     * @return Whether the validator refuses the result.
     */

    private static boolean refuses(String replace, String with) {
        String changed = set().replace(replace, with);

        Assertions.assertNotEquals(set(), changed, "The test must change the set.");

        try {
            ModSetValidator.check(deflate(changed));
            return false;
        } catch (InvalidModSetException exception) {
            return true;
        }
    }

    @Nested
    @DisplayName("Validator Tests")
    class ValidatorTests {

        @Test
        @DisplayName("A valid set passes")
        public void testValid() throws InvalidModSetException {
            Assertions.assertEquals(64, ModSetValidator.check(deflate(set())).length());
        }

        @Test
        @DisplayName("The date is not part of a set's identity, its contents are")
        public void testHash() throws InvalidModSetException {
            String first = ModSetValidator.check(deflate(set("2026-10-31T12:00:00Z")));
            String later = ModSetValidator.check(deflate(set("2026-11-02T09:30:00Z")));
            String other = ModSetValidator.check(deflate(set().replace("\"hard\":true", "\"hard\":false")));

            Assertions.assertEquals(first, later);
            Assertions.assertNotEquals(first, other);
        }

        @Test
        @DisplayName("The order of keys does not change a set's identity")
        public void testKeyOrder() throws InvalidModSetException {
            String reordered = set().replace("\"name\":\"Ada's set\",", "").replace("\"format\":1,", "\"format\":1,\"digest\":\"3fa9\",\"name\":\"Ada's set\",")
                    .replace(",\"digest\":\"3fa9\"}", "}");

            Assertions.assertEquals(ModSetValidator.check(deflate(set())), ModSetValidator.check(deflate(reordered)));
        }

        @Test
        @DisplayName("Anything but compressed JSON of one object is refused")
        public void testNotASet() {
            byte[] text = set().getBytes(StandardCharsets.UTF_8);
            byte[] whole = deflate(set());
            byte[] cut = Arrays.copyOf(whole, whole.length / 2);

            Assertions.assertThrows(InvalidModSetException.class, () -> ModSetValidator.check(text));
            Assertions.assertThrows(InvalidModSetException.class, () -> ModSetValidator.check(cut));
            Assertions.assertThrows(InvalidModSetException.class, () -> ModSetValidator.check(deflate("[1,2]")));
            Assertions.assertThrows(InvalidModSetException.class, () -> ModSetValidator.check(deflate("{format:1}")));
            Assertions.assertThrows(InvalidModSetException.class, () -> ModSetValidator.check(deflate(set() + "x")));
        }

        @Test
        @DisplayName("A small body that expands too far is refused")
        public void testBomb() {
            // Valid JSON all the way: only the size refuses it.
            String bomb = set() + " ".repeat(Constants.MODSET_MAX_JSON_BYTES + 1);

            Assertions.assertTrue(deflate(bomb).length < Constants.MODSET_MAX_BODY_BYTES);
            Assertions.assertThrows(InvalidModSetException.class, () -> ModSetValidator.check(deflate(bomb)));
        }

        @Test
        @DisplayName("Keys the format does not have are refused, at every level")
        public void testUnknownKeys() {
            Assertions.assertTrue(refuses("\"format\":1,", "\"format\":1,\"blob\":\"x\","));
            Assertions.assertTrue(refuses("\"kind\":\"gameplay\",", "\"kind\":\"gameplay\",\"blob\":\"x\","));
            Assertions.assertTrue(refuses("\"path\":\"difficulty-mod\",", "\"path\":\"difficulty-mod\",\"asset\":\"x\","));
            Assertions.assertTrue(refuses("\"id\":\"local-thing\",", "\"id\":\"local-thing\",\"url\":\"x\","));
        }

        @Test
        @DisplayName("A set carries no address but a GitHub repository")
        public void testAddresses() {
            Assertions.assertTrue(refuses("https://github.com/OpenRAC/rac1-mods", "https://evil.example/OpenRAC/rac1-mods"));
            Assertions.assertTrue(refuses("https://github.com/OpenRAC/rac1-mods", "http://github.com/OpenRAC/rac1-mods"));
            Assertions.assertTrue(refuses("https://github.com/OpenRAC/rac1-mods", "https://github.com/OpenRAC/rac1-mods/releases/x"));
            Assertions.assertTrue(refuses("Ada's set", "https://evil.example"));
            Assertions.assertTrue(refuses("\"mode\":\"a\"", "\"mode\":\"https://evil.example\""));
        }

        @Test
        @DisplayName("Ids, versions, paths and hashes follow their rules")
        public void testFields() {
            Assertions.assertTrue(refuses("\"id\":\"difficulty-mod\"", "\"id\":\"../evil\""));
            Assertions.assertTrue(refuses("\"version\":\"1.4.0\"", "\"version\":\"latest\""));
            Assertions.assertTrue(refuses("\"path\":\"difficulty-mod\"", "\"path\":\"../../etc\""));
            Assertions.assertTrue(refuses("\"path\":\"difficulty-mod\"", "\"path\":\"/etc\""));
            Assertions.assertTrue(refuses("\"kind\":\"gameplay\"", "\"kind\":\"native\""));
            Assertions.assertTrue(refuses(SHA, "abc"));
            Assertions.assertTrue(refuses("\"format\":1", "\"format\":2"));
            Assertions.assertTrue(refuses("\"enemy_count\":2", "\"enemy_count\":[2]"));
        }

        @Test
        @DisplayName("A mod with neither checksums nor a commit is refused")
        public void testPinned() {
            Assertions.assertTrue(refuses("\"sums\":\"" + SHA + "\",", ""));
        }

        @Test
        @DisplayName("Lists have a limit")
        public void testListLimit() {
            StringBuilder mods = new StringBuilder(MOD);

            for (int i = 0; i < Constants.MODSET_MAX_MODS; i++) mods.append(',').append(MOD);

            Assertions.assertTrue(refuses(MOD, mods.toString()));
        }
    }

    @Nested
    @DisplayName("Store Tests")
    class StoreTests {
        private final AtomicLong time = new AtomicLong(1_000);
        private final Deque<String> codes = new ArrayDeque<>(List.of("AAAAAAAA", "BBBBBBBB", "CCCCCCCC", "DDDDDDDD"));

        private ModSetStore store(int capacity) {
            return new ModSetStore(capacity, 30 * DAY, time::get, codes::removeFirst);
        }

        @Test
        @DisplayName("A set comes back by its code")
        public void testPutGet() {
            ModSetStore store = store(10);
            byte[] body = { 1, 2, 3 };

            Assertions.assertEquals("AAAAAAAA", store.put(body, "h1"));
            Assertions.assertArrayEquals(body, store.get("AAAAAAAA"));
            Assertions.assertNull(store.get("ZZZZZZZZ"));
        }

        @Test
        @DisplayName("The same set keeps its code and refreshes its life")
        public void testDedupe() {
            ModSetStore store = store(10);

            String first = store.put(new byte[] { 1 }, "h1");

            time.addAndGet(20 * DAY);

            Assertions.assertEquals(first, store.put(new byte[] { 1 }, "h1"));
            Assertions.assertEquals(1, store.getSize());

            time.addAndGet(20 * DAY);

            Assertions.assertNotNull(store.get(first));
        }

        @Test
        @DisplayName("A set not read for 30 days is gone, and each read refreshes it")
        public void testExpiry() {
            ModSetStore store = store(10);
            String code = store.put(new byte[] { 1 }, "h1");

            time.addAndGet(29 * DAY);
            Assertions.assertNotNull(store.get(code));

            time.addAndGet(29 * DAY);
            Assertions.assertNotNull(store.get(code));

            time.addAndGet(31 * DAY);
            Assertions.assertNull(store.get(code));
            Assertions.assertEquals(0, store.getSize());
        }

        @Test
        @DisplayName("An expired set can be stored again under a new code")
        public void testExpiredAgain() {
            ModSetStore store = store(10);
            String first = store.put(new byte[] { 1 }, "h1");

            time.addAndGet(31 * DAY);

            Assertions.assertNotEquals(first, store.put(new byte[] { 1 }, "h1"));
        }

        @Test
        @DisplayName("A code already taken is never overwritten")
        public void testCollision() {
            Deque<String> repeats = new ArrayDeque<>(List.of("AAAAAAAA", "AAAAAAAA", "BBBBBBBB"));
            ModSetStore store = new ModSetStore(10, 30 * DAY, time::get, repeats::removeFirst);

            byte[] first = { 1 };
            byte[] second = { 2 };

            Assertions.assertEquals("AAAAAAAA", store.put(first, "h1"));
            Assertions.assertEquals("BBBBBBBB", store.put(second, "h2"));
            Assertions.assertArrayEquals(first, store.get("AAAAAAAA"));
        }

        @Test
        @DisplayName("Past its capacity the store drops the set read longest ago")
        public void testEviction() {
            ModSetStore store = store(2);
            String first = store.put(new byte[] { 1 }, "h1");
            String second = store.put(new byte[] { 2 }, "h2");

            // The first is now the fresher of the two.
            store.get(first);

            String third = store.put(new byte[] { 3 }, "h3");

            Assertions.assertNotNull(store.get(first));
            Assertions.assertNull(store.get(second));
            Assertions.assertNotNull(store.get(third));
        }

        @Test
        @DisplayName("Generated codes are 8 characters of the alphabet")
        public void testRandomCodes() {
            String code = new ModSetStore().put(new byte[] { 1 }, "h1");

            Assertions.assertEquals(8, code.length());
            Assertions.assertTrue(code.chars().allMatch(c -> Constants.CODE_ALPHABET.indexOf(c) >= 0));
        }

        @Test
        @DisplayName("A saved store loads with its codes, read times and dedupe")
        public void testPersistence(@TempDir Path folder) throws IOException {
            Path file = folder.resolve("sub/modsets.bin");
            ModSetStore store = store(10);
            byte[] body = deflate(set());
            String code = store.put(body, ModSetValidator.class.getSimpleName());

            time.addAndGet(10 * DAY);
            store.save(file);

            ModSetStore loaded = new ModSetStore(10, 30 * DAY, time::get, () -> "XXXXXXXX");

            loaded.load(file);

            Assertions.assertArrayEquals(body, loaded.get(code));

            time.addAndGet(31 * DAY);

            ModSetStore late = new ModSetStore(10, 30 * DAY, time::get, () -> "XXXXXXXX");

            late.load(file);

            Assertions.assertEquals(0, late.getSize());
        }

        @Test
        @DisplayName("A missing file is an empty store, a foreign one an error")
        public void testBadFile(@TempDir Path folder) throws IOException {
            Path file = folder.resolve("modsets.bin");
            ModSetStore store = store(10);

            store.load(file);

            Assertions.assertEquals(0, store.getSize());

            Files.write(file, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });

            Assertions.assertThrows(IOException.class, () -> store.load(file));
        }
    }

    @Nested
    @DisplayName("HTTP Tests")
    class HttpTests {
        private final ModSetStore store = new ModSetStore(10, 30 * DAY, System::currentTimeMillis, () -> "K7M29QXR");
        private final HttpChannelInitializer initializer = new HttpChannelInitializer(store);

        /**
         * Sends one request through the real pipeline, from an address of its own.
         * @return The whole response.
         */

        private FullHttpResponse send(String address, HttpMethod method, String uri, byte[] body) throws Exception {
            AddressedChannel server = new AddressedChannel(address);
            EmbeddedChannel client = new EmbeddedChannel(new HttpClientCodec(), new HttpObjectAggregator(1 << 20));

            initializer.initPipeline(server.pipeline());
            server.register();

            ByteBuf content = Unpooled.wrappedBuffer(body);
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, content);

            request.headers().set(HttpHeaderNames.HOST, "test");
            request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);

            client.writeOutbound(request);

            ByteBuf bytes;

            while ((bytes = client.readOutbound()) != null) server.writeInbound(bytes);

            server.runPendingTasks();

            while ((bytes = server.readOutbound()) != null) client.writeInbound(bytes);

            client.finish();
            server.close();

            return client.readInbound();
        }

        @Test
        @DisplayName("A set goes up and comes back, whatever the code's case or dash")
        public void testRoundTrip() throws Exception {
            byte[] body = deflate(set());
            FullHttpResponse put = send("10.0.0.1", HttpMethod.PUT, "/v1/modsets", body);

            Assertions.assertEquals(200, put.status().code());
            Assertions.assertEquals("{\"code\":\"K7M29QXR\"}", put.content().toString(StandardCharsets.UTF_8));

            for (String code : List.of("K7M29QXR", "k7m2-9qxr")) {
                FullHttpResponse get = send("10.0.0.1", HttpMethod.GET, "/v1/modsets/" + code, new byte[0]);

                Assertions.assertEquals(200, get.status().code());
                Assertions.assertArrayEquals(body, ByteBufUtil.getBytes(get.content()));
            }
        }

        @Test
        @DisplayName("Requests that make no sense get the right status")
        public void testStatuses() throws Exception {
            Assertions.assertEquals(404, send("10.0.0.2", HttpMethod.GET, "/v1/modsets/AAAAAAAA", new byte[0]).status().code());
            Assertions.assertEquals(404, send("10.0.0.2", HttpMethod.GET, "/", new byte[0]).status().code());
            Assertions.assertEquals(405, send("10.0.0.2", HttpMethod.GET, "/v1/modsets", new byte[0]).status().code());
            Assertions.assertEquals(405, send("10.0.0.2", HttpMethod.DELETE, "/v1/modsets/AAAAAAAA", new byte[0]).status().code());
            Assertions.assertEquals(400, send("10.0.0.2", HttpMethod.PUT, "/v1/modsets", "not a set".getBytes(StandardCharsets.UTF_8)).status().code());
        }

        @Test
        @DisplayName("A body over the size limit is refused before it is read")
        public void testTooLarge() throws Exception {
            byte[] big = new byte[Constants.MODSET_MAX_BODY_BYTES + 1];

            Assertions.assertEquals(413, send("10.0.0.3", HttpMethod.PUT, "/v1/modsets", big).status().code());
        }

        @Test
        @DisplayName("Each address has its own rate")
        public void testRates() throws Exception {
            byte[] body = deflate(set());

            for (int i = 0; i < Constants.MODSET_PUTS_PER_MINUTE; i++)
                Assertions.assertEquals(200, send("10.0.0.4", HttpMethod.PUT, "/v1/modsets", body).status().code());

            Assertions.assertEquals(429, send("10.0.0.4", HttpMethod.PUT, "/v1/modsets", body).status().code());
            Assertions.assertEquals(200, send("10.0.0.5", HttpMethod.PUT, "/v1/modsets", body).status().code());

            for (int i = 0; i < Constants.MODSET_GETS_PER_MINUTE; i++)
                Assertions.assertEquals(200, send("10.0.0.6", HttpMethod.GET, "/v1/modsets/K7M29QXR", new byte[0]).status().code());

            Assertions.assertEquals(429, send("10.0.0.6", HttpMethod.GET, "/v1/modsets/K7M29QXR", new byte[0]).status().code());
        }
    }
}
