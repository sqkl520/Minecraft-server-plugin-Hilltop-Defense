package com.hilltopvillage.command;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.admin.AdminDeployManager;
import com.hilltopvillage.util.LanguageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SetupWizard implements Listener {

    private final HilltopVillagePlugin plugin;
    private final LanguageManager lang;
    private final AdminDeployManager deploy;
    private final ConcurrentHashMap<UUID, SetupSession> sessions = new ConcurrentHashMap<>();

    public SetupWizard(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.deploy = plugin.getGameManager().getAdminDeployManager();
    }

    public void start(Player player) {
        SetupSession session = new SetupSession();
        sessions.put(player.getUniqueId(), session);
        showStep(player, session);
    }

    private void showStep(Player player, SetupSession s) {
        switch (s.step) {
            case 1: showStepWorld(player, s); break;
            case 2: showStepCore(player, s); break;
            case 3: showStepSpawns(player, s); break;
            case 4: showStepNodes(player, s); break;
            case 5: showStepPreview(player, s); break;
            case 6: showStepDone(player); break;
        }
    }

    // === Step 1: Confirm World ===
    private void showStepWorld(Player player, SetupSession s) {
        World world = player.getWorld();
        String msg = lang.getFor(player, "setup.step1", "1", "6")
                .replace("{world}", world.getName());
        sendClickableMsg(player, msg + " &a[确认]", "/hilltop setup confirm-world");
    }

    // === Step 2: Set Core ===
    private void showStepCore(Player player, SetupSession s) {
        String msg = lang.getFor(player, "setup.step2", "2", "6");
        sendClickableMsg(player, msg + " &a[设为核心]", "/hilltop setup confirm-core");
    }

    // === Step 3: Spawn Points (GUI) ===
    private void showStepSpawns(Player player, SetupSession s) {
        Inventory inv = Bukkit.createInventory(player, 9,
                lang.getFor(player, "setup.gui-spawns-title"));
        refreshSpawnGui(inv, player);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        player.openInventory(inv);
    }

    private void refreshSpawnGui(Inventory inv, Player player) {
        inv.clear();
        List<org.bukkit.Location> points = deploy.getSpawnPoints();
        for (int i = 0; i < Math.min(points.size(), 5); i++) {
            org.bukkit.Location loc = points.get(i);
            inv.setItem(i, makeItem(Material.COMPASS, lang.getFor(player, "setup.spawn-entry",
                    String.valueOf(i), fmt(loc.getX()), fmt(loc.getY()), fmt(loc.getZ())),
                    "setup-spawn-" + i));
        }
        inv.setItem(7, makeItem(Material.EMERALD_BLOCK, lang.getFor(player, "setup.btn-add"), "setup-add-spawn"));
        inv.setItem(8, makeItem(Material.GREEN_WOOL, lang.getFor(player, "setup.btn-done"), "setup-spawns-done"));
    }

    // === Step 4: Scan Nodes ===
    private void showStepNodes(Player player, SetupSession s) {
        plugin.getGameManager().getNodeSystem().loadNodesFromWorld(() -> {
            int count = plugin.getGameManager().getNodeSystem().getTotalCount();
            String msg = lang.getFor(player, "setup.step4", "4", "6")
                    .replace("{count}", String.valueOf(count));
            sendClickableMsg(player, msg + " &a[继续]", "/hilltop setup confirm-nodes");
        });
    }

    // === Step 5: Config Preview (GUI) ===
    private void showStepPreview(Player player, SetupSession s) {
        Inventory inv = Bukkit.createInventory(player, 9,
                lang.getFor(player, "setup.gui-preview-title"));
        var cfg = plugin.getGameManager().getConfigManager().getActiveConfig();
        inv.setItem(0, infoItem(Material.GRASS_BLOCK, lang.getFor(player, "setup.preview-world",
                plugin.getGameManager().getGameWorld().getName())));
        inv.setItem(1, infoItem(Material.BEACON, lang.getFor(player, "setup.preview-core",
                fmt(plugin.getGameManager().getCoreLocation().getX()),
                fmt(plugin.getGameManager().getCoreLocation().getY()),
                fmt(plugin.getGameManager().getCoreLocation().getZ()))));
        inv.setItem(2, infoItem(Material.SPAWNER, lang.getFor(player, "setup.preview-spawns",
                String.valueOf(deploy.getSpawnPoints().size()))));
        inv.setItem(3, infoItem(Material.CRYING_OBSIDIAN, lang.getFor(player, "setup.preview-nodes",
                String.valueOf(plugin.getGameManager().getNodeSystem().getTotalCount()))));
        inv.setItem(4, infoItem(Material.PLAYER_HEAD, lang.getFor(player, "setup.preview-players",
                String.valueOf(cfg.getMinPlayers()), String.valueOf(cfg.getMaxPlayers()))));
        inv.setItem(5, infoItem(Material.CLOCK, lang.getFor(player, "setup.preview-waves",
                String.valueOf(cfg.getVictoryWaves()))));
        inv.setItem(7, makeItem(Material.RED_WOOL, lang.getFor(player, "setup.btn-reset"), "setup-reset"));
        inv.setItem(8, makeItem(Material.GREEN_WOOL, lang.getFor(player, "setup.btn-confirm"), "setup-confirm"));
        player.openInventory(inv);
    }

    // === Step 6: Done ===
    private void showStepDone(Player player) {
        String msg = lang.getFor(player, "setup.done");
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(msg));
        player.sendMessage(
                LegacyComponentSerializer.legacySection()
                        .deserialize(lang.getFor(player, "setup.done-start"))
                        .clickEvent(ClickEvent.suggestCommand("/hilltop start")));
        sessions.remove(player.getUniqueId());
    }

    // === Command Handler (called from onCommand) ===
    public boolean handle(Player player, String sub) {
        SetupSession s = sessions.get(player.getUniqueId());
        if (s == null) return false;

        switch (sub.toLowerCase()) {
            case "confirm-world":
                deploy.setCoreLocation(null); // ensure clean, saves world implicitly
                s.advance();
                autoSave(player);
                showStep(player, s);
                break;
            case "confirm-core":
                deploy.setCoreLocation(player.getLocation());
                player.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, player.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
                s.advance();
                autoSave(player);
                showStep(player, s);
                break;
            case "confirm-nodes":
                s.advance();
                autoSave(player);
                showStep(player, s);
                break;
            default:
                player.sendMessage(lang.getFor(player, "setup.unknown"));
        }
        return true;
    }

    private void autoSave(Player player) {
        plugin.getGameManager().getConfigManager().saveToFile();
        player.sendMessage(LegacyComponentSerializer.legacySection()
                .deserialize(lang.getFor(player, "setup.saved")));
    }

    // === GUI Click Handler ===
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        SetupSession s = sessions.get(player.getUniqueId());
        if (s == null) return;

        String title = event.getView().getTitle();
        String spawnsTitle = lang.getFor(player, "setup.gui-spawns-title");
        String previewTitle = lang.getFor(player, "setup.gui-preview-title");

        event.setCancelled(true);

        if (title.equals(spawnsTitle)) {
            handleSpawnGuiClick(player, s, event);
        } else if (title.equals(previewTitle)) {
            handlePreviewGuiClick(player, s, event);
        }
    }

    private void handleSpawnGuiClick(Player player, SetupSession s, InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (item == null) return;
        String tag = getTag(item);
        if (tag == null) return;

        switch (tag) {
            case "setup-add-spawn":
                deploy.addSpawnPoint(player.getLocation());
                player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.05);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                player.sendMessage(LegacyComponentSerializer.legacySection()
                        .deserialize(lang.getFor(player, "setup.spawn-added")));
                refreshSpawnGui(event.getInventory(), player);
                autoSave(player);
                break;
            case "setup-spawns-done":
                HandlerList.unregisterAll(this);
                player.closeInventory();
                s.advance();
                autoSave(player);
                showStep(player, s);
                break;
            default:
                if (tag.startsWith("setup-spawn-")) {
                    int idx = Integer.parseInt(tag.substring("setup-spawn-".length()));
                    deploy.removeSpawnPoint(idx);
                    player.sendMessage(LegacyComponentSerializer.legacySection()
                            .deserialize(lang.getFor(player, "setup.spawn-removed", String.valueOf(idx))));
                    refreshSpawnGui(event.getInventory(), player);
                    autoSave(player);
                }
        }
    }

    private void handlePreviewGuiClick(Player player, SetupSession s, InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (item == null) return;
        String tag = getTag(item);
        if (tag == null) return;

        switch (tag) {
            case "setup-confirm":
                HandlerList.unregisterAll(this);
                player.closeInventory();
                s.advance();
                autoSave(player);
                showStep(player, s);
                break;
            case "setup-reset":
                HandlerList.unregisterAll(this);
                player.closeInventory();
                s.step = 1;
                showStep(player, s);
                break;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        SetupSession s = sessions.remove(event.getPlayer().getUniqueId());
        if (s != null) {
            HandlerList.unregisterAll(this);
        }
    }

    public boolean hasSession(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    // === Helpers ===
    private ItemStack makeItem(Material mat, String name, String tag) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.RESET + LegacyComponentSerializer.legacySection()
                .deserialize(name).toString());
        meta.setLore(java.util.Collections.singletonList(tag));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.RESET + LegacyComponentSerializer.legacySection()
                .deserialize(name).toString());
        item.setItemMeta(meta);
        return item;
    }

    private String getTag(ItemStack item) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta().getLore() == null) return null;
        List<String> lore = item.getItemMeta().getLore();
        return lore.isEmpty() ? null : lore.get(0);
    }

    private void sendClickableMsg(Player player, String msg, String command) {
        player.sendMessage(LegacyComponentSerializer.legacySection()
                .deserialize(msg)
                .clickEvent(ClickEvent.runCommand(command)));
    }

    private String fmt(double d) { return String.format("%.1f", d); }

    public static class SetupSession {
        int step = 1;
        void advance() { step++; }
    }
}
