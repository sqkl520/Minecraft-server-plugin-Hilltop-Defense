package com.hilltopvillage.config.menu;

import com.hilltopvillage.util.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class NumericInputHandler implements Listener {

    private static final ConcurrentHashMap<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PendingStringInput> pendingStringInputs = new ConcurrentHashMap<>();

    public static void requestNumber(Plugin plugin, Player player, String prompt, Consumer<Double> callback, LanguageManager lang) {
        requestNumber(plugin, player, prompt, callback, null, lang);
    }

    public static void requestNumber(Plugin plugin, Player player, String prompt, Consumer<Double> callback, Runnable onCancel, LanguageManager lang) {
        player.closeInventory();
        player.sendMessage("");
        player.sendMessage(lang.getFor(player, "config.menu.input.number-prompt"));
        player.sendMessage(prompt);
        player.sendMessage(lang.getFor(player, "config.menu.input.cancel-hint"));
        player.sendMessage("");

        pendingInputs.put(player.getUniqueId(), new PendingInput(callback, onCancel, player.getUniqueId(), lang));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingInput removed = pendingInputs.remove(player.getUniqueId());
            if (removed != null) {
                player.sendMessage(lang.getFor(player, "config.menu.input.timeout"));
                if (removed.onCancel != null) removed.onCancel.run();
            }
        }, 20L * 30);
    }

    public static void requestInteger(Plugin plugin, Player player, String prompt, Consumer<Integer> callback, Runnable onCancel, LanguageManager lang) {
        requestNumber(plugin, player, prompt, d -> callback.accept(d.intValue()), onCancel, lang);
    }

    /**
     * Requests the player to input an arbitrary string in chat.
     * Used for ItemsAdder namespace IDs, Material names, etc.
     *
     * @param plugin   plugin instance
     * @param player   target player
     * @param prompt   input prompt (already localized)
     * @param callback completion callback (accepts string value, may be empty)
     * @param onCancel cancel/timeout callback (may be null)
     * @param lang     language manager for i18n
     */
    public static void requestString(Plugin plugin, Player player, String prompt, Consumer<String> callback, Runnable onCancel, LanguageManager lang) {
        player.closeInventory();
        player.sendMessage("");
        player.sendMessage(lang.getFor(player, "config.menu.input.text-prompt"));
        player.sendMessage(prompt);
        player.sendMessage(lang.getFor(player, "config.menu.input.cancel-hint"));
        player.sendMessage("");

        PendingStringInput input = new PendingStringInput(callback, onCancel, player.getUniqueId(), lang);
        pendingStringInputs.put(player.getUniqueId(), input);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingStringInput removed = pendingStringInputs.remove(player.getUniqueId());
            if (removed != null) {
                player.sendMessage(lang.getFor(player, "config.menu.input.timeout"));
                if (removed.onCancel != null) removed.onCancel.run();
            }
        }, 20L * 30);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();

        // 优先检查字符串输入
        PendingStringInput stringPending = pendingStringInputs.remove(id);
        if (stringPending != null) {
            event.setCancelled(true);
            String msg = event.getMessage().trim();
            if (msg.equalsIgnoreCase("cancel")) {
                event.getPlayer().sendMessage(stringPending.lang.getFor(event.getPlayer(), "config.menu.input.cancelled"));
                if (stringPending.onCancel != null) {
                    Bukkit.getScheduler().runTask(
                            Bukkit.getPluginManager().getPlugin("HilltopVillage"),
                            stringPending.onCancel);
                }
            } else {
                if (stringPending.callback != null) {
                    final String value = msg;
                    Bukkit.getScheduler().runTask(
                            Bukkit.getPluginManager().getPlugin("HilltopVillage"),
                            () -> stringPending.callback.accept(value));
                }
            }
            return;
        }

        // 检查数值输入
        PendingInput pending = pendingInputs.remove(id);
        if (pending == null) return;

        event.setCancelled(true);

        String msg = event.getMessage().trim();
        if (msg.equalsIgnoreCase("cancel")) {
            event.getPlayer().sendMessage(pending.lang.getFor(event.getPlayer(), "config.menu.input.cancelled"));
            if (pending.onCancel != null) {
                Bukkit.getScheduler().runTask(
                        Bukkit.getPluginManager().getPlugin("HilltopVillage"),
                        pending.onCancel);
            }
            return;
        }

        try {
            double value = Double.parseDouble(msg);
            if (pending.callback != null) {
                Bukkit.getScheduler().runTask(
                        Bukkit.getPluginManager().getPlugin("HilltopVillage"),
                        () -> pending.callback.accept(value));
            }
        } catch (NumberFormatException e) {
            event.getPlayer().sendMessage(pending.lang.getFor(event.getPlayer(), "config.menu.input.invalid-number", msg));
            pendingInputs.put(id, pending);
        }
    }

    /**
     * 玩家退服时清除其待处理的聊天输入（静默丢弃，不回调 onCancel）。
     *
     * <p>为什么不回调：{@code onCancel} 的调用方约定是给<b>在线</b>玩家重开 GUI
     * （{@code reopen}）；已退服的玩家调它只会打出发包到失效连接，
     * 还会顺带把菜单监听器重新注册一次。</p>
     *
     * <p>与 30 秒超时任务是幂等关系：两者都是 {@code remove(uuid)}，
     * 无论谁先执行，另一个都只会拿到 null。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pendingInputs.remove(id);
        pendingStringInputs.remove(id);
    }

    private static class PendingInput {
        final Consumer<Double> callback;
        final Runnable onCancel;
        final UUID playerId;
        final LanguageManager lang;

        PendingInput(Consumer<Double> callback, Runnable onCancel, UUID playerId, LanguageManager lang) {
            this.callback = callback;
            this.onCancel = onCancel;
            this.playerId = playerId;
            this.lang = lang;
        }
    }

    private static class PendingStringInput {
        final Consumer<String> callback;
        final Runnable onCancel;
        final UUID playerId;
        final LanguageManager lang;

        PendingStringInput(Consumer<String> callback, Runnable onCancel, UUID playerId, LanguageManager lang) {
            this.callback = callback;
            this.onCancel = onCancel;
            this.playerId = playerId;
            this.lang = lang;
        }
    }
}