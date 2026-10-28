package com.openrac.system;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

import com.openrac.utils.Utils;
import com.openrac.utils.logger.Log;

import java.util.HashMap;

/**
 * The environment class is our custom implementation for safely loading and retrieving environment
 * variables. It has double redundancy given that whenever we retrieve a variable, we must specify a
 * fallback on top of the .env.defaults file which already specifies a fallback. This is so that we
 * can ensure the type safety of our variables.
 */

public class Environment {
    private static final HashMap<String, Object> variables = new HashMap<>();

    /**
     * Initializes the environment by loading the .env file if it's present and falling back onto .env.defaults if it's not.
     * Command-line arguments are then parsed and merged in, overriding any loaded variables.
     * @param args The command-line arguments to parse and insert into the environment map.
     */

    public static void load(String[] args) {
        // The .env files are at the root, where services run.

        String envDir = System.getProperty("env.dir", ".");
        Dotenv defaults = Dotenv.configure().directory(envDir).filename(".env.defaults").load();
        Dotenv userVariables = Dotenv.configure().directory(envDir).ignoreIfMissing().ignoreIfMalformed().load();

        // Defaults first, then user values override them.
        for (DotenvEntry entry : defaults.entries())
            variables.put(entry.getKey(), entry.getValue());

        for (DotenvEntry entry : userVariables.entries())
            variables.put(entry.getKey(), entry.getValue());

        parseArguments(args);

        Log.debug("Successfully loaded " + variables.size() + " environment variables.");
    }

    /**
     * Parses the arguments passed to the program and inserts them into the environment map.
     * @param args The arguments passed to the program.
     */

    private static void parseArguments(String[] args) {
        // If there are no arguments, we return.
        if (args.length == 0) return;

        // Arguments must be even in length.
        if (args.length % 2 != 0) {
            Log.error("Arguments must be in the form of key value pairs.");
            return;
        }

        // Iterate through the arguments and the value.
        for (int i = 0; i < args.length; i += 2) {
            String key = Utils.formatArgument(args[i]);
            String value = args[i + 1];

            variables.put(key, value);
        }
    }

    /**
     * Retrieves an integer from our environment map.
     * @param key The key to retrieve the integer from.
     * @param defaultValue The default value to return if the key does not exist.
     * @return The integer, assuming the value can be converted to an integer.
     */

    public static int getInt(String key, int defaultValue) {
        Object value = variables.get(key);

        if (value == null) return defaultValue;

        return Integer.parseInt(value.toString());
    }

    /**
     * Retrieves a string from our environment map.
     * @param key The key to retrieve the string from.
     * @param defaultValue The default value to return if the key does not exist.
     * @return The string, assuming the value can be converted to a string.
     */

    public static String getString(String key, String defaultValue) {
        Object value = variables.get(key);

        if (value == null) return defaultValue;

        return value.toString();
    }

    /**
     * Retrieves a boolean from our environment map.
     * @param key The key to retrieve the boolean from.
     * @param defaultValue The default value to return if the key does not exist.
     * @return The boolean, assuming the value can be converted to a boolean.
     */

    public static boolean getBoolean(String key, boolean defaultValue) {
        Object value = variables.get(key);

        if (value == null) return defaultValue;

        return Boolean.parseBoolean(value.toString());
    }
}
