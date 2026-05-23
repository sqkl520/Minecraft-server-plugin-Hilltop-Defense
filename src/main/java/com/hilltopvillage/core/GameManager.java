package com.hilltopvillage.core;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.admin.AdminDeployManager;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.mechanics.NodeSystem;
import com.hilltopvillage.util.DisplayEntityManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GameManager {

    private final HilltopVillagePlugin plugin;
    private GameState state;
    private int currentWave;
    private int totalWaves;
    private final ConcurrentHashMap<UUID, PlayerData> playerDataMap;
    private final Set<UUID> players;
    private final WaveManager waveManager;
    private final NodeSystem nodeSystem;
    private final DisplayEntityManager displayEntityManager;
    private final AdminDeployManager adminDeployManager;
    private final ConfigManager configManager;
    private Location coreLocation;
    private World gameWorld;

    public GameManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.state = GameState.WAITING;
        this.currentWave = 0;
        this.totalWaves = plugin.getConfig().getInt("game.victory-waves", 20);
        this.playerDataMap = new ConcurrentHashMap<>();
        this.players = Collections.newSetFromMap(new ConcurrentHashMap<>());

        loadWorldReference();

        this.waveManager = new WaveManager(this);
        this.nodeSystem = new NodeSystem(this);
        this.displayEntityManager = new DisplayEntityManager(plugin);
        this.adminDeployManager = new AdminDeployManager(plugin, this);
        this.configManager = new ConfigManager(plugin);
    }

    private void loadWorldReference() {
        String worldName = plugin.getConfig().getString("game.world-name", "world");
        this.gameWorld = Bukkit.getWorld(worldName);
        if (this.gameWorld == null) {
            this.gameWorld = Bukkit.getWorlds().get(0);
            plugin.getLogger().warning("Configured world not found, defaulting to: " + this.gameWorld.getName());
        }

        double coreX = plugin.getConfig().getDouble("game.core.x", 0);
        double coreY = plugin.getConfig().getDouble("game.core.y", 100);
        double coreZ = plugin.getConfig().getDouble("game.core.z", 0);
        this.coreLocation = new Location(gameWorld, coreX, coreY, coreZ);
    }

    public void startGame() {
        if (state != GameState.WAITING) return;
        if (players.size() < plugin.getConfig().getInt("game.min-players", 2)) return;

        state = GameState.STARTING;
        broadcastMessage(plugin.getConfig().getString("messages.game-start", "&6Game started!"));

        nodeSystem.loadNodesFromWorld();
        nodeSystem.applyAllBuffs();

        state = GameState.RUNNING;
        currentWave = 0;
        startNextWave();
    }

    public void startNextWave() {
        if (state == GameState.DEFEAT || state == GameState.VICTORY) return;

        currentWave++;
        if (currentWave > totalWaves) {
            endGame(true);
            return;
        }

        state = GameState.RUNNING;

        broadcastMessage(plugin.getConfig().getString("messages.wave-start", "&eWave {wave} incoming!")
                .replace("{wave}", String.valueOf(currentWave)));

        waveManager.spawnWave(currentWave);

        new BukkitRunnable() {
            @Override
            public void run() {
                checkWaveCompletion();
            }
        }.runTaskLater(plugin, 20L * 5);
    }

    public void checkWaveCompletion() {
        if (state != GameState.RUNNING && state != GameState.WAVE_INTERMISSION) return;

        if (waveManager.isWaveCleared()) {
            onWaveComplete();
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    checkWaveCompletion();
                }
            }.runTaskLater(plugin, 20L * 3);
        }
    }

    private void onWaveComplete() {
        broadcastMessage(plugin.getConfig().getString("messages.wave-complete", "&aWave {wave} cleared!")
                .replace("{wave}", String.valueOf(currentWave)));

        state = GameState.WAVE_INTERMISSION;

        int intervalSeconds = plugin.getConfig().getInt("game.wave-interval-seconds", 30);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (state == GameState.WAVE_INTERMISSION) {
                    startNextWave();
                }
            }
        }.runTaskLater(plugin, 20L * intervalSeconds);
    }

    public void endGame(boolean victory) {
        state = victory ? GameState.VICTORY : GameState.DEFEAT;
        String msgPath = victory ? "messages.game-over-victory" : "messages.game-over-defeat";
        broadcastMessage(plugin.getConfig().getString(msgPath, "&eGame over!"));

        waveManager.clearAllMobs();

        for (UUID playerId : players) {
            PlayerData data = playerDataMap.get(playerId);
            if (data != null) {
                data.deactivateSmash();
            }
        }

        playerDataMap.values().forEach(pd -> pd.deactivateSmash());
    }

    public void stopGame() {
        state = GameState.WAITING;
        currentWave = 0;
        waveManager.clearAllMobs();
        playerDataMap.values().forEach(PlayerData::deactivateSmash);
    }

    public void addPlayer(Player player) {
        players.add(player.getUniqueId());
        playerDataMap.computeIfAbsent(player.getUniqueId(), PlayerData::new);
    }

    public void removePlayer(Player player) {
        players.remove(player.getUniqueId());
        PlayerData data = playerDataMap.remove(player.getUniqueId());
        if (data != null) {
            data.deactivateSmash();
        }

        if (players.size() < plugin.getConfig().getInt("game.min-players", 2) && state == GameState.RUNNING) {
            endGame(false);
        }
    }

    public void broadcastMessage(String message) {
        for (UUID playerId : players) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', message));
            }
        }
    }

    public PlayerData getPlayerData(UUID playerId) {
        return playerDataMap.get(playerId);
    }

    public PlayerData getPlayerData(Player player) {
        return playerDataMap.get(player.getUniqueId());
    }

    public GameState getState() {
        return state;
    }

    public int getCurrentWave() {
        return currentWave;
    }

    public Set<UUID> getPlayers() {
        return Collections.unmodifiableSet(players);
    }

    public WaveManager getWaveManager() {
        return waveManager;
    }

    public NodeSystem getNodeSystem() {
        return nodeSystem;
    }

    public DisplayEntityManager getDisplayEntityManager() {
        return displayEntityManager;
    }

    public AdminDeployManager getAdminDeployManager() {
        return adminDeployManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public Location getCoreLocation() {
        return coreLocation.clone();
    }

    public World getGameWorld() {
        return gameWorld;
    }

    public HilltopVillagePlugin getPlugin() {
        return plugin;
    }

    public boolean isGameRunning() {
        return state == GameState.RUNNING || state == GameState.WAVE_INTERMISSION;
    }
}
