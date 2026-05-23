package com.hilltopvillage.util;

import com.hilltopvillage.HilltopVillagePlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
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
 */
public class LanguageManager {

    private final HilltopVillagePlugin plugin;
    private File messagesFile;
    private FileConfiguration messagesConfig;

    /** 全局默认语言 */
    private String defaultLocale = "zh";

    /** 玩家个人语言偏好 (uuid -> locale) */
    private final Map<UUID, String> playerLocales = new HashMap<>();

    /** 消息缓存: locale -> (key -> message) */
    private final Map<String, Map<String, String>> messageCache = new HashMap<>();

    public LanguageManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        loadDefaults();
        reload();
    }

    // ---- 加载 ----

    private void loadDefaults() {
        // Save default messages.yml from resources
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();

        messagesFile = new File(dataFolder, "messages.yml");
        if (!messagesFile.exists()) {
            // Copy from resources
            try (InputStream in = plugin.getResource("messages.yml")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, messagesFile.toPath());
                } else {
                    messagesFile.createNewFile();
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Could not create default messages.yml: " + e.getMessage());
            }
        }
    }

    public void reload() {
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);

        // Merge with built-in defaults (from jar)
        InputStream defStream = plugin.getResource("messages.yml");
        if (defStream != null) {
            try (Reader reader = new InputStreamReader(defStream, StandardCharsets.UTF_8)) {
                YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(reader);
                messagesConfig.setDefaults(defConfig);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not load default messages.yml from jar: " + e.getMessage());
            }
        }

        // Read locale from config
        String savedLocale = plugin.getConfig().getString("language", "zh");
        if (savedLocale != null && (savedLocale.equals("en") || savedLocale.equals("zh"))) {
            defaultLocale = savedLocale;
        }

        // Pre-cache messages (merge file + jar defaults)
        messageCache.clear();
        for (String locale : new String[]{"en", "zh"}) {
            Map<String, String> map = new HashMap<>();
            org.bukkit.configuration.ConfigurationSection section = messagesConfig.getConfigurationSection(locale);
            if (section != null) {
                for (String key : section.getKeys(true)) {
                    if (messagesConfig.isString(locale + "." + key)) {
                        map.put(key, messagesConfig.getString(locale + "." + key));
                    }
                }
            }
            // Also load from jar defaults (for newly added keys)
            if (messagesConfig.getDefaults() != null) {
                org.bukkit.configuration.ConfigurationSection defSection = messagesConfig.getDefaults().getConfigurationSection(locale);
                if (defSection != null) {
                    for (String key : defSection.getKeys(true)) {
                        if (!map.containsKey(key)) {
                            String defPath = locale + "." + key;
                            if (messagesConfig.getDefaults().isString(defPath)) {
                                map.put(key, messagesConfig.getDefaults().getString(defPath));
                            }
                        }
                    }
                }
            }
            messageCache.put(locale, map);
        }

        plugin.getLogger().info("LanguageManager loaded. Default locale: " + defaultLocale);
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