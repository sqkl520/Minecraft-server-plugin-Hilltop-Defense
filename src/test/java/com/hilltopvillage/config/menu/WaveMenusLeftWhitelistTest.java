package com.hilltopvillage.config.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
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
 * {@code slice-08-logic-009} 的<b>第四处</b>调用点 —— {@code WaveSettingsMenu.WaveDetailMenu}
 * 里的整数版 helper（源码约 308-340 行）。
 *
 * <p>为什么单独一个类：它嵌在 {@code public static class WaveDetailMenu} 里，构造需要
 * {@code waveNum} 且 {@code buildMenu()} 会在波次不存在时提前 return —— 与另外三处
 * 同一个 helper 形状、但夹具不同。round2 报告只说了「四个地方」，本类负责钉住这一处。</p>
 *
 * <p>被守护的分支：{@code } else if (clickType == ClickType.LEFT) {}，原本是
 * {@code } else {}} 兜底。</p>
 *
 * <p><b>round3 R-9 追加</b>：同一个 {@code WaveDetailMenu} 里，怪物组成行的 count 槽位
 * （源码约 261-267 行）也留着同型裸 {@code else}，直到 round3 才被清扫。见
 * {@link MonsterRowCountSlot}喵。</p>
 *
 * <p><b>round3-07 追加</b>：波次<b>列表</b>行（{@code WaveSettingsMenu#buildMenu()}，源码
 * {@code :91}）的非 SHIFT 兜底同样会跳详情页，作者 2026-11-04 裁决收窄为显式 LEFT。
 * 见 {@link WaveRowLeftOnly}喵。</p>
 */
class WaveMenusLeftWhitelistTest extends MockBukkitTestBase {

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
        admin = server.addPlayer("wave-admin");
        players.add(admin);
    }

    @AfterEach
    void tearDown() {
        MenuTestSupport.clearPending(handler, players);
        if (lookup != null) {
            lookup.close();
        }
    }

    /** 取一个出厂就存在的波次（{@code GameConfig} 默认有 1 / 5 / 10）。 */
    private GameConfig.WaveConfig defaultWave() {
        return cfg.getWaves().get(1);
    }

    /**
     * {@code WaveDetailMenu#buildMenu()} 会用 {@code parentMenu.getMobDisplayName(key)} 渲染
     * 每个怪种的行，所以 {@code parentMenu} 不能是 null（实测：NPE 在
     * {@code WaveDetailMenu.mobDisplayName}）。用真实的 {@link WaveSettingsMenu} 而不是 mock，
     * 因为 {@code getMobDisplayName} 是私有方法，mock 也桩不出来。
     */
    private WaveSettingsMenu.WaveDetailMenu detailMenu() {
        WaveSettingsMenu parent = new WaveSettingsMenu(admin, cm, null, lang);
        return new WaveSettingsMenu.WaveDetailMenu(admin, cm, 1, parent, lang);
    }

    private int totalMobs() {
        return defaultWave().getTotalMobs();
    }

    @Nested
    @DisplayName("WaveDetailMenu —— 总怪数槽位的 LEFT 白名单")
    class TotalMobsSlot {

        @Test
        @DisplayName("MIDDLE / DROP / NUMBER_KEY / SWAP_OFFHAND 都不改 total-mobs，只有 LEFT 加 2")
        void onlyLeftIncrementsTotalMobs() {
            int before = totalMobs();
            WaveSettingsMenu.WaveDetailMenu menu = detailMenu();
            menu.open();

            int slot = 19;
            assertNotNull(menu.clickActions.get(slot),
                    "slot 19 是 total-mobs 的可调项，必须登记动作");

            for (ClickType type : List.of(ClickType.MIDDLE, ClickType.DROP, ClickType.CONTROL_DROP,
                    ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DOUBLE_CLICK,
                    ClickType.UNKNOWN)) {
                MenuTestSupport.click(admin, menu, slot, type);
                server.getScheduler().performTicks(2);
                assertEquals(before, totalMobs(),
                        "ClickType." + type + " 不是白名单成员，本轮总怪数必须不变 —— "
                                + "原实现这里是 else 兜底，热键/丢出键会静默把每玩家怪数 +2"
                                + "（slice-08-logic-009）");
            }

            MenuTestSupport.click(admin, menu, slot, ClickType.LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(before + 2, totalMobs(),
                    "对照半：LEFT 仍然 +2（若这条也红，说明分支被整段删除，不是收窄）");
        }

        @Test
        @DisplayName("RIGHT 仍然 -2，且不会吃 DOUBLE_CLICK")
        void rightStillDecrementsAndDoubleClickIsInert() {
            int before = totalMobs();
            WaveSettingsMenu.WaveDetailMenu menu = detailMenu();
            menu.open();

            MenuTestSupport.click(admin, menu, 19, ClickType.RIGHT);
            server.getScheduler().performTicks(2);
            assertEquals(before - 2, totalMobs(), "RIGHT 仍然是减号");

            int mid = totalMobs();
            MenuTestSupport.click(admin, menu, 19, ClickType.DOUBLE_CLICK);
            server.getScheduler().performTicks(2);
            assertEquals(mid, totalMobs(),
                    "DOUBLE_CLICK 是「双击聚合」，不该被当成一次独立的 +2");
        }

        @Test
        @DisplayName("SHIFT_LEFT 仍然走聊天输入，不是静默无操作")
        void shiftStillOpensChatInput() {
            int before = totalMobs();
            WaveSettingsMenu.WaveDetailMenu menu = detailMenu();
            menu.open();

            MenuTestSupport.click(admin, menu, 19, ClickType.SHIFT_LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(before, totalMobs(), "SHIFT 只登记聊天输入，此刻不该改值");

            MenuTestSupport.say(server, handler, admin, "42");
            assertEquals(42, totalMobs(),
                    "SHIFT 聊天输入仍然生效（收窄 else 不得把 SHIFT 一起吃成无操作）");
        }
    }

    @Nested
    @DisplayName("WaveDetailMenu —— 怪物行的 count 槽位（round3 R-9）")
    class MonsterRowCountSlot {

        /**
         * 取怪物组成行的 count。出厂 {@code wave 1} 的 {@code compositions} 顺序是
         * {@code explode-beetle}(6) / {@code hook-claw-hunter}(2)，第一行落在 slot 27。
         */
        private int beetleCount() {
            return defaultWave().getCompositions().get("explode-beetle").getCount();
        }

        @Test
        @DisplayName("正向半：MIDDLE / DROP / NUMBER_KEY / SWAP_OFFHAND / DOUBLE_CLICK 都不改怪物数量")
        void nonLeftClicksAreInertOnMonsterCount() {
            int before = beetleCount();
            WaveSettingsMenu.WaveDetailMenu menu = detailMenu();
            menu.open();

            int slot = 27;
            assertNotNull(menu.clickActions.get(slot),
                    "slot 27 是怪物组成的第一行，必须登记动作");

            for (ClickType type : List.of(ClickType.MIDDLE, ClickType.DROP, ClickType.CONTROL_DROP,
                    ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DOUBLE_CLICK,
                    ClickType.WINDOW_BORDER_LEFT, ClickType.WINDOW_BORDER_RIGHT,
                    ClickType.UNKNOWN)) {
                MenuTestSupport.click(admin, menu, slot, type);
                server.getScheduler().performTicks(2);
                assertEquals(before, beetleCount(),
                        "ClickType." + type + " 不是白名单成员，该怪种 count 必须完全不变 —— "
                                + "原实现这里是 else 兜底，Q 键丢出/热键切换/中键复制/双击都会静默把"
                                + "怪物数量 +1 并写进配置（slice-08-logic-009 同型漏网点，round3 R-9）");
            }
        }

        @Test
        @DisplayName("反向对照半：同一槽位按 LEFT，数量仍然 +1")
        void leftStillIncrementsMonsterCount() {
            int before = beetleCount();
            WaveSettingsMenu.WaveDetailMenu menu = detailMenu();
            menu.open();

            MenuTestSupport.click(admin, menu, 27, ClickType.LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(before + 1, beetleCount(),
                    "对照半：LEFT 仍然 +1（若这条也红，说明分支被整段删除或恒假，不是收窄）"
                            + "（slice-08-logic-009 / round3 R-9）");
        }

        @Test
        @DisplayName("RIGHT 仍然 -1（收窄 LEFT 不得把减号一起吃掉）")
        void rightStillDecrementsMonsterCount() {
            int before = beetleCount();
            WaveSettingsMenu.WaveDetailMenu menu = detailMenu();
            menu.open();

            MenuTestSupport.click(admin, menu, 27, ClickType.RIGHT);
            server.getScheduler().performTicks(2);
            assertEquals(before - 1, beetleCount(),
                    "RIGHT 仍然是减号（round3 R-9）");
        }
    }

    @Nested
    @DisplayName("波次主菜单仍然把每个波次都挂上动作（白名单化没弄坏分发）")
    class WaveListStillDispatches {

        @Test
        @DisplayName("三个出厂波次各占一个槽位，且有动作")
        void everyShippedWaveHasAnAction() {
            WaveSettingsMenu menu = new WaveSettingsMenu(admin, cm, null, lang);
            menu.open();

            // slot 9 起顺序排布，出厂有 1 / 5 / 10 三个波次
            for (int slot : List.of(9, 10, 11)) {
                assertNotNull(menu.getInventory().getItem(slot),
                        "slot " + slot + " 必须有波次图标");
                assertTrue(menu.clickActions.containsKey(slot),
                        "slot " + slot + " 必须登记动作 —— 只渲染不注册等于按钮坏了");
            }
        }
    }

    /**
     * 波次列表行的非白名单点击（round3-07，作者 2026-11-04 裁决）。
     *
     * <p><b>缺陷</b>：{@code WaveSettingsMenu#buildMenu()} 的波次行原本是裸 {@code else}
     * 兜底，凡是「不是 SHIFT」的 {@link ClickType} 都会 {@code closeInventory()} 并延迟 2 tick
     * 打开 {@code WaveDetailMenu}。它不写配置（不是 R-9 那种数据损坏），但 Q 键丢出 / 中键 /
     * 数字键 / 副手交换都会把管理员从列表页弹进详情页，属 UX 缺陷。作者裁决收窄为显式 LEFT。</p>
     *
     * <p><b>怎么观测「有没有跳转」</b>——这一条是本类的关键，不能用「断言复述实现」蒙混：</p>
     * <ul>
     *   <li>跳转的真实副作用有<b>两个</b>：{@code admin.closeInventory()} 与 2 tick 后
     *       {@code admin.openInventory(detail)}。两者都作用在 {@code PlayerMock} 的真实视图状态上，
     *       所以完全不需要 spy 生产对象。</li>
     *   <li>因此断言的是 {@code admin.getOpenInventory().getTopInventory().getHolder()}：
     *       留在列表页时 holder 必须是<b>同一个</b> {@link WaveSettingsMenu} 实例（{@code assertSame}，
     *       顺带证明没被关掉重开）；跳转后 holder 必须是 {@link WaveSettingsMenu.WaveDetailMenu}。
     *       这是「玩家屏幕上现在是什么」而不是「代码里注册了什么」。</li>
     *   <li>{@code performTicks(2)} 推的正是那段 {@code runTaskLater(..., 2L)}；不推的话两种实现都
     *       看起来「没跳转」，测试会假绿。</li>
     * </ul>
     */
    @Nested
    @DisplayName("波次列表行：只有 LEFT 跳详情页（round3-07 作者裁决）")
    class WaveRowLeftOnly {

        /** 出厂三个波次里，波次 1 落在 slot 9（列表从 9 起按编号升序排布）。 */
        private static final int WAVE_ROW_SLOT = 9;
        private static final int WAVE_ONE = 1;

        private WaveSettingsMenu openMenu() {
            WaveSettingsMenu menu = new WaveSettingsMenu(admin, cm, null, lang);
            menu.open();
            return menu;
        }

        /**
         * 玩家当前顶层界面的 holder。
         *
         * <p>{@code PlayerMock#closeInventory()} 之后 {@code getOpenInventory().getTopInventory()}
         * 返回 <b>null</b>（实测，见 {@code ShopManagerTest#topHolder}），直接链式 {@code getHolder()}
         * 会在「确实已关闭」这个正确结果上抛 NPE。</p>
         */
        private Object topHolder() {
            InventoryView view = admin.getOpenInventory();
            if (view == null) return null;
            Inventory top = view.getTopInventory();
            return top == null ? null : top.getHolder();
        }

        @Test
        @DisplayName("正向半：MIDDLE / DROP / NUMBER_KEY / SWAP_OFFHAND / DOUBLE_CLICK 都不跳转、不关菜单")
        void nonLeftClicksNeitherJumpNorClose() {
            WaveSettingsMenu menu = openMenu();

            // 夹具前提：玩家此刻确实停在波次列表页上，且该槽位登记了动作
            assertSame(menu, topHolder(),
                    "夹具前提：open() 之后顶层界面必须是这个波次列表菜单");
            assertTrue(menu.clickActions.containsKey(WAVE_ROW_SLOT),
                    "夹具前提：slot " + WAVE_ROW_SLOT + " 是波次 1 的行，必须登记点击动作");

            for (ClickType type : List.of(ClickType.MIDDLE, ClickType.DROP, ClickType.NUMBER_KEY,
                    ClickType.SWAP_OFFHAND, ClickType.DOUBLE_CLICK, ClickType.CONTROL_DROP,
                    ClickType.WINDOW_BORDER_LEFT, ClickType.WINDOW_BORDER_RIGHT)) {
                MenuTestSupport.click(admin, menu, WAVE_ROW_SLOT, type);
                server.getScheduler().performTicks(2);

                Object holder = topHolder();
                assertSame(menu, holder,
                        "ClickType." + type + " 不是白名单成员，不该打开波次详情菜单、也不该关掉当前菜单"
                                + "（slice-08-logic-009 同型漏网点 / round3-07 作者裁决）—— "
                                + "原实现这里是裸 else 兜底，Q 键丢出、中键、数字键、副手交换都会把管理员"
                                + "从列表页弹进详情页；实际顶层 holder: "
                                + (holder == null ? "null（菜单被关掉了）" : holder.getClass().getSimpleName()));
                assertFalse(holder instanceof WaveSettingsMenu.WaveDetailMenu,
                        "ClickType." + type + " 之后顶层界面变成了 WaveDetailMenu —— "
                                + "说明白名单没有收窄（slice-08-logic-009 同型漏网点 / round3-07）");
            }
        }

        @Test
        @DisplayName("反向对照半：同一槽位按 LEFT 仍然打开波次详情菜单")
        void leftStillOpensDetailMenu() {
            WaveSettingsMenu menu = openMenu();
            assertSame(menu, topHolder(), "夹具前提：先停在波次列表页");

            MenuTestSupport.click(admin, menu, WAVE_ROW_SLOT, ClickType.LEFT);
            // 跳转是 runTaskLater(..., 2L)，不推 tick 的话「没跳转」和「跳转了」长得一样
            server.getScheduler().performTicks(2);

            Object holder = topHolder();
            assertNotNull(holder,
                    "按 LEFT 必须先 closeInventory() 再在 2 tick 后 openInventory(detail) —— "
                            + "顶层界面为 null 说明只有关闭、没有打开（round3-07 反向对照半）");
            assertInstanceOf(WaveSettingsMenu.WaveDetailMenu.class, holder,
                    "对照半：LEFT 仍然打开波次详情菜单。若这条也红，说明分支被整段删除或恒假，"
                            + "不是「收窄」而是「拆功能」（slice-08-logic-009 / round3-07 作者裁决）；"
                            + "实际顶层 holder: " + holder.getClass().getName());
        }

        @Test
        @DisplayName("删除路径未受影响：SHIFT_LEFT / SHIFT_RIGHT 仍然走 removeWave 并重开列表")
        void shiftClicksStillDeleteTheWave() {
            // ConfigManager 是 mock，默认 removeWave 不落值；把它接到真实 cfg 上，
            // 断言才能盯「波次真的从配置里消失了」而不是只盯一次调用计数。
            doAnswer(invocation -> {
                cfg.getWaves().remove((Integer) invocation.getArgument(0));
                return null;
            }).when(cm).removeWave(anyInt());

            WaveSettingsMenu shiftLeftMenu = openMenu();
            MenuTestSupport.click(admin, shiftLeftMenu, WAVE_ROW_SLOT, ClickType.SHIFT_LEFT);
            server.getScheduler().performTicks(2);

            verify(cm, times(1)).removeWave(WAVE_ONE);
            assertFalse(cfg.getWaves().containsKey(WAVE_ONE),
                    "SHIFT_LEFT 仍须真的删掉波次 1（P2-33 的 removeWave 记账路径不得被收窄波及）");
            assertSame(shiftLeftMenu, topHolder(),
                    "SHIFT_LEFT 删除后应当 reopen() 回到同一个波次列表菜单实例");

            // 删掉波次 1 后列表剩 [5, 10]，波次 5 落到 slot 9；用它走 SHIFT_RIGHT，
            // 证明两个 SHIFT 分支都还在，而不是只有一个活着。
            WaveSettingsMenu shiftRightMenu = openMenu();
            assertTrue(shiftRightMenu.clickActions.containsKey(WAVE_ROW_SLOT),
                    "夹具前提：删掉波次 1 后 slot 9 是波次 5");
            MenuTestSupport.click(admin, shiftRightMenu, WAVE_ROW_SLOT, ClickType.SHIFT_RIGHT);
            server.getScheduler().performTicks(2);

            verify(cm, times(1)).removeWave(5);
            assertFalse(cfg.getWaves().containsKey(5),
                    "SHIFT_RIGHT 仍然删除波次 5（收窄 else 不得把 SHIFT_RIGHT 一起吃掉）");
        }
    }
}
