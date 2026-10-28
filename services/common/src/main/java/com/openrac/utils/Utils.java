package com.openrac.utils;

/**
 * Small helpers shared by every module.
 */

public class Utils {
    /**
     * Formats the argument from camel case to uppercase with underscores for spaces.
     * @param argument The argument as it comes from the command line.
     * @return The formatted argument as an uppercase, underscore-delimited string.
     */

    public static String formatArgument(String argument) {
        StringBuilder formatted = new StringBuilder();

        for (int i = 0; i < argument.length(); i++) {
            char c = argument.charAt(i);

            // Skip the dash character.
            if (c == '-') continue;

            // If the character is uppercase, we add an underscore to the formatted string.
            if (Character.isUpperCase(c))
                formatted.append('_');

            formatted.append(Character.toUpperCase(c));
        }

        return formatted.toString();
    }
}
