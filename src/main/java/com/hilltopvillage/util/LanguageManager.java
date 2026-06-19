package com.hilltopvillage.util;

import com.hilltopvillage.HilltopVillagePlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 多语言管理器 - 从 messages.yml 加载消息，支持 en/zh 切换。
 * JAR 内嵌文件为主，磁盘文件为覆盖层，确保新 key 始终可用。
 */
public class LanguageManager {

    private final HilltopVillagePlugin plugin;
    private File messagesFile;

    /** 全局默认语言 */
    private String defaultLocale = "zh";

    /** 玩家个人语言偏好 (uuid -> locale) */
    private final Map<UUID, String> playerLocales = new HashMap<>();

    /** 消息缓存: locale -> (key -> message) */
    private final Map<String, Map<String, String>> messageCache = new HashMap<>();

    public LanguageManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    // ---- 加载 ----

    public void reload() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();
        messagesFile = new File(dataFolder, "messages.yml");

        // 1. 从 JAR 加载完整默认值（永远有所有 key）
        YamlConfiguration jarConfig = new YamlConfiguration();
        InputStream jarStream = plugin.getResource("messages.yml");
        if (jarStream != null) {
            try (Reader reader = new InputStreamReader(jarStream, StandardCharsets.UTF_8)) {
                jarConfig = YamlConfiguration.loadConfiguration(reader);
            } catch (IOException e) {
                plugin.getLogger().warning("Cannot load jar messages.yml: " + e.getMessage());
            }
        }

        // 2. 加载磁盘文件（旧版/管理员自定义，可能缺 key，但自定义优先）
        YamlConfiguration diskConfig = messagesFile.exists()
                ? YamlConfiguration.loadConfiguration(messagesFile)
                : new YamlConfiguration();

        // 3. 构建缓存：JAR 为基础，磁盘值覆盖
        messageCache.clear();
        for (String locale : new String[]{"en", "zh"}) {
            Map<String, String> map = new HashMap<>();

            // 先从 JAR 加载（保证所有 key 都有值）
            ConfigurationSection jarSection = jarConfig.getConfigurationSection(locale);
            if (jarSection != null) {
                for (String key : jarSection.getKeys(true)) {
                    String path = locale + "." + key;
                    if (jarConfig.isString(path)) {
                        map.put(key, jarConfig.getString(path));
                    }
                }
            }

            // 再用磁盘文件覆盖（管理员自定义生效）
            ConfigurationSection diskSection = diskConfig.getConfigurationSection(locale);
            if (diskSection != null) {
                for (String key : diskSection.getKeys(true)) {
                    String path = locale + "." + key;
                    if (diskConfig.isString(path)) {
                        map.put(key, diskConfig.getString(path));
                    }
                }
            }

            messageCache.put(locale, map);
        }

        // 4. 把 JAR 里缺失的 key 补写到磁盘文件（方便管理员查看/编辑全量 key）
        boolean dirty = false;
        for (String key : jarConfig.getKeys(true)) {
            if (!diskConfig.contains(key)) {
                diskConfig.set(key, jarConfig.get(key));
                dirty = true;
            }
        }
        if (dirty || !messagesFile.exists()) {
            try {
                diskConfig.save(messagesFile);
            } catch (IOException e) {
                plugin.getLogger().warning("Cannot save messages.yml: " + e.getMessage());
            }
        }

        // 5. 读取全局语言设置
        String savedLocale = plugin.getConfig().getString("language", "zh");
        if ("en".equals(savedLocale) || "zh".equals(savedLocale)) {
            defaultLocale = savedLocale;
        }

        plugin.getLogger().info("LanguageManager loaded " +
                messageCache.getOrDefault("en", Map.of()).size() + " EN keys, " +
                messageCache.getOrDefault("zh", Map.of()).size() + " ZH keys. Locale: " + defaultLocale);
    }

    // ---- 语言切换 ----

    public String getLocale() {
        return defaultLocale;
    }

    public void setLocale(String locale) {
        if (locale.equals("en") || locale.equals("zh")) {
            this.defaultLocale = locale;
            plugin.getConfig().set("language", locale);
            plugin.saveConfig();
        }
    }

    public String getPlayerLocale(Player player) {
        if (player == null) return defaultLocale;
        return playerLocales.getOrDefault(player.getUniqueId(), defaultLocale);
    }

    public void setPlayerLocale(Player player, String locale) {
        if (player == null) return;
        if (locale.equals("en") || locale.equals("zh")) {
            playerLocales.put(player.getUniqueId(), locale);
        }
    }

    // ---- 消息获取 ----

    /**
     * 使用默认语言获取消息（已含颜色代码）
     */
    public String get(String key) {
        return get(key, defaultLocale);
    }

    /**
     * 指定语言获取消息
     */
    public String get(String key, String locale) {
        Map<String, String> map = messageCache.get(locale);
        if (map == null) map = messageCache.get(defaultLocale);
        if (map == null) return key;
        String msg = map.get(key);
        if (msg == null && !locale.equals(defaultLocale)) {
            msg = messageCache.getOrDefault(defaultLocale, Map.of()).get(key);
        }
        return msg != null ? ChatColor.translateAlternateColorCodes('&', msg) : key;
    }

    /**
     * 获取玩家语言对应的消息
     */
    public String getFor(Player player, String key) {
        return get(key, getPlayerLocale(player));
    }

    /**
     * 获取消息并替换占位符 {0}, {1}, ...
     */
    public String get(String key, String locale, Object... args) {
        String msg = get(key, locale);
        for (int i = 0; i < args.length; i++) {
            msg = msg.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return msg;
    }

    public String getFor(Player player, String key, Object... args) {
        return get(key, getPlayerLocale(player), args);
    }
}
