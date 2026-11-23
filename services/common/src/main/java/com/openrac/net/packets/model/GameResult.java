package com.openrac.net.packets.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A finished game as a game server reports it to the hub: what was played, for how long, who won,
 * and each player's line of the scoreboard.
 * @param family The game family it was played in.
 * @param name The game's name in the lobby.
 * @param mode The game's mode, as the game numbers its rule sets.
 * @param map The map, as the game numbers its levels.
 * @param duration How long it ran, in seconds.
 * @param winningTeam The team that won, or -1 when none did.
 * @param players Each player's line, in slot order.
 */

public record GameResult(String family, String name, int mode, int map, int duration, int winningTeam, List<Player> players) {
    /**
     * Reads a result as {@link #serialize()} writes it.
     * @param payload The packet's payload, at the result.
     * @param maxPlayers The most player lines accepted.
     * @return The result, or null when it holds too many players.
     */

    public static GameResult read(InputBuffer payload, int maxPlayers) {
        String family = payload.readString();
        String name = payload.readString();
        int mode = payload.readInt();
        int map = payload.readInt();
        int duration = payload.readInt();
        int winningTeam = payload.readInt();
        int count = payload.readUnsignedByte();

        // A scoreboard past the limit only comes from a broken server.
        if (count > maxPlayers) return null;

        List<Player> players = new ArrayList<>();

        for (int i = 0; i < count; i++)
            players.add(new Player(payload.readLong(), payload.readString(), payload.readInt(), payload.readInt(), payload.readInt(), payload.readInt(),
                    payload.readUnsignedByte() != 0));

        return new GameResult(family, name, mode, map, duration, winningTeam, players);
    }

    /**
     * Writes the result for a HUB RESULT.
     * @return The result's fields, then each player's.
     */

    public OutputBuffer serialize() {
        OutputBuffer buffer = new OutputBuffer();

        buffer.writeString(family);
        buffer.writeString(name);
        buffer.writeInt(mode);
        buffer.writeInt(map);
        buffer.writeInt(duration);
        buffer.writeInt(winningTeam);
        buffer.writeUnsignedByte(players.size());

        for (Player player : players) {
            buffer.writeLong(player.accountId());
            buffer.writeString(player.name());
            buffer.writeInt(player.team());
            buffer.writeInt(player.kills());
            buffer.writeInt(player.deaths());
            buffer.writeInt(player.score());
            buffer.writeUnsignedByte(player.won() ? 1 : 0);
        }

        return buffer;
    }

    /**
     * One player's line of the scoreboard.
     * @param accountId The hub account the server verified, 0 for a guest.
     * @param name The name it played under.
     * @param team Its team, or -1 in a game without teams.
     * @param kills Players it killed.
     * @param deaths Times it died.
     * @param score Its score as the game counts it.
     * @param won Whether it was on the winning side.
     */

    public record Player(long accountId, String name, int team, int kills, int deaths, int score, boolean won) {}
}
