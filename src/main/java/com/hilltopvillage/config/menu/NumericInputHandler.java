package com.hilltopvillage.config.menu;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class NumericInputHandler implements Listener {

    private static final ConcurrentHashMap<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PendingStringInput> pendingStringInputs = new ConcurrentHashMap<>();

    public static void requestNumber(Plugin plugin, Player player, String prompt, Consumer<Double> callback) {
        requestNumber(plugin, player, prompt, callback, null);
    }

    public static void requestNumber(Plugin plugin, Player player, String prompt, Consumer<Double> callback, Runnable onCancel) {
        player.closeInventory();
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "请在聊天栏输入数值:");
        player.sendMessage(ChatColor.GRAY + prompt);
        player.sendMessage(ChatColor.RED + "输入 'cancel' 取消");
        player.sendMessage("");

        pendingInputs.put(player.getUniqueId(), new PendingInput(callback, onCancel, player.getUniqueId()));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingInput removed = pendingInputs.remove(player.getUniqueId());
            if (removed != null) {
                player.sendMessage(ChatColor.GRAY + "输入超时，已取消。");
                if (removed.onCancel != null) removed.onCancel.run();
            }
        }, 20L * 30);
    }

    public static void requestInteger(Plugin plugin, Player player, String prompt, Consumer<Integer> callback, Runnable onCancel) {
        requestNumber(plugin, player, prompt, d -> callback.accept(d.intValue()), onCancel);
    }

    /**
     * 请求玩家在聊天栏输入任意字符串。
     * 用于 ItemsAdder 命名空间ID、Material 名等文本输入。
     *
     * @param plugin   插件实例
     * @param player   目标玩家
     * @param prompt   输入提示
     * @param callback 输入完成回调（接受字符串值，可为空字符串）
     * @param onCancel 取消/超时回调（可为 null）
     */
    public static void requestString(Plugin plugin, Player player, String prompt, Consumer<String> callback, Runnable onCancel) {
        player.closeInventory();
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "请在聊天栏输入文本:");
        player.sendMessage(ChatColor.GRAY + prompt);
        player.sendMessage(ChatColor.RED + "输入 'cancel' 取消");
        player.sendMessage("");

        PendingStringInput input = new PendingStringInput(callback, onCancel, player.getUniqueId());
        pendingStringInputs.put(player.getUniqueId(), input);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingStringInput removed = pendingStringInputs.remove(player.getUniqueId());
            if (removed != null) {
                player.sendMessage(ChatColor.GRAY + "输入超时，已取消。");
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
                event.getPlayer().sendMessage(ChatColor.GRAY + "已取消。");
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
            event.getPlayer().sendMessage(ChatColor.GRAY + "已取消。");
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
            event.getPlayer().sendMessage(ChatColor.RED + "无效的数字: " + msg + "，请重新输入或输入 'cancel'。");
            pendingInputs.put(id, pending);
        }
    }

    private static class PendingInput {
        final Consumer<Double> callback;
        final Runnable onCancel;
        final UUID playerId;

        PendingInput(Consumer<Double> callback, Runnable onCancel, UUID playerId) {
            this.callback = callback;
            this.onCancel = onCancel;
            this.playerId = playerId;
        }
    }

    private static class PendingStringInput {
        final Consumer<String> callback;
        final Runnable onCancel;
        final UUID playerId;

        PendingStringInput(Consumer<String> callback, Runnable onCancel, UUID playerId) {
            this.callback = callback;
            this.onCancel = onCancel;
            this.playerId = playerId;
        }
    }
}