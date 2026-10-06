package com.hilltopvillage.arena;

import com.hilltopvillage.core.GameState;
import com.hilltopvillage.core.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Arena interface - represents a single game instance.
 * Inspired by BedWars1058's IArena pattern.
 *
 * <p>Each arena is an independent game instance with its own:
 * - Player list
 * - Game state (WAITING → STARTING → RUNNING → ENDING)
 * - Wave manager
 * - Node system
 * - Shop instances
 *
 * <p>Multiple arenas can run simultaneously on different worlds or regions.
 */
public interface IArena {

    // ========== Basic Info ==========

    /**
     * Arena unique identifier (e.g. "arena1", "skylands", "desert_fortress").
     * Must match the ID in arenas.yml.
     */
    String getArenaId();

    /**
     * Display name shown to players (supports color codes).
     * Example: "§6§lSkylands Fortress"
     */
    String getDisplayName();

    /**
     * Arena world name.
     */
    String getWorldName();

    // ========== Game State ==========

    /**
     * Current game state.
     * WAITING → waiting for players to join
     * STARTING → countdown before game starts (10 seconds)
     * RUNNING → game in progress
     * ENDING → game over, cleaning up
     */
    GameState getState();

    /**
     * Change arena state.
     * Triggers state transition logic (e.g. WAITING→STARTING starts countdown).
     */
    void setState(GameState state);

    // ========== Player Management ==========

    /**
     * All players currently in this arena (alive + spectators).
     */
    List<Player> getPlayers();

    /**
     * Add a player to this arena.
     * Teleports player to lobby/spawn, gives starting items.
     *
     * @return true if joined successfully, false if arena is full/disabled
     */
    boolean addPlayer(Player player);

    /**
     * Remove a player from this arena.
     * Teleports player back to server spawn, clears inventory.
     *
     * @param player player to remove
     */
    void removePlayer(Player player);

    /**
     * Check if a player is in this arena.
     */
    boolean isPlayer(UUID uuid);

    /**
     * Check if a player is in this arena.
     */
    boolean isPlayer(Player player);

    /**
     * Get player data (crystals, kills, etc).
     */
    PlayerData getPlayerData(UUID uuid);

    // ========== Game Control ==========

    /**
     * Start the game immediately (skip countdown).
     * Only works if state is WAITING or STARTING.
     */
    void startGame();

    /**
     * Stop the game immediately.
     * Kicks all players, resets arena state.
     */
    void stopGame();

    /**
     * Check if game can start.
     * Requires: min players reached, all spawn points set, core location valid.
     */
    boolean canStart();

    /**
     * Current wave number (1-based).
     * Returns 0 if game hasn't started.
     */
    int getCurrentWave();

    /**
     * Total waves to victory.
     */
    int getTotalWaves();

    // ========== Locations ==========

    /**
     * Core location (village core that players must defend).
     */
    Location getCoreLocation();

    /**
     * Lobby spawn point (where players wait before game starts).
     * If null, uses first spawn point.
     */
    Location getLobbySpawn();

    /**
     * Spectator spawn point (where dead players go).
     * If null, uses core location + 10 blocks up.
     */
    Location getSpectatorSpawn();

    /**
     * Player spawn points (for in-game respawn).
     */
    List<Location> getSpawnPoints();

    // ========== Messaging ==========

    /**
     * Send a message to all players in this arena.
     */
    void broadcast(String message);

    /**
     * Send an i18n message to all players in this arena.
     *
     * @param key message key from messages.yml
     * @param args message arguments
     */
    void broadcastI18n(String key, Object... args);

    // ========== Status ==========

    /**
     * Check if arena is enabled in arenas.yml.
     */
    boolean isEnabled();

    /**
     * Check if arena is available for join.
     * Returns true if the arena is deployed, state is WAITING, and player count < max.
     */
    boolean isAvailable();

    /**
     * 竞技场是否已完成部署（至少配置了 1 个怪物生成点）。
     * 未部署的竞技场不应出现在可选列表中，也不允许开局。
     */
    boolean isDeployed();

    /**
     * 竞技场世界是否已加载。
     *
     * <p>false 表示「不可用」：arenas.yml 里写了这个竞技场，但对应的地图存档不存在或未被加载。
     * 与 {@link #isDeployed()}（世界在、但还没配生成点）是两种不同的状态。</p>
     */
    boolean isWorldAvailable();

    /**
     * Check if arena is full.
     */
    boolean isFull();

    /**
     * Current player count.
     */
    int getPlayerCount();

    /**
     * Minimum players to start.
     */
    int getMinPlayers();

    /**
     * Maximum players allowed.
     */
    int getMaxPlayers();
}
