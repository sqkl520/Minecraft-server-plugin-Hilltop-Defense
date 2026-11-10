package com.hilltopvillage.config.menu;

import com.hilltopvillage.util.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
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

    /**
     * 请求玩家在聊天栏输入一个数值，并把结果<b>钳制到调用方声明的取值区间</b>。
     *
     * <p>钳制在 {@link #onChat} 中统一执行——那是所有数值输入的<b>唯一入口</b>，因此鼠标
     * +/- 档位与 SHIFT 聊天输入共用同一份上下界，不会再出现「lore 上写着 10..1000，聊天却
     * 能写进 0」的分裂（slice-08-logic-001）。凡是界面上声明了取值范围的槽位，都必须调用
     * 本重载并传入该槽位的 {@code min}/{@code max}。</p>
     *
     * @param min 该槽位声明的下界（含）
     * @param max 该槽位声明的上界（含）；{@code min > max} 时按 {@code max} 取值
     */
    public static void requestNumber(Plugin plugin, Player player, String prompt,
                                     double min, double max,
                                     Consumer<Double> callback, Runnable onCancel, LanguageManager lang) {
        player.closeInventory();
        player.sendMessage("");
        player.sendMessage(lang.getFor(player, "config.menu.input.number-prompt"));
        player.sendMessage(prompt);
        player.sendMessage(lang.getFor(player, "config.menu.input.cancel-hint"));
        player.sendMessage("");

        PendingInput input = new PendingInput(plugin, callback, min, max, onCancel, player.getUniqueId(), lang);
        pendingInputs.put(player.getUniqueId(), input);
        scheduleNumberTimeout(player, input);
    }

    /**
     * 自由区间版本：<b>仅限界面上没有声明取值范围的输入</b>（例如「新建波次的编号」）。
     *
     * <p>只保留 ±1e9 的量程防护（P2-32）。凡是在 lore 里展示了 min/max 的槽位，必须改用带
     * {@code min}/{@code max} 的重载，否则区间对该路径形同虚设。</p>
     */
    public static void requestNumber(Plugin plugin, Player player, String prompt,
                                     Consumer<Double> callback, Runnable onCancel, LanguageManager lang) {
        requestNumber(plugin, player, prompt, -INTEGER_INPUT_CLAMP, INTEGER_INPUT_CLAMP, callback, onCancel, lang);
    }

    /** 整数版 {@link #requestNumber}：区间语义相同，最终按 int 消费。 */
    public static void requestInteger(Plugin plugin, Player player, String prompt,
                                      int min, int max,
                                      Consumer<Integer> callback, Runnable onCancel, LanguageManager lang) {
        requestNumber(plugin, player, prompt, min, max, d -> {
            // P2-32 整数域防护：超界值 clamp 到 ±1e9（在 int 量程内），避免 (int) 截断语义
            // 把 1e300 变 Integer.MAX_VALUE、把 1e-9 变 0。NaN/Infinity 已由 onChat 的
            // isFinite 检查拦在更早一层，到不了这里。
            double clamped = Math.max(-INTEGER_INPUT_CLAMP, Math.min(INTEGER_INPUT_CLAMP, d));
            callback.accept((int) clamped);
        }, onCancel, lang);
    }

    /** 自由区间整数版 {@link #requestInteger}：仅供没有声明取值范围的新建类输入。 */
    public static void requestInteger(Plugin plugin, Player player, String prompt,
                                      Consumer<Integer> callback, Runnable onCancel, LanguageManager lang) {
        requestInteger(plugin, player, prompt, Integer.MIN_VALUE, Integer.MAX_VALUE, callback, onCancel, lang);
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
            // slice-08-logic-001：所有数值输入的收敛点。调用方（各菜单的 addAdjustableItem 等）
            // 把该槽位在 lore 里声明的 min/max 带到这里，因此聊天输入与鼠标 +/- 走同一份区间，
            // 不再有「界面上写着 10..1000、聊天却能写进 0」的分裂。
            final double clamped = pending.clamp(value);
            if (pending.callback != null) {
                Bukkit.getScheduler().runTask(
                        Bukkit.getPluginManager().getPlugin("HilltopVillage"),
                        () -> pending.callback.accept(clamped));
            }
        } catch (NumberFormatException e) {
            event.getPlayer().sendMessage(pending.lang.getFor(event.getPlayer(), "config.menu.input.invalid-number", msg));
            // M-11 重挂：换成【新条目】并【登记新的超时任务】。旧的一次性超时任务只认旧条目的
            // 身份（两参 remove），不会误消费新条目；新条目有自己的 30 秒超时，
            // 30 秒临界点交错不会再造出「每条聊天都被吞掉」的永久劫持条目。
            PendingInput renewed = new PendingInput(pending.plugin, pending.callback, pending.min, pending.max,
                    pending.onCancel, pending.playerId, pending.lang);
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

    /**
     * 玩家打开任意容器时丢弃其待处理输入（slice-08-logic-007）。
     *
     * <p>原实现的 {@code pendingInputs} 只有三个出口：聊天消息、30 秒超时、退服；
     * 缺少「玩家放弃输入去干别的」这条转移。玩家 SHIFT+点击后跑去开箱子、开自己的配置 GUI
     * 或做其它 GUI 操作，条目仍会在 30 秒内生效，期间他在公屏打的任何能解析为数字的话都会被
     * {@code event.setCancelled(true)} 吞掉并直接写入配置（无二次确认）。</p>
     *
     * <p>打开容器是「我已离开聊天输入」的最强信号：此时丢弃条目、不回调 onCancel
     * （与 {@link #onQuit} 同口径 —— 玩家已经在做别的事，不应把 GUI 抢回来）。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        pendingInputs.remove(event.getPlayer().getUniqueId());
        pendingStringInputs.remove(event.getPlayer().getUniqueId());
    }

    private static class PendingInput {
        final Plugin plugin;
        final Consumer<Double> callback;
        /** 调用方为该槽位声明的下界（含）。 */
        final double min;
        /** 调用方为该槽位声明的上界（含）。 */
        final double max;
        final Runnable onCancel;
        final UUID playerId;
        final LanguageManager lang;

        PendingInput(Plugin plugin, Consumer<Double> callback, double min, double max,
                     Runnable onCancel, UUID playerId, LanguageManager lang) {
            this.plugin = plugin;
            this.callback = callback;
            this.min = min;
            this.max = max;
            this.onCancel = onCancel;
            this.playerId = playerId;
            this.lang = lang;
        }

        /** 把聊天输入的原始值钳制到本槽位声明的区间。 */
        double clamp(double value) {
            return Math.min(max, Math.max(min, value));
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
