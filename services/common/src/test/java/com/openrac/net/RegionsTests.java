package com.openrac.net;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openrac.net.packets.Regions;
import com.openrac.utils.Utils;

/**
 * The region codes a server may register under, and the server names the lists take.
 */

public class RegionsTests {

    @Nested
    @DisplayName("Code Tests")
    class CodeTests {

        @Test
        @DisplayName("A known code reads in any case and with spaces around it")
        public void testValid() {
            Assertions.assertEquals(Regions.US, Regions.get("US"));
            Assertions.assertEquals(Regions.DE, Regions.get(" de "));
            Assertions.assertEquals(Regions.EU, Regions.get("Eu"));
            Assertions.assertEquals(Regions.LOCAL, Regions.get("Local"));
        }

        @Test
        @DisplayName("A name, a three-letter code or nothing is no region")
        public void testInvalid() {
            Assertions.assertNull(Regions.get("United States"));
            Assertions.assertNull(Regions.get("USA"));
            Assertions.assertNull(Regions.get("UK"));
            Assertions.assertNull(Regions.get(""));
        }

        @Test
        @DisplayName("Every country code is two letters")
        public void testShape() {
            for (Regions region : Regions.values()) {
                // LOCAL is the one code past two letters.
                if (region == Regions.LOCAL) continue;

                Assertions.assertTrue(region.name().matches("[A-Z]{2}"), region.name());
            }
        }
    }

    @Nested
    @DisplayName("Server Name Tests")
    class ServerNameTests {

        @Test
        @DisplayName("A name fits the cap, and an empty one is allowed")
        public void testCap() {
            int cap = NetworkConstants.MAX_SERVER_NAME_LENGTH;

            Assertions.assertTrue(Utils.isPrintable("Thyrannosis USA", cap));
            Assertions.assertTrue(Utils.isPrintable("", cap));
            Assertions.assertTrue(Utils.isPrintable("x".repeat(cap), cap));
            Assertions.assertFalse(Utils.isPrintable("x".repeat(cap + 1), cap));
        }

        @Test
        @DisplayName("A name holds printable ASCII only")
        public void testPrintable() {
            Assertions.assertFalse(Utils.isPrintable("Tab\there", 32));
            Assertions.assertFalse(Utils.isPrintable("Zürich", 32));
            Assertions.assertTrue(Utils.isPrintable("Kerwan #2 (EU)", 32));
        }
    }
}
