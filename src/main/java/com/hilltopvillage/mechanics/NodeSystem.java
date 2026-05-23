package com.hilltopvillage.mechanics;

import com.hilltopvillage.core.GameManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NodeSystem {

    private final GameManager gameManager;
    private final ConcurrentHashMap<Location, NodeData> nodes;
    private final Set<Material> nodeBlockTypes;
    private double baseHealth;
    private double buffRadius;
    private final Map<PotionEffectType, Integer> buffEffects;
    private BukkitRunnable buffTask;

    public NodeSystem(GameManager gameManager) {
        this.gameManager = gameManager;
        this.nodes = new ConcurrentHashMap<>();
        this.nodeBlockTypes = new HashSet<>();
        this.buffEffects = new LinkedHashMap<>();

        loadConfig();
    }

    private void loadConfig() {
        List<String> blockTypeNames = gameManager.getPlugin().getConfig()
                .getStringList("nodes.block-types");
        for (String name : blockTypeNames) {
            try {
                nodeBlockTypes.add(Material.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException e) {
                gameManager.getPlugin().getLogger().warning("Invalid node block type: " + name);
            }
        }

        if (nodeBlockTypes.isEmpty()) {
            nodeBlockTypes.add(Material.BEACON);
            nodeBlockTypes.add(Material.ENCHANTING_TABLE);
        }

        this.baseHealth = gameManager.getPlugin().getConfig()
                .getDouble("nodes.base-health", 100.0);
        this.buffRadius = gameManager.getPlugin().getConfig()
                .getDouble("nodes.buff-radius", 20.0);

        ConfigurationSection buffSection = gameManager.getPlugin().getConfig()
                .getConfigurationSection("nodes.buff-effects");
        if (buffSection != null) {
            for (String key : buffSection.getKeys(false)) {
                try {
                    PotionEffectType type = PotionEffectType.getByName(key);
                    if (type != null) {
                        int amplifier = buffSection.getInt(key + ".amplifier", 0);
                        buffEffects.put(type, amplifier);
                    }
                } catch (Exception ignored) {}
            }
        }

        if (buffEffects.isEmpty()) {
            buffEffects.put(PotionEffectType.DAMAGE_RESISTANCE, 1);
            buffEffects.put(PotionEffectType.REGENERATION, 0);
        }
    }

    public void loadNodesFromWorld() {
        nodes.clear();
        World world = gameManager.getGameWorld();
        Location core = gameManager.getCoreLocation();

        int searchRadius = 80;
        int minY = core.getBlockY() - 30;
        int maxY = core.getBlockY() + 30;

        for (int x = core.getBlockX() - searchRadius; x <= core.getBlockX() + searchRadius; x++) {
            for (int z = core.getBlockZ() - searchRadius; z <= core.getBlockZ() + searchRadius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (isNodeBlock(block)) {
                        Location loc = block.getLocation().clone();
                        nodes.put(loc, new NodeData(loc, baseHealth));
                    }
                }
            }
        }

        gameManager.getPlugin().getLogger().info("Loaded " + nodes.size() + " energy nodes.");
    }

    public boolean isNodeBlock(Block block) {
        return nodeBlockTypes.contains(block.getType());
    }

    public boolean isNodeAt(Location location) {
        Location blockLoc = location.getBlock().getLocation();
        return nodes.containsKey(blockLoc);
    }

    public NodeData getNodeAt(Location location) {
        Location blockLoc = location.getBlock().getLocation();
        return nodes.get(blockLoc);
    }

    public double damageNode(Location location, double damage) {
        NodeData node = getNodeAt(location);
        if (node == null || !node.isActive()) return 0;

        double actualDamage = Math.min(damage, node.getHealth());
        node.setHealth(node.getHealth() - actualDamage);

        if (node.getHealth() <= 0) {
            node.setActive(false);
            node.getLocation().getWorld().playEffect(node.getLocation(), Effect.ENDER_SIGNAL, 0);
            node.getLocation().getWorld().playSound(node.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.5f);

            gameManager.broadcastMessage(
                    gameManager.getPlugin().getConfig().getString("messages.node-destroyed", "&cAn energy node has been destroyed!"));
        }

        return actualDamage;
    }

    public boolean repairNode(Location location, Player player, double repairAmount) {
        NodeData node = getNodeAt(location);
        if (node == null) return false;

        if (!node.isActive()) {
            node.setActive(true);
            node.setHealth(repairAmount);

            gameManager.getPlayerData(player).addNodeRepaired();
            gameManager.broadcastMessage(
                    gameManager.getPlugin().getConfig().getString("messages.node-repaired", "&aAn energy node has been repaired!"));

            node.getLocation().getWorld().playSound(node.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.8f);
            return true;
        }

        double newHealth = Math.min(baseHealth, node.getHealth() + repairAmount);
        node.setHealth(newHealth);
        node.getLocation().getWorld().playSound(node.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.2f);
        return true;
    }

    public void applyAllBuffs() {
        if (buffTask != null) {
            buffTask.cancel();
        }

        buffTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!gameManager.isGameRunning()) {
                    cancel();
                    return;
                }

                for (UUID playerId : gameManager.getPlayers()) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player == null || !player.isOnline()) continue;

                    int activeNodeCount = countActiveNodesNear(player.getLocation());
                    if (activeNodeCount > 0) {
                        applyBuffsToPlayer(player, activeNodeCount);
                    }
                }
            }
        };

        buffTask.runTaskTimer(gameManager.getPlugin(), 0L, 60L);
    }

    private int countActiveNodesNear(Location playerLoc) {
        int count = 0;
        for (NodeData node : nodes.values()) {
            if (!node.isActive()) continue;
            if (node.getLocation().getWorld() != playerLoc.getWorld()) continue;
            if (node.getLocation().distanceSquared(playerLoc) <= buffRadius * buffRadius) {
                count++;
            }
        }
        return count;
    }

    private void applyBuffsToPlayer(Player player, int activeNodes) {
        int effectiveAmplifier = Math.min(activeNodes - 1, 3);

        for (Map.Entry<PotionEffectType, Integer> entry : buffEffects.entrySet()) {
            PotionEffectType type = entry.getKey();
            int baseAmplifier = entry.getValue();
            int finalAmplifier = baseAmplifier + effectiveAmplifier;

            player.addPotionEffect(new PotionEffect(
                    type, 100, finalAmplifier, false, true, true));
        }
    }

    public void stopBuffTask() {
        if (buffTask != null) {
            buffTask.cancel();
            buffTask = null;
        }
    }

    public NodeData getNearestNode(Location location) {
        NodeData nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (NodeData node : nodes.values()) {
            if (!node.isActive()) continue;
            if (node.getLocation().getWorld() != location.getWorld()) continue;

            double distSq = node.getLocation().distanceSquared(location);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = node;
            }
        }

        return nearest;
    }

    public Collection<NodeData> getAllNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public int getActiveCount() {
        int count = 0;
        for (NodeData node : nodes.values()) {
            if (node.isActive()) count++;
        }
        return count;
    }

    public int getTotalCount() {
        return nodes.size();
    }

    public static class NodeData {
        private final Location location;
        private double health;
        private boolean active;

        public NodeData(Location location, double maxHealth) {
            this.location = location.clone();
            this.health = maxHealth;
            this.active = true;
        }

        public Location getLocation() {
            return location;
        }

        public double getHealth() {
            return health;
        }

        public void setHealth(double health) {
            this.health = Math.max(0, health);
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }
    }
}
