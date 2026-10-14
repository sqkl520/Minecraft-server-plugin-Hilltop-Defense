package com.hilltopvillage.config.menu;

import com.hilltopvillage.util.LanguageManager;
import org.bukkit.Bukkit;
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

    /** 聊天输入的等待上限（tick）：30 秒 = 600 tick */
    private static final long INPUT_TIMEOUT_TICKS = 20L * 30;
    /**
     * {@link #requestInteger} 的宽松量程上限（P2-32）：超出此范围的聊天输入被 clamp。
     *
     * <p>GUI 的 +/- 档位本就按各菜单的 min/max clamp（防线一），本常量是聊天输入层的
     * 最后防线（防线二）：拦掉 {@code (int) 1e300 → Integer.MAX_VALUE} 这类荒谬值。
     * 取 1e9 是因为它宽松到覆盖一切合理游戏参数，又稳稳落在 int 量程内。</p>
     */
    private static final double INTEGER_INPUT_CLAMP = 1_000_000_000d;

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

        PendingInput input = new PendingInput(plugin, callback, onCancel, player.getUniqueId(), lang);
        pendingInputs.put(player.getUniqueId(), input);
        scheduleNumberTimeout(player, input);
    }

    public static void requestInteger(Plugin plugin, Player player, String prompt, Consumer<Integer> callback, Runnable onCancel, LanguageManager lang) {
        requestNumber(plugin, player, prompt, d -> {
            // P2-32 整数域防护：超界值 clamp 到 ±1e9（在 int 量程内），避免 (int) 截断语义
            // 把 1e300 变 Integer.MAX_VALUE、把 1e-9 变 0。NaN/Infinity 已由 onChat 的
            // isFinite 检查拦在更早一层，到不了这里。
            double clamped = Math.max(-INTEGER_INPUT_CLAMP, Math.min(INTEGER_INPUT_CLAMP, d));
            callback.accept((int) clamped);
        }, onCancel, lang);
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
            // 两参 remove：只消费「本任务登记的那一个」条目（对象身份精确匹配）。
            // 期间的覆盖请求（map 里已换成新条目）不会被子虚乌有的旧超时任务误杀（M-11 缺陷②）
            PendingStringInput removed = pendingStringInputs.remove(input.playerId, input) ? input : null;
            if (removed != null) {
                player.sendMessage(lang.getFor(player, "config.menu.input.timeout"));
                if (removed.onCancel != null) removed.onCancel.run();
            }
        }, INPUT_TIMEOUT_TICKS);
    }

    /**
     * 登记一次数值输入的 30 秒超时任务（M-11）。
     *
     * <p>超时到点时用两参 {@code remove(uuid, entry)} 精确消费「本任务登记的那一个条目」：
     * 若期间玩家重新发起输入、或非法输入后重挂换成了新条目，map 里已不是本条目 ——
     * remove 失败则不消费、不触发 onCancel。旧任务因此不会误杀新请求（缺陷②），
     * 也不会把已换代条目的超时语义搞乱。</p>
     *
     * <p><b>重挂（onChat 非法输入分支）必须对新条目调用本方法登记新任务</b>：
     * 一次性任务已执行完就不再保护新条目，不补登记会造出「永不超时的劫持条目」（缺陷①）。</p>
     */
    private static void scheduleNumberTimeout(Player player, PendingInput input) {
        Bukkit.getScheduler().runTaskLater(input.plugin, () -> {
            PendingInput removed = pendingInputs.remove(input.playerId, input) ? input : null;
            if (removed != null) {
                player.sendMessage(removed.lang.getFor(player, "config.menu.input.timeout"));
                if (removed.onCancel != null) removed.onCancel.run();
            }
        }, INPUT_TIMEOUT_TICKS);
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
            // P2-32：NaN / Infinity / -Infinity 不是合法数字输入，且拦截荒谬量级（|v| > 1e9）。
            // 不拦的话 (int)NaN = 0、(int)1e300 = Integer.MAX_VALUE 会被 setter 静默吞下，
            // 造成 min-players=0（永不满足开局）/ Infinity 绕过 <=0 禁用语义等静默错乱。
            // 1e9 宽松到覆盖一切合理游戏参数（量级上亿的参数在任何玩法里都是荒谬值）。
            if (!Double.isFinite(value) || Math.abs(value) > INTEGER_INPUT_CLAMP) {
                throw new NumberFormatException("Not a valid finite number: " + msg);
            }
            if (pending.callback != null) {
                Bukkit.getScheduler().runTask(
                        Bukkit.getPluginManager().getPlugin("HilltopVillage"),
                        () -> pending.callback.accept(value));
            }
        } catch (NumberFormatException e) {
            event.getPlayer().sendMessage(pending.lang.getFor(event.getPlayer(), "config.menu.input.invalid-number", msg));
            // M-11 重挂：换成【新条目】并【登记新的超时任务】。旧的一次性超时任务只认旧条目的
            // 身份（两参 remove），不会误消费新条目；新条目有自己的 30 秒超时，
            // 30 秒临界点交错不会再造出「每条聊天都被吞掉」的永久劫持条目。
            PendingInput renewed = new PendingInput(pending.plugin, pending.callback, pending.onCancel,
                    pending.playerId, pending.lang);
            pendingInputs.put(id, renewed);
            scheduleNumberTimeout(event.getPlayer(), renewed);
        }
    }

    /**
     * 玩家退服时清除其待处理的聊天输入（静默丢弃，不回调 onCancel）。
     *
     * <p>为什么不回调：{@code onCancel} 的调用方约定是给<b>在线</b>玩家重开 GUI
     * （{@code reopen}）；已退服的玩家调它只会打出发包到失效连接，
     * 还会顺带把菜单监听器重新注册一次。</p>
     *
     * <p>与 30 秒超时任务是幂等关系：两者都精确移除各自条目，
     * 无论谁先执行，另一个都只会拿到 null（两参 remove）或空 map（单参 remove）。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pendingInputs.remove(id);
        pendingStringInputs.remove(id);
    }

    private static class PendingInput {
        final Plugin plugin;
        final Consumer<Double> callback;
        final Runnable onCancel;
        final UUID playerId;
        final LanguageManager lang;

        PendingInput(Plugin plugin, Consumer<Double> callback, Runnable onCancel, UUID playerId, LanguageManager lang) {
            this.plugin = plugin;
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
