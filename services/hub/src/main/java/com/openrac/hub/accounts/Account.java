package com.openrac.hub.accounts;

/**
 * A player's account as the store keeps it.
 * @param id The account's id, which never changes.
 * @param name The name as registered, unique in any case.
 * @param passwordHash The password's hash, never the password.
 */

public record Account(long id, String name, String passwordHash) {}
