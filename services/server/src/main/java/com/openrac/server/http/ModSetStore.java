package com.openrac.server.http;

import com.openrac.server.Constants;
import com.openrac.utils.logger.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The mod sets behind short codes. Reading a set refreshes its time to live, so a party's code stays
 * alive, and past the capacity the set read longest ago goes. The same set always gets the same
 * code. Every method locks the store: Netty's workers and the saving thread share it.
 */

public class ModSetStore {
    private static final int FILE_MAGIC = 0x4F524D53; // "ORMS".
    private static final int FILE_VERSION = 1;

    private static final SecureRandom random = new SecureRandom();

    private final int capacity;
    private final long timeToLive; // Milliseconds since the last read.

    private final LongSupplier clock;
    private final Supplier<String> codes;

    // Oldest read first: the order eviction goes in.
    private final LinkedHashMap<String, Stored> sets = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, String> codeByHash = new HashMap<>();

    /**
     * Creates a store on the wall clock, its size and time to live from the environment.
     */

    public ModSetStore() {
        this(Constants.MODSET_CAPACITY, Constants.MODSET_TTL_DAYS * 86_400_000L, System::currentTimeMillis, ModSetStore::randomCode);
    }

    /**
     * Creates a store.
     * @param capacity The most sets kept.
     * @param timeToLive Milliseconds a set lives after its last read.
     * @param clock The time in milliseconds.
     * @param codes Makes a new code.
     */

    public ModSetStore(int capacity, long timeToLive, LongSupplier clock, Supplier<String> codes) {
        this.capacity = capacity;
        this.timeToLive = timeToLive;
        this.clock = clock;
        this.codes = codes;
    }

    /**
     * Keeps a checked set.
     * @param body The compressed set.
     * @param hash Its canonical hash.
     * @return Its code: the code it already has, when it is stored.
     */

    public synchronized String put(byte[] body, String hash) {
        long now = clock.getAsLong();
        String known = codeByHash.get(hash);

        // The same set keeps its code; storing it counts as a read.
        if (known != null && read(known, now) != null) return known;

        String code = newCode(now);

        sets.put(code, new Stored(body, hash, now));
        codeByHash.put(hash, code);

        trim();

        return code;
    }

    /**
     * Fetches a set and refreshes its time to live.
     * @param code The code.
     * @return The compressed set, or null when the code is unknown or has expired.
     */

    public synchronized byte[] get(String code) {
        Stored stored = read(code, clock.getAsLong());

        // An unknown code and an expired one read the same.
        return stored == null ? null : stored.body;
    }

    /**
     * Drops every expired set.
     */

    public synchronized void sweep() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, Stored>> entries = sets.entrySet().iterator();

        while (entries.hasNext()) {
            Stored stored = entries.next().getValue();

            if (now - stored.read <= timeToLive) continue;

            codeByHash.remove(stored.hash);
            entries.remove();
        }
    }

    /**
     * Writes every set to a file, replacing it in one step.
     * @param file The file.
     * @throws IOException If it cannot be written.
     */

    public synchronized void save(Path file) throws IOException {
        sweep();

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

        // A file name alone has no parent to create.
        if (file.getParent() != null) Files.createDirectories(file.getParent());

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
            out.writeInt(FILE_MAGIC);
            out.writeInt(FILE_VERSION);
            out.writeInt(sets.size());

            for (Map.Entry<String, Stored> entry : sets.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeLong(entry.getValue().read);
                out.writeInt(entry.getValue().body.length);
                out.write(entry.getValue().body);
            }
        }

        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Reads back what {@link #save} wrote. A set that no longer passes the checks is skipped.
     * @param file The file; a missing one leaves the store empty.
     * @throws IOException If it cannot be read.
     */

    public synchronized void load(Path file) throws IOException {
        // The first start has nothing to load.
        if (!Files.exists(file)) return;

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            // Anything else was not written by this store.
            if (in.readInt() != FILE_MAGIC || in.readInt() != FILE_VERSION) throw new IOException("not a mod set store");

            int count = in.readInt();
            List<Stored> kept = new ArrayList<>();
            List<String> codesRead = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                String code = in.readUTF();
                long read = in.readLong();
                int length = in.readInt();

                // A damaged length must not allocate gigabytes.
                if (length < 0 || length > Constants.MODSET_MAX_BODY_BYTES) throw new IOException("damaged mod set store");

                byte[] body = in.readNBytes(length);

                try {
                    kept.add(new Stored(body, ModSetValidator.check(body), read));
                    codesRead.add(code);
                } catch (InvalidModSetException exception) {
                    Log.warning("Skipped stored mod set " + code + ": " + exception.getMessage() + ".");
                }
            }

            for (int i = 0; i < kept.size(); i++) {
                sets.put(codesRead.get(i), kept.get(i));
                codeByHash.put(kept.get(i).hash, codesRead.get(i));
            }
        }

        sweep();
    }

    /**
     * Finds a live set, marking it read.
     * @param code The code.
     * @param now The time.
     * @return The set, or null.
     */

    private Stored read(String code, long now) {
        Stored stored = sets.get(code);

        if (stored == null) return null;

        // Expiry is checked here too: the sweep only runs now and then.
        if (now - stored.read > timeToLive) {
            sets.remove(code);
            codeByHash.remove(stored.hash);
            return null;
        }

        stored.read = now;
        return stored;
    }

    /**
     * Makes a code no live set has.
     * @param now The time.
     * @return The code.
     */

    private String newCode(long now) {
        while (true) {
            String code = codes.get();

            // 40 random bits rarely collide, but a taken code stays untouched.
            if (read(code, now) == null) return code;
        }
    }

    /**
     * Evicts the sets read longest ago until the store fits its capacity.
     */

    private void trim() {
        Iterator<Map.Entry<String, Stored>> entries = sets.entrySet().iterator();

        while (sets.size() > capacity && entries.hasNext()) {
            codeByHash.remove(entries.next().getValue().hash);
            entries.remove();
        }
    }

    /**
     * @return A random code of the party alphabet, which has no characters that read alike.
     */

    private static String randomCode() {
        StringBuilder code = new StringBuilder();

        for (int i = 0; i < Constants.MODSET_CODE_LENGTH; i++) code.append(Constants.CODE_ALPHABET.charAt(random.nextInt(Constants.CODE_ALPHABET.length())));

        return code.toString();
    }

    /**
     * @return How many sets are stored.
     */

    public synchronized int getSize() {
        return sets.size();
    }

    /**
     * One stored set.
     */

    private static final class Stored {
        private final byte[] body;
        private final String hash;
        private long read; // When it was last read, in milliseconds.

        /**
         * @param body The compressed set.
         * @param hash Its canonical hash.
         * @param read When it was last read.
         */

        private Stored(byte[] body, String hash, long read) {
            this.body = body;
            this.hash = hash;
            this.read = read;
        }
    }
}
