package com.hilltopvillage.config.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@code slice-08-logic-009} 的<b>第六处、也是 round3 唯一未守护的调用点</b> ——
 * {@code WaveSettingsMenu.MonsterPropMenu#addDblItem}（源码 541-573 行，被守护的分支在 568 行）。
 *
 * <p><b>为什么必须单独一个类</b>：round1 的修复报告写「4 处」，实际源码有 6 处。
 * 前 5 处已由 {@link ConfigMenuLeftWhitelistTest} 与 {@link WaveMenusLeftWhitelistTest} 钉住；
 * 第 6 处嵌在 {@code public static class MonsterPropMenu} 里，构造需要 {@code monsterKey}
 * 与 {@code WaveDetailMenu parentMenu}，夹具与另外五处不同。round3 最终验证组实测：
 * 把这一处还原成裸 {@code else}，全量套件仍然 718 全绿 —— 修复处于裸奔状态喵。</p>
 *
 * <p><b>缺陷原貌</b>：{@code addDblItem} 的最后一个分支原本是 {@code } else { val = current + step; }}。
 * SHIFT 与 RIGHT 已被前两个分支吃掉，这个 {@code else} 就接管了<b>所有</b>剩余 ClickType ——
 * {@code MIDDLE}（创造模式中键复制）、{@code DROP}（Q 键丢出）、{@code NUMBER_KEY}（热键 1-9）、
 * {@code SWAP_OFFHAND}（F 键）、{@code DOUBLE_CLICK}（双击聚合）。管理员在怪物属性页里
 * 想把图标丢出去、或按热键切物品，配置就被静默 +1 步并写盘喵。</p>
 *
 * <p><b>覆盖的槽位</b>：{@code addDblItem} 是共用 helper，{@code MonsterPropMenu#buildMenu}
 * 里共 6 处调用（19 health / 20 speed / 21 base-damage / 46 自爆倍率或钩爪速度 /
 * 47-48 经 {@code addIntItem} 转调）。本类取三个形状各异的代表：</p>
 * <ul>
 *   <li>slot 19 —— {@code explode-beetle} 的 health（step 10，double）</li>
 *   <li>slot 46 —— {@code explode-beetle} 独有的自爆伤害倍率（step 0.5，double，switch 分支内）</li>
 *   <li>slot 47 —— {@code hook-claw-hunter} 的技能冷却（int 版 {@code addIntItem} 转调 {@code addDblItem}，
 *       证明收窄对整数委托路径同样生效）</li>
 * </ul>
 *
 * <p><b>两个方向都断言</b>：只断言「MIDDLE 不改」的话，一个把整段分支删掉的实现
 * （连 LEFT 也不改）同样会绿；因此每个正向半都配一条 LEFT 仍然按步长变化的对照半喵。</p>
 */
class MonsterPropMenuLeftWhitelistTest extends MockBukkitTestBase {

    private final NumericInputHandler handler = new NumericInputHandler();
    private final List<PlayerMock> players = new ArrayList<>();

    private BareHost host;
    private MockedStatic<JavaPlugin> lookup;
    private LanguageManager lang;
    private GameConfig cfg;
    private ConfigManager cm;
    private PlayerMock admin;

    @BeforeEach
    void setUpFixture() {
        host = loadBareHost();
        lookup = MenuTestSupport.pluginLookup(host);
        lang = MenuTestSupport.lang();
        cfg = new GameConfig();
        cm = MenuTestSupport.config(cfg);
        admin = server.addPlayer("prop-admin");
        players.add(admin);
    }

    @AfterEach
    void tearDown() {
        MenuTestSupport.clearPending(handler, players);
        if (lookup != null) {
            lookup.close();
        }
    }

    private GameConfig.MonsterConfig monster(String key) {
        GameConfig.MonsterConfig mc = cfg.getMonsters().get(key);
        assertNotNull(mc, "出厂配置必须包含怪种 " + key + "，否则下面的断言什么都没测到");
        return mc;
    }

    /**
     * 构造 {@code MonsterPropMenu}。{@code parentMenu} 只在返回按钮的 lambda 里被解引用
     * （本类从不点返回键），但这里仍用真实的 {@code WaveDetailMenu}，避免将来菜单在
     * {@code buildMenu()} 里提前读父菜单时静默 NPE喵。
     */
    private WaveSettingsMenu.MonsterPropMenu propMenu(String monsterKey) {
        WaveSettingsMenu waveMenu = new WaveSettingsMenu(admin, cm, null, lang);
        WaveSettingsMenu.WaveDetailMenu parent = new WaveSettingsMenu.WaveDetailMenu(admin, cm, 1, waveMenu, lang);
        WaveSettingsMenu.MonsterPropMenu menu =
                new WaveSettingsMenu.MonsterPropMenu(admin, cm, monsterKey, parent, lang);
        menu.open();
        return menu;
    }

    /**
     * 正向半：对每一个非白名单 ClickType，{@code read} 必须<b>完全不改</b>。
     * 白名单内的 SHIFT_* 由各自的分支处理（会开聊天输入），故不在此列。
     */
    private void assertNonWhitelistClicksAreInert(AbstractConfigMenu menu, int slot,
                                                   DoubleSupplier read, String what) {
        assertNotNull(menu.clickActions.get(slot),
                what + "：slot " + slot + " 必须登记了点击动作，否则本用例什么都没测到");

        List<ClickType> notWhitelisted = List.of(
                ClickType.MIDDLE, ClickType.DROP, ClickType.CONTROL_DROP,
                ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DOUBLE_CLICK,
                ClickType.WINDOW_BORDER_LEFT, ClickType.WINDOW_BORDER_RIGHT,
                ClickType.UNKNOWN);

        for (ClickType type : notWhitelisted) {
            double before = read.getAsDouble();
            MenuTestSupport.click(admin, menu, slot, type);
            server.getScheduler().performTicks(2);
            assertEquals(before, read.getAsDouble(), 1e-9,
                    what + "：ClickType." + type + " 不是白名单成员，配置值必须<b>完全不改</b> —— "
                            + "原实现这里是 else 兜底，Q 键丢出/热键切换/中键复制都会静默把值按步长改动"
                            + "（slice-08-logic-009 第 6 调用点 MonsterPropMenu#addDblItem）");
        }
    }

    // ================================================================== double 槽位

    @Nested
    @DisplayName("MonsterPropMenu#addDblItem 的 double 槽位 —— LEFT 白名单")
    class DblSlots {

        @Test
        @DisplayName("自爆倍率槽位（slot 46）：非白名单点击不改值，LEFT 加 0.5，RIGHT 减 0.5")
        void explodeMultiplierOnlyReactsToLeftAndRight() {
            double start = monster("explode-beetle").getExplodeDamageMultiplier();
            WaveSettingsMenu.MonsterPropMenu menu = propMenu("explode-beetle");
            int slot = 46;

            assertNonWhitelistClicksAreInert(menu, slot,
                    () -> monster("explode-beetle").getExplodeDamageMultiplier(),
                    "MonsterPropMenu[explode-multiplier]");

            double before = monster("explode-beetle").getExplodeDamageMultiplier();
            MenuTestSupport.click(admin, menu, slot, ClickType.LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(before + 0.5, monster("explode-beetle").getExplodeDamageMultiplier(), 1e-9,
                    "对照半：LEFT 是白名单成员，必须仍然 +0.5。若这条也红，说明分支被整段删了，"
                            + "不是「收窄」（slice-08-logic-009）");
            assertEquals(start + 0.5, monster("explode-beetle").getExplodeDamageMultiplier(), 1e-9,
                    "整轮下来只应发生一次 +0.5 —— 上面 9 个非白名单点击必须全部无副作用");

            MenuTestSupport.click(admin, menu, slot, ClickType.RIGHT);
            server.getScheduler().performTicks(2);
            assertEquals(start, monster("explode-beetle").getExplodeDamageMultiplier(), 1e-9,
                    "对照半：RIGHT 仍然是减号（收窄 else 不得把 RIGHT 一起吃成无操作）");
        }

        @Test
        @DisplayName("health 槽位（slot 19）：非白名单点击不改值，LEFT 加 10")
        void healthOnlyReactsToLeft() {
            monster("explode-beetle").setHealth(40);
            WaveSettingsMenu.MonsterPropMenu menu = propMenu("explode-beetle");

            assertNonWhitelistClicksAreInert(menu, 19,
                    () -> monster("explode-beetle").getHealth(),
                    "MonsterPropMenu[health]");

            MenuTestSupport.click(admin, menu, 19, ClickType.LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(50.0, monster("explode-beetle").getHealth(), 1e-9,
                    "对照半：LEFT 仍然 +10（health 的步长是 10）");
        }
    }

    // ================================================================== int 委托路径

    @Nested
    @DisplayName("addIntItem → addDblItem 的整数委托路径")
    class IntDelegatedSlot {

        @Test
        @DisplayName("技能冷却槽位（slot 47，hook-claw-hunter）：非白名单点击不改值，LEFT 加 20")
        void cooldownOnlyReactsToLeft() {
            monster("hook-claw-hunter").setCooldownTicks(60);
            WaveSettingsMenu.MonsterPropMenu menu = propMenu("hook-claw-hunter");
            int slot = 47;

            assertNonWhitelistClicksAreInert(menu, slot,
                    () -> monster("hook-claw-hunter").getCooldownTicks(),
                    "MonsterPropMenu[cooldown-ticks]");

            MenuTestSupport.click(admin, menu, slot, ClickType.LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(80, monster("hook-claw-hunter").getCooldownTicks(),
                    "对照半：整数版 addIntItem 转调同一个 addDblItem，LEFT 仍然 +20 —— "
                            + "证明收窄对整数委托路径同样生效（slice-08-logic-009）");
        }
    }

    // ================================================================== 防过度收紧

    @Nested
    @DisplayName("收窄 else 不得顺手吃掉 SHIFT 与渲染")
    class NotOverNarrowed {

        @Test
        @DisplayName("SHIFT_LEFT 仍然走聊天输入，不是静默无操作")
        void shiftStillOpensChatInput() {
            monster("explode-beetle").setExplodeDamageMultiplier(3.0);
            WaveSettingsMenu.MonsterPropMenu menu = propMenu("explode-beetle");

            MenuTestSupport.click(admin, menu, 46, ClickType.SHIFT_LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(3.0, monster("explode-beetle").getExplodeDamageMultiplier(), 1e-9,
                    "SHIFT 只登记一次聊天输入，此刻值还没变");

            MenuTestSupport.say(server, handler, admin, "7.5");
            assertEquals(7.5, monster("explode-beetle").getExplodeDamageMultiplier(), 1e-9,
                    "SHIFT 聊天输入仍然生效 —— 收窄 else 不得把 SHIFT 一起吃成无操作");
        }

        @Test
        @DisplayName("槽位确实渲染了图标（动作不是凭空注册的）")
        void slotIsActuallyRendered() {
            WaveSettingsMenu.MonsterPropMenu menu = propMenu("explode-beetle");

            assertNotNull(menu.getInventory().getItem(46), "slot 46 必须有图标");
            assertNotEquals(Material.AIR, menu.getInventory().getItem(46).getType(),
                    "slot 46 的图标不能是空气 —— 只注册动作不渲染等于按钮看不见");
            assertTrue(menu.clickActions.containsKey(46),
                    "slot 46 必须同时在 clickActions 里 —— 只渲染不注册等于按钮坏了");
        }
    }
}
