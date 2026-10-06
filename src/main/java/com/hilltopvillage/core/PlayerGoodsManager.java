package com.hilltopvillage.core;

import com.hilltopvillage.HilltopVillagePlugin;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 玩家进入竞技场前的状态快照管理（对应 BedWars1058 的 PlayerGoods）。
 *
 * <p>保存内容：背包、盔甲、经验、等级、血量、饱食度、游戏模式、飞行权限、药水效果。</p>
 *
 * <p>与 BedWars1058 的差异：其 PlayerGoods 仅存内存（服务器崩溃 = 玩家物品永久丢失），
 * 本实现落盘到 {@code player-goods.yml}，崩溃后重启仍可归还。</p>
 */
public final class PlayerGoodsManager {

    private static final String FILE_NAME = "player-goods.yml";
    /** 玩家背包容量（getContents() 的固定长度） */
    private static final int INVENTORY_SIZE = 36;
    /** 盔甲槽位数量（头盔/胸甲/护腿/靴子） */
    private static final int ARMOR_SIZE = 4;

    private final HilltopVillagePlugin plugin;
    private final File goodsFile;
    private Map<UUID, Snapshot> goods = new ConcurrentHashMap<>();

    public PlayerGoodsManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.goodsFile = new File(plugin.getDataFolder(), FILE_NAME);
        loadFromDisk();
    }

    // ========== 快照数据结构 ==========

    /** 单个玩家的状态快照 */
    public static final class Snapshot {
        private String playerName = "";
        private ItemStack[] contents;
        private ItemStack[] armor;
        private ItemStack offHand;
        private float exp;
        private int level;
        private double health;
        private int food;
        private GameMode gameMode = GameMode.SURVIVAL;
        private boolean allowFlight;
        private boolean flying;
        private Collection<PotionEffect> effects = new ArrayList<>();

        public String getPlayerName() { return playerName; }
    }

    // ========== 保存 / 归还 ==========

    /**
     * 保存玩家当前状态并清空其背包（进入竞技场前调用）。
     * 若该玩家已有未归还的快照，则保留原快照不覆盖（避免连续加入时丢失原始物品）。
     *
     * @return true 表示本次新建了快照
     */
    public boolean save(Player player) {
        UUID uuid = player.getUniqueId();
        if (goods.containsKey(uuid)) {
            // 已有快照：只清空当前背包，不覆盖原始快照
            clearInventory(player);
            return false;
        }

        Snapshot snapshot = new Snapshot();
        snapshot.playerName = player.getName();
        snapshot.contents = player.getInventory().getContents().clone();
        snapshot.armor = player.getInventory().getArmorContents().clone();
        snapshot.offHand = player.getInventory().getItemInOffHand().clone();
        snapshot.exp = player.getExp();
        snapshot.level = player.getLevel();
        snapshot.health = player.getHealth();
        snapshot.food = player.getFoodLevel();
        snapshot.gameMode = player.getGameMode();
        snapshot.allowFlight = player.getAllowFlight();
        snapshot.flying = player.isFlying();
        snapshot.effects = new ArrayList<>(player.getActivePotionEffects());

        goods.put(uuid, snapshot);
        saveToDisk();

        // 保存后清空状态，准备进入游戏
        clearInventory(player);
        player.setExp(0);
        player.setLevel(0);
        player.setFireTicks(0);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        return true;
    }

    /**
     * 归还玩家此前保存的状态（离开竞技场时调用）。
     *
     * @return true 表示成功归还；false 表示该玩家没有快照
     */
    public boolean restore(Player player) {
        Snapshot snapshot = goods.remove(player.getUniqueId());
        if (snapshot == null) return false;

        clearInventory(player);

        if (snapshot.contents != null) {
            player.getInventory().setContents(snapshot.contents);
        }
        if (snapshot.armor != null) {
            player.getInventory().setArmorContents(snapshot.armor);
        }
        if (snapshot.offHand != null) {
            player.getInventory().setItemInOffHand(snapshot.offHand);
        }
        player.setExp(snapshot.exp);
        player.setLevel(snapshot.level);
        player.setFoodLevel(snapshot.food);
        player.setGameMode(snapshot.gameMode);
        player.setAllowFlight(snapshot.allowFlight);
        player.setFlying(snapshot.allowFlight && snapshot.flying);

        // 血量恢复需在模式设置后，且不能超过当前最大血量
        double maxHealth = player.getMaxHealth();
        player.setHealth(Math.max(1.0, Math.min(snapshot.health, maxHealth)));

        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        for (PotionEffect effect : snapshot.effects) {
            player.addPotionEffect(effect);
        }

        saveToDisk();
        return true;
    }

    /** 该玩家是否有未归还的快照 */
    public boolean has(Player player) {
        return goods.containsKey(player.getUniqueId());
    }

    /** 所有持有快照的玩家 UUID（供关服/启动时检查） */
    public Collection<UUID> pendingUuids() {
        return new ArrayList<>(goods.keySet());
    }

    // ========== 内部工具 ==========

    private void clearInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
    }

    // ========== YAML 持久化 ==========

    private void loadFromDisk() {
        goods = new ConcurrentHashMap<>();
        if (!goodsFile.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(goodsFile);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) return;

        int loaded = 0;
        for (String key : root.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) continue;

                Snapshot snapshot = new Snapshot();
                snapshot.playerName = section.getString("player-name", "");

                snapshot.contents = readItems(section, "contents", INVENTORY_SIZE);
                snapshot.armor = readItems(section, "armor", ARMOR_SIZE);

                snapshot.offHand = section.getItemStack("off-hand");
                snapshot.exp = (float) section.getDouble("exp", 0.0);
                snapshot.level = section.getInt("level", 0);
                snapshot.health = section.getDouble("health", 20.0);
                snapshot.food = section.getInt("food", 20);
                snapshot.allowFlight = section.getBoolean("allow-flight", false);
                snapshot.flying = section.getBoolean("flying", false);

                String mode = section.getString("game-mode");
                snapshot.gameMode = mode == null ? GameMode.SURVIVAL : parseGameMode(mode);

                List<PotionEffect> effects = new ArrayList<>();
                for (Map<?, ?> raw : section.getMapList("effects")) {
                    PotionEffect effect = readEffect(raw);
                    if (effect != null) effects.add(effect);
                }
                snapshot.effects = effects;

                goods.put(uuid, snapshot);
                loaded++;
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "跳过损坏的物品快照: " + key, exception);
            }
        }

        if (loaded > 0) {
            plugin.getLogger().info("已从 " + FILE_NAME + " 恢复 " + loaded + " 份未归还的玩家物品快照");
        }
    }

    private void saveToDisk() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Snapshot> entry : goods.entrySet()) {
            String path = "players." + entry.getKey();
            Snapshot snapshot = entry.getValue();
            yaml.set(path + ".player-name", snapshot.playerName);

            // 按槽位索引存储：背包数组含 null（空格子），不能用 List.of（不允许 null 元素）
            writeItems(yaml, path + ".contents", snapshot.contents);
            writeItems(yaml, path + ".armor", snapshot.armor);

            yaml.set(path + ".off-hand", snapshot.offHand);
            yaml.set(path + ".exp", (double) snapshot.exp);
            yaml.set(path + ".level", snapshot.level);
            yaml.set(path + ".health", snapshot.health);
            yaml.set(path + ".food", snapshot.food);
            yaml.set(path + ".game-mode", snapshot.gameMode == null ? null : snapshot.gameMode.name());
            yaml.set(path + ".allow-flight", snapshot.allowFlight);
            yaml.set(path + ".flying", snapshot.flying);

            List<Map<String, Object>> effects = new ArrayList<>();
            for (PotionEffect effect : snapshot.effects) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("type", effect.getType().getName());
                map.put("duration", effect.getDuration());
                map.put("amplifier", effect.getAmplifier());
                map.put("ambient", effect.isAmbient());
                map.put("particles", effect.hasParticles());
                effects.add(map);
            }
            yaml.set(path + ".effects", effects);
        }

        try {
            yaml.save(goodsFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "无法保存 " + FILE_NAME, exception);
        }
    }

    /** 按槽位索引写入物品（跳过空格子，避免 null 元素无法序列化） */
    private void writeItems(YamlConfiguration yaml, String path, ItemStack[] items) {
        yaml.set(path, null);
        if (items == null) return;
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item != null && item.getType() != org.bukkit.Material.AIR) {
                yaml.set(path + "." + i, item);
            }
        }
    }

    /** 按槽位索引读取物品 */
    private ItemStack[] readItems(ConfigurationSection section, String path, int size) {
        ItemStack[] items = new ItemStack[size];
        ConfigurationSection itemsSection = section.getConfigurationSection(path);
        if (itemsSection == null) return items;
        for (String key : itemsSection.getKeys(false)) {
            try {
                int index = Integer.parseInt(key);
                if (index >= 0 && index < size) {
                    items[index] = itemsSection.getItemStack(key);
                }
            } catch (NumberFormatException ignored) {
                // 非法槽位键，跳过
            }
        }
        return items;
    }

    private static PotionEffect readEffect(Map<?, ?> raw) {
        Object typeName = raw.get("type");
        if (!(typeName instanceof String name)) return null;
        PotionEffectType type = PotionEffectType.getByName(name);
        if (type == null) return null;

        int duration = raw.get("duration") instanceof Number n ? n.intValue() : 200;
        int amplifier = raw.get("amplifier") instanceof Number n ? n.intValue() : 0;
        boolean ambient = !(raw.get("ambient") instanceof Boolean b) || b;
        boolean particles = !(raw.get("particles") instanceof Boolean b) || b;
        return new PotionEffect(type, duration, amplifier, ambient, particles);
    }

    private static GameMode parseGameMode(String name) {
        try {
            return GameMode.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return GameMode.SURVIVAL;
        }
    }
}
