package com.openrac.net.limits;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The open connections each address may hold, counted on opening and closing.
 */

public class ConnectionLimitTests {

    @Nested
    @DisplayName("Count Tests")
    class CountTests {

        @Test
        @DisplayName("An address opens up to its cap, and a close frees one place")
        public void testCap() throws UnknownHostException {
            InetAddress address = InetAddress.getByName("192.0.2.1");

            Assertions.assertTrue(ConnectionLimit.open(address, 2));
            Assertions.assertTrue(ConnectionLimit.open(address, 2));
            Assertions.assertFalse(ConnectionLimit.open(address, 2));

            ConnectionLimit.close(address);

            Assertions.assertTrue(ConnectionLimit.open(address, 2));
            Assertions.assertFalse(ConnectionLimit.open(address, 2));

            ConnectionLimit.close(address);
            ConnectionLimit.close(address);
        }

        @Test
        @DisplayName("Addresses are counted apart, and closing them all starts afresh")
        public void testAddresses() throws UnknownHostException {
            InetAddress first = InetAddress.getByName("192.0.2.2");
            InetAddress second = InetAddress.getByName("192.0.2.3");

            Assertions.assertTrue(ConnectionLimit.open(first, 1));
            Assertions.assertTrue(ConnectionLimit.open(second, 1));
            Assertions.assertFalse(ConnectionLimit.open(first, 1));

            ConnectionLimit.close(first);
            ConnectionLimit.close(first);

            Assertions.assertTrue(ConnectionLimit.open(first, 1));

            ConnectionLimit.close(first);
            ConnectionLimit.close(second);
        }
    }
}
