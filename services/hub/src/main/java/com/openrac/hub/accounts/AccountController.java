package com.openrac.hub.accounts;

import at.favre.lib.crypto.bcrypt.BCrypt;
import io.netty.channel.Channel;

import com.openrac.hub.Constants;
import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.model.OutputBuffer;
import com.openrac.net.packets.out.AccountPacket;
import com.openrac.net.packets.out.ErrorPacket;
import com.openrac.net.packets.out.HubPacket;
import com.openrac.utils.logger.Log;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Logs players in to their accounts. A name nobody has registers on its first login, as the game's
 * own flow expects; later logins must match its password. A login hands out a session token, which
 * game servers check with the hub. Hashing and the store both block, so the work runs on its own
 * threads and answers down the asking connection.
 */

public class AccountController {
    private static final SecureRandom random = new SecureRandom();

    // Wrong passwords per address in the current window.
    private static final Map<InetAddress, Integer> failures = new ConcurrentHashMap<>();

    private static volatile AccountStore store = new MemoryAccountStore();

    private static volatile Executor executor = Executors.newFixedThreadPool(Constants.ACCOUNT_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "accounts");

        thread.setDaemon(true);

        return thread;
    });

    /**
     * Logs a client in, registering the name when it is new.
     * @param channel The client's connection, which gets the answer.
     * @param address The client's address, null for a test channel.
     * @param name The account name.
     * @param password The password.
     */

    public static void login(Channel channel, InetAddress address, String name, String password) {
        // Bad names and passwords never reach the store.
        if (!isValidName(name) || password.length() < Constants.MIN_PASSWORD_LENGTH || password.length() > Constants.MAX_PASSWORD_LENGTH) {
            channel.writeAndFlush(new ErrorPacket(Opcodes.Error.INVALID_ACCOUNT));
            return;
        }

        // A guessing address waits out the window.
        if (address != null && failures.getOrDefault(address, 0) >= Constants.LOGIN_FAILURES) {
            channel.writeAndFlush(new ErrorPacket(Opcodes.Error.TRY_LATER));
            return;
        }

        run(channel, () -> {
            Account account = store.find(name);
            boolean created = false;

            // A new name registers with this password.
            if (account == null) {
                account = store.create(name, BCrypt.withDefaults().hashToString(Constants.PASSWORD_COST, password.toCharArray()));
                created = account != null;
            }

            // Registered by another login meanwhile, so it is checked like any other.
            if (account == null) account = store.find(name);

            // The name's owner chose another password.
            if (!created && !BCrypt.verifyer().verify(password.toCharArray(), account.passwordHash()).verified) {
                // Test channels have no address to count against.
                if (address != null) failures.merge(address, 1, Integer::sum);

                Log.info("Wrong password for account " + account.name() + " from " + channel.remoteAddress() + ".");
                channel.writeAndFlush(new ErrorPacket(Opcodes.Error.WRONG_PASSWORD));
                return;
            }

            String token = createToken();

            store.touch(account.id());
            store.addSession(hash(token), account.id(), Instant.now().plus(Duration.ofDays(Constants.SESSION_DAYS)));

            // The log tells a registration from a login.
            Log.info((created ? "Registered" : "Logged in") + " account " + account.id() + " (" + account.name() + ").");
            channel.writeAndFlush(new AccountPacket(account.id(), account.name(), token, created));
        });
    }

    /**
     * Ends a client's session.
     * @param channel The client's connection.
     * @param token The session token it was given.
     */

    public static void logout(Channel channel, String token) {
        run(channel, () -> store.removeSession(hash(token)));
    }

    /**
     * Answers a game server asking whose session a player's token is.
     * @param channel The server's connection, which gets the answer.
     * @param request The server's number for the question.
     * @param token The token the player gave the server.
     */

    public static void verify(Channel channel, int request, String token) {
        run(channel, () -> {
            Account account = store.findSession(hash(token), Instant.now());
            OutputBuffer answer = new OutputBuffer();

            answer.writeInt(request);

            // An account id of 0 says the token is no session.
            answer.writeLong(account == null ? 0 : account.id());
            answer.writeString(account == null ? "" : account.name());

            channel.writeAndFlush(new HubPacket(Opcodes.Hub.VERIFIED, answer));
        });
    }

    /**
     * Forgets expired sessions and starts a new window for wrong passwords.
     */

    public static void sweep() {
        failures.clear();

        executor.execute(() -> {
            try {
                store.removeExpired(Instant.now());
            } catch (RuntimeException exception) {
                Log.error("Could not forget expired sessions: " + exception.getMessage() + ".");
            }
        });
    }

    /**
     * Runs account work off the event loop. A store that fails answers TRY_LATER.
     * @param channel The connection that asked.
     * @param work The work, which answers on its own.
     */

    private static void run(Channel channel, Runnable work) {
        executor.execute(() -> {
            try {
                work.run();
            } catch (RuntimeException exception) {
                Log.error("Account request from " + channel.remoteAddress() + " failed: " + exception.getMessage() + ".");
                channel.writeAndFlush(new ErrorPacket(Opcodes.Error.TRY_LATER));
            }
        });
    }

    /**
     * @return A new session token: random bytes, URL-safe text.
     */

    private static String createToken() {
        byte[] bytes = new byte[Constants.TOKEN_BYTES];

        random.nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * @param token A session token.
     * @return Its SHA-256 as hex, which is all the store keeps.
     */

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            // Every Java runtime ships SHA-256.
            throw new IllegalStateException(exception);
        }
    }

    /**
     * @param name An account name as typed.
     * @return Whether it is short printable ASCII with no edge spaces.
     */

    public static boolean isValidName(String name) {
        return !name.isBlank() && name.length() <= Constants.MAX_ACCOUNT_NAME_LENGTH && name.equals(name.strip())
                && name.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
    }

    /**
     * @param store Where accounts are kept from now on.
     */

    public static void setStore(AccountStore store) {
        AccountController.store = store;
    }

    /**
     * @param executor What runs account work; tests run it inline.
     */

    public static void setExecutor(Executor executor) {
        AccountController.executor = executor;
    }
}
