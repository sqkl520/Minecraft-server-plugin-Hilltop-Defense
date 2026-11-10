package com.hilltopvillage.config.menu;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.util.LanguageManager;

/**
 * 五个 Settings 菜单共用的测试夹具。
 *
 * <p><b>为什么它存在 —— round2 的「构造器硬阻塞」判定被实测推翻</b></p>
 *
 * <p>round2 的结论是「这些菜单不可测，因为构造器第一行就调
 * {@code HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class)}」。实测分两步：</p>
 *
 * <ol>
 *   <li><b>阻塞本身是真的。</b>直接调用 {@code JavaPlugin.getPlugin(...)}（无论
 *       {@code loadBareHost()} 还是 {@code loadFullPlugin()} 之后）都抛
 *       {@code IllegalArgumentException: class ... is not initialized by a interface
 *       io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader} ——
 *       Paper 的 {@code getPlugin} 不走 {@code Bukkit.getPluginManager()}，而是从类加载器反查，
 *       MockBukkit 的类加载器不是 {@code ConfiguredPluginClassLoader}，所以永远查不到。</li>
 *   <li><b>但它可以被 {@code mockStatic} 绕过。</b>{@code JavaPlugin.getPlugin} 是静态方法，
 *       Mockito 5 的内联 mock maker 支持静态桩。把返回值钉成已启用的 {@code BareHost} 之后，
 *       五个菜单都能正常构造、{@code buildMenu()} 正常渲染、{@code reopen()} 正常注册监听器。</li>
 * </ol>
 *
 * <p>因此 round2 报告 §6 的 B-11「需要真实插件实例，判为不划算，未写（不是不会写）」，
 * 在技术上不成立 —— 成本是每个用例约 3 秒的 {@code BareHost} 加载，不是「不可测」。</p>
 *
 * <p><b>为什么 {@code plugin} 必须是「已启用」的真实插件</b>：每个点击动作末尾都会
 * {@code reopen()}，它调 {@code Bukkit.getScheduler().runTask(plugin, ...)} 与
 * {@code Bukkit.getPluginManager().registerEvents(this, plugin)}。用 Mockito mock 掉的插件
 * 会在 {@code performTicks} 时抛 {@code IllegalPluginAccess ... while not enabled}（实测，
 * probe F），因此这里用 {@code loadBareHost()} 的已启用实例。</p>
 *
 * <p><b>为什么不直接用 {@code clickActions.get(slot).onClick(...)}</b>：那会跳过
 * {@link AbstractConfigMenu#onClick} 的槽位/ClickType 分发，测不出「slot 22 到底有没有接上
 * 这个动作」。本夹具走完整的 {@link InventoryClickEvent} 路径，与真实客户端点击同一条链。</p>
 */
final class MenuTestSupport {

    private MenuTestSupport() {
    }

    /**
     * 语言桩：<b>原样回传 key</b>。
     *
     * <p>这一点很重要。{@code MonsterSettingsMenu#translateMaterial} 的判据是
     * {@code translated.equals(key) ? 回退到材质名 : translated}，若桩返回任意别的字符串，
     * 材质名分支就永远走不到，测试会在错误的分支上通过。</p>
     */
    static LanguageManager lang() {
        LanguageManager lang = mock(LanguageManager.class);
        when(lang.getFor(any(), anyString())).thenReturn("k");
        // varargs 必须用 any(Object[].class)：裸 any() 匹配不到「零个可变参数」与「多个」两种形状，
        // 桩不上时会静默返回 null（round2-09 §8 第 3 条）。
        when(lang.getFor(any(), anyString(), any(Object[].class))).thenAnswer(inv -> inv.getArgument(1));
        return lang;
    }

    /**
     * 配置桩：只提供 {@code getActiveConfig()}，返回调用方持有的真实 {@link GameConfig}。
     *
     * <p>用真实 {@code GameConfig} 而不是它的 mock，是因为断言要观察 setter 的<b>真实落值</b>
     * （{@code getSpawnRadiusMax()} 之类）。用 mock 的话，setter 调用只能靠 verify，而
     * 「值被改成了 105」这种跨字段抬值缺陷在 verify 里看不出来。</p>
     */
    static ConfigManager config(GameConfig cfg) {
        ConfigManager cm = mock(ConfigManager.class);
        when(cm.getActiveConfig()).thenReturn(cfg);
        return cm;
    }

    /** 打开 {@code getPlugin(...)} 的静态桩，返回值固定为已启用的 {@code host}。 */
    static MockedStatic<JavaPlugin> pluginLookup(HilltopVillagePlugin host) {
        MockedStatic<JavaPlugin> ms = mockStatic(JavaPlugin.class);
        ms.when(() -> JavaPlugin.getPlugin(HilltopVillagePlugin.class)).thenReturn(host);
        return ms;
    }

    /**
     * 构造一次真实的 {@link InventoryClickEvent} 并投递给菜单。
     *
     * <p>{@code InventoryView} 必须用 Mockito 桩：{@code SimpleInventoryViewMock#convertSlot}
     * 在 4.45.0 是 {@code UnimplementedOperationException}，连事件都构造不出来
     * （round2-09 §8 第 1 条）。{@code getInventory(rawSlot)} 是
     * {@code getClickedInventory()} 的唯一依据，{@code convertSlot} 是 {@code getSlot()} 的唯一依据，
     * 两个都要桩，漏一个就会让动作落在错误槽位上。</p>
     */
    static InventoryClickEvent click(PlayerMock admin, AbstractConfigMenu menu, int slot, ClickType type) {
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(menu.getInventory());
        when(view.getBottomInventory()).thenReturn(admin.getInventory());
        when(view.getPlayer()).thenReturn(admin);
        when(view.getType()).thenReturn(InventoryType.CHEST);
        when(view.getInventory(anyInt())).thenAnswer(invocation -> {
            int rawSlot = invocation.getArgument(0);
            if (rawSlot < 0) return null;
            return rawSlot < menu.getInventory().getSize()
                    ? menu.getInventory()
                    : admin.getInventory();
        });
        // 菜单 54 格时 top 占 rawSlot 0..53，菜单槽位号 == rawSlot，因此这里是恒等映射。
        when(view.convertSlot(anyInt())).thenAnswer(invocation -> invocation.getArgument(0));

        InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                slot, type, InventoryAction.PICKUP_ALL);
        menu.onClick(event);
        return event;
    }

    /** 投递一条聊天输入并让调度器跑完 {@code onChat} 回调与随后的 {@code reopen()}。 */
    static void say(ServerMock server, NumericInputHandler handler, PlayerMock player, String message) {
        handler.onChat(new AsyncPlayerChatEvent(true, player, message, Collections.emptySet()));
        server.getScheduler().performTicks(3);
    }

    /** 清掉静态 map 里本用例残留的待处理输入，避免泄漏到同 JVM 的下一个用例。 */
    static void clearPending(NumericInputHandler handler, List<PlayerMock> players) {
        for (PlayerMock player : players) {
            handler.onQuit(new PlayerQuitEvent(player, (String) null));
        }
        players.clear();
    }

    /** 一个只用于收集「本用例创建了哪些玩家」的小容器。 */
    static List<PlayerMock> players() {
        return new ArrayList<>();
    }
}
