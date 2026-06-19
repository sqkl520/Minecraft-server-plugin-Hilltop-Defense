package com.hilltopvillage.config.menu;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.ConfigPermission;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class MainConfigMenu extends AbstractConfigMenu {

    private final ConfigManager configManager;

    public MainConfigMenu(Player admin, ConfigManager configManager, LanguageManager lang) {
        super(admin, lang.getFor(admin, "config.menu.main.title"), 54, HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class), lang);
        this.configManager = configManager;
    }

    @Override
    protected void buildMenu() {
        clickActions.clear();
        inventory.clear();

        boolean isAdmin = admin.hasPermission(ConfigPermission.ADMIN.getNode());

        setItem(4, Material.ENCHANTED_BOOK,
                lang.getFor(admin, "config.menu.main.header"),
                lang.getFor(admin, "config.menu.main.header-lore1"),
                "",
                lang.getFor(admin, "config.menu.main.header-lore3"));

        int slot = 19;
        if (isAdmin || admin.hasPermission(ConfigPermission.RULES.getNode())) {
            setClickableItem(slot, Material.CLOCK,
                    lang.getFor(admin, "config.menu.main.rules"),
                    ct -> openSubMenu(new GameRulesMenu(admin, configManager, this, lang)),
                    lang.getFor(admin, "config.menu.main.rules-lore1"),
                    lang.getFor(admin, "config.menu.main.rules-lore2"),
                    lang.getFor(admin, "config.menu.main.rules-lore3"),
                    lang.getFor(admin, "config.menu.main.rules-lore4"));
        } else {
            setItem(slot, Material.BARRIER, lang.getFor(admin, "config.menu.main.rules-noperm"));
        }
        slot += 2;

        if (isAdmin || admin.hasPermission(ConfigPermission.WAVES.getNode())) {
            setClickableItem(slot, Material.ZOMBIE_HEAD,
                    lang.getFor(admin, "config.menu.main.waves"),
                    ct -> openSubMenu(new WaveSettingsMenu(admin, configManager, this, lang)),
                    lang.getFor(admin, "config.menu.main.waves-lore1"),
                    lang.getFor(admin, "config.menu.main.waves-lore2"),
                    lang.getFor(admin, "config.menu.main.waves-lore3"));
        } else {
            setItem(slot, Material.BARRIER, lang.getFor(admin, "config.menu.main.waves-noperm"));
        }
        slot += 2;

        if (isAdmin || admin.hasPermission(ConfigPermission.MONSTERS.getNode())) {
            setClickableItem(slot, Material.SKELETON_SPAWN_EGG,
                    lang.getFor(admin, "config.menu.main.monsters"),
                    ct -> openSubMenu(new MonsterSettingsMenu(admin, configManager, this, lang)),
                    lang.getFor(admin, "config.menu.main.monsters-lore1"),
                    lang.getFor(admin, "config.menu.main.monsters-lore2"),
                    lang.getFor(admin, "config.menu.main.monsters-lore3"));
        } else {
            setItem(slot, Material.BARRIER, lang.getFor(admin, "config.menu.main.monsters-noperm"));
        }
        slot += 2;

        if (isAdmin || admin.hasPermission(ConfigPermission.ITEMS.getNode())) {
            setClickableItem(slot, Material.NETHERITE_AXE,
                    lang.getFor(admin, "config.menu.main.items"),
                    ct -> openSubMenu(new ItemSettingsMenu(admin, configManager, this, lang)),
                    lang.getFor(admin, "config.menu.main.items-lore1"),
                    lang.getFor(admin, "config.menu.main.items-lore2"),
                    lang.getFor(admin, "config.menu.main.items-lore3"));
        } else {
            setItem(slot, Material.BARRIER, lang.getFor(admin, "config.menu.main.items-noperm"));
        }
        slot += 2;

        if (isAdmin || admin.hasPermission(ConfigPermission.NODES.getNode())) {
            setClickableItem(slot, Material.BEACON,
                    lang.getFor(admin, "config.menu.main.nodes"),
                    ct -> openSubMenu(new NodeSettingsMenu(admin, configManager, this, lang)),
                    lang.getFor(admin, "config.menu.main.nodes-lore1"),
                    lang.getFor(admin, "config.menu.main.nodes-lore2"),
                    lang.getFor(admin, "config.menu.main.nodes-lore3"));
        } else {
            setItem(slot, Material.BARRIER, lang.getFor(admin, "config.menu.main.nodes-noperm"));
        }

        int bottomRow = 45;
        if (configManager.hasUnsavedChanges()) {
            setClickableItem(bottomRow, Material.LIME_WOOL,
                    lang.getFor(admin, "config.menu.main.save"),
                    ct -> {
                        configManager.saveToFile();
                        admin.sendMessage(lang.getFor(admin, "config.menu.main.saved-msg"));
                        admin.sendMessage(lang.getFor(admin, "config.menu.main.saved-hint"));
                        admin.playSound(admin.getLocation(),
                                org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
                        reopen();
                    },
                    lang.getFor(admin, "config.menu.main.save-lore1"),
                    lang.getFor(admin, "config.menu.main.save-lore2"));
        } else {
            setItem(bottomRow, Material.GREEN_WOOL,
                    lang.getFor(admin, "config.menu.main.uptodate"),
                    lang.getFor(admin, "config.menu.main.uptodate-lore"));
        }

        setClickableItem(bottomRow + 2, Material.ORANGE_WOOL,
                lang.getFor(admin, "config.menu.main.revert"),
                ct -> {
                    if (configManager.hasUnsavedChanges()) {
                        configManager.revertAll();
                        admin.sendMessage(lang.getFor(admin, "config.menu.main.reverted-msg"));
                        admin.playSound(admin.getLocation(),
                                org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
                        reopen();
                    } else {
                        admin.sendMessage(lang.getFor(admin, "config.menu.main.no-changes-msg"));
                    }
                },
                lang.getFor(admin, "config.menu.main.revert-lore"));

        setClickableItem(bottomRow + 4, Material.CRAFTING_TABLE,
                lang.getFor(admin, "config.menu.main.reload"),
                ct -> {
                    configManager.loadFromFile();
                    admin.sendMessage(lang.getFor(admin, "config.menu.main.reloaded-msg"));
                    admin.playSound(admin.getLocation(),
                            org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                    reopen();
                },
                lang.getFor(admin, "config.menu.main.reload-lore1"),
                lang.getFor(admin, "config.menu.main.reload-lore2"));

        setItem(bottomRow + 6, Material.BOOK,
                lang.getFor(admin, "config.menu.main.permission-info"),
                ChatColor.GRAY + ConfigPermission.ADMIN.getNode() + ChatColor.DARK_GRAY + lang.getFor(admin, "config.menu.main.perm-separator") + lang.getFor(admin, "config.menu.main.permission-admin-desc").replace(ChatColor.GRAY.toString(), "").replace("&7", ""),
                ChatColor.GRAY + ConfigPermission.RULES.getNode(),
                ChatColor.GRAY + ConfigPermission.WAVES.getNode(),
                ChatColor.GRAY + ConfigPermission.MONSTERS.getNode(),
                ChatColor.GRAY + ConfigPermission.ITEMS.getNode(),
                ChatColor.GRAY + ConfigPermission.NODES.getNode());

        fillEmpty();
    }

    private void openSubMenu(AbstractConfigMenu menu) {
        admin.closeInventory();
        Bukkit.getScheduler().runTaskLater(
                com.hilltopvillage.HilltopVillagePlugin.getPlugin(com.hilltopvillage.HilltopVillagePlugin.class),
                () -> {
                    Bukkit.getPluginManager().registerEvents(menu,
                            com.hilltopvillage.HilltopVillagePlugin.getPlugin(com.hilltopvillage.HilltopVillagePlugin.class));
                    menu.open();
                }, 2L);
    }
}