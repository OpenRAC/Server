package com.openrac.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.openrac.utils.Utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the environment from files of the test's own, in a temporary folder: .env.defaults, then a
 * .env over it, then the command line over both.
 */

public class EnvironmentTests {
    @TempDir
    Path folder;

    /**
     * Writes the defaults and a local override, and points the loader at them.
     * @throws IOException If a file cannot be written.
     */

    @BeforeEach
    public void writeFiles() throws IOException {
        Files.writeString(folder.resolve(".env.defaults"), "TEST_PORT=7770\nTEST_NAME=Default\nTEST_FLAG=false\nTEST_KEPT=kept\n");
        Files.writeString(folder.resolve(".env"), "TEST_NAME=Local\n");
        System.setProperty("env.dir", folder.toString());
    }

    /**
     * Points the loader back at the working folder.
     */

    @AfterEach
    public void clearFolder() {
        System.clearProperty("env.dir");
    }

    @Nested
    @DisplayName("Load Tests")
    class LoadTests {

        @Test
        @DisplayName("A local .env overrides the defaults, and the command line overrides both")
        public void testOrder() {
            Environment.load(new String[] { "--test-port", "9000" });

            Assertions.assertEquals(9000, Environment.getInt("TEST_PORT", 0));
            Assertions.assertEquals("Local", Environment.getString("TEST_NAME", ""));
            Assertions.assertEquals("kept", Environment.getString("TEST_KEPT", ""));
        }

        @Test
        @DisplayName("An odd number of arguments is ignored, and the files still load")
        public void testOddArguments() {
            Environment.load(new String[] { "--test-flag" });

            Assertions.assertFalse(Environment.getBoolean("TEST_FLAG", true));
            Assertions.assertEquals(7770, Environment.getInt("TEST_PORT", 0));
        }

        @Test
        @DisplayName("A key nobody set reads as the fallback")
        public void testFallback() {
            Environment.load(new String[0]);

            Assertions.assertEquals(42, Environment.getInt("TEST_UNSET_NUMBER", 42));
            Assertions.assertEquals("none", Environment.getString("TEST_UNSET_TEXT", "none"));
            Assertions.assertTrue(Environment.getBoolean("TEST_UNSET_FLAG", true));
        }
    }

    @Nested
    @DisplayName("Argument Tests")
    class ArgumentTests {

        @Test
        @DisplayName("Dashed and camel case options become the setting's name")
        public void testFormat() {
            Assertions.assertEquals("HUB_PORT", Utils.formatArgument("--hub-port"));
            Assertions.assertEquals("HTTP_PORT", Utils.formatArgument("--httpPort"));
            Assertions.assertEquals("PORT", Utils.formatArgument("port"));
        }
    }
}
