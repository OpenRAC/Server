package com.openrac.server.http;

/**
 * A mod set the store refuses. The message names the rule broken, for the server's log only.
 */

public class InvalidModSetException extends Exception {
    /**
     * @param rule The rule the set broke.
     */

    public InvalidModSetException(String rule) {
        super(rule);
    }
}
