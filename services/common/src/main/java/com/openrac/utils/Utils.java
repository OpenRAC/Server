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

            // Leading dashes mark the option, inner ones separate words (--http-port).
            if (c == '-') {
                if (formatted.length() > 0) formatted.append('_');

                continue;
            }

            // If the character is uppercase, we add an underscore to the formatted string.
            if (Character.isUpperCase(c))
                formatted.append('_');

            formatted.append(Character.toUpperCase(c));
        }

        return formatted.toString();
    }

    /**
     * @param text A string read from the wire.
     * @param maxLength The most characters it may have.
     * @return Whether the string is neither blank nor too long.
     */

    public static boolean isValid(String text, int maxLength) {
        return !text.isBlank() && text.length() <= maxLength;
    }

    /**
     * @param text A string read from the wire, such as a game family.
     * @param maxLength The most characters it may have.
     * @return Whether it is 1 to maxLength characters of printable ASCII without spaces.
     */

    public static boolean isWord(String text, int maxLength) {
        return !text.isEmpty() && text.length() <= maxLength && text.chars().allMatch(c -> c > 0x20 && c <= 0x7E);
    }

    /**
     * @param text A name shown to players, such as a server's.
     * @param maxLength The most characters it may have.
     * @return Whether it is at most maxLength characters of printable ASCII, spaces included.
     */

    public static boolean isPrintable(String text, int maxLength) {
        return text.length() <= maxLength && text.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
    }
}
