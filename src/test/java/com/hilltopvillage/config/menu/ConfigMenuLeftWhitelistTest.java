package com.hilltopvillage.config.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

import org.bukkit.plugin.java.JavaPlugin;

import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@code slice-08-logic-009} —— 把 {@code else} 兜底收窄成显式 {@code ClickType.LEFT} 白名单。
 *
 * <p><b>缺陷原貌</b>：四个 {@code addAdjustableItem} helper 的最后一个分支写成
 * {@code } else { val = current + step; ... }}。因为 SHIFT 与 RIGHT 已被前面两个分支吃掉，
 * 这个 {@code else} 会接管<b>所有</b>剩余的 ClickType —— {@code MIDDLE}（创造模式中键复制）、
 * {@code DROP}（Q 键丢出）、{@code NUMBER_KEY}（按热键 1-9）、{@code SWAP_OFFHAND}（F 键）、
 * {@code DOUBLE_CLICK}（双击聚合）。结果是管理员想丢出一个图标、或按热键切物品，
 * 配置就被静默 +1 步并写盘。</p>
 *
 * <p><b>本类证明什么</b>：对<b>每一个</b>受影响槽位，非白名单的 ClickType 都不改值、也不动配置，
 * 而 {@code LEFT} 仍然 +1 步。<b>两个方向都断言</b> —— 只断言「MIDDLE 不改」的话，
 * 一个把整段分支删掉的实现（连 LEFT 也不改）同样会绿。</p>
 *
 * <p><b>三处调用点</b>（第四处 {@code WaveSettingsMenu} 的整数版在
 * {@link WaveMenusLeftWhitelistTest} 里，因为它需要 wave 数据）：</p>
 * <ul>
 *   <li>{@code GameRulesMenu} 的动态下界重载（整数版）</li>
 *   <li>{@code ItemSettingsMenu#addDblItem}</li>
 *   <li>{@code NodeSettingsMenu#addAdjustableItem}（直接继承外层的 double 版）</li>
 *   <li>{@code MonsterSettingsMenu#addAdjustableItem}</li>
 * </ul>
 */
class ConfigMenuLeftWhitelistTest extends MockBukkitTestBase {

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
        admin = server.addPlayer("whitelist-admin");
        players.add(admin);
    }

    @AfterEach
    void tearDown() {
        MenuTestSupport.clearPending(handler, players);
        if (lookup != null) {
            lookup.close();
        }
    }

    /** 一次左键 + 一步；非白名单点击必须做到「值不变」。 */
    private void assertOnlyLeftIncrements(AbstractConfigMenu menu, int slot, int before, int step,
                                           String what) {
        assertNotNull(menu.clickActions.get(slot),
                what + "：slot " + slot + " 必须登记了点击动作，否则本用例什么都没测到");

        List<ClickType> ignored = List.of(
                ClickType.MIDDLE, ClickType.DROP, ClickType.CONTROL_DROP,
                ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DOUBLE_CLICK,
                ClickType.WINDOW_BORDER_LEFT, ClickType.WINDOW_BORDER_RIGHT,
                ClickType.UNKNOWN);

        for (ClickType type : ignored) {
            int valueBefore = currentValue(menu, slot);
            MenuTestSupport.click(admin, menu, slot, type);
            server.getScheduler().performTicks(2);
            assertEquals(valueBefore, currentValue(menu, slot),
                    what + "：ClickType." + type + " 不是白名单成员，必须<b>完全不改</b>配置 —— "
                            + "原实现这里是 else 兜底，Q 键丢出/热键切换/中键复制都会静默把值 +" + step
                            + "（slice-08-logic-009）");
        }

        int valueBefore = currentValue(menu, slot);
        MenuTestSupport.click(admin, menu, slot, ClickType.LEFT);
        server.getScheduler().performTicks(2);
        assertEquals(valueBefore + step, currentValue(menu, slot),
                what + "：对照半 —— LEFT 是白名单成员，必须仍然 +" + step
                        + "。若这条也红，说明分支被整段删了，不是「收窄」");
        assertEquals(before + step, currentValue(menu, slot),
                what + "：整轮下来只应发生一次 +" + step);
    }

    /**
     * 读当前值。{@code menu} 的身份决定读哪个字段 —— 这样同一个断言方法能服务四处调用点，
     * 而不用为每处复制一份。
     */
    private int currentValue(AbstractConfigMenu menu, int slot) {
        if (menu instanceof GameRulesMenu) {
            // slot 10 = min-players（step 1）
            return cfg.getMinPlayers();
        }
        if (menu instanceof ItemSettingsMenu) {
            // slot 19 = hammer smash-timeout（step 20）
            return cfg.getHammerSmashTimeoutTicks();
        }
        if (menu instanceof NodeSettingsMenu) {
            // slot 10 = node base-health（step 50）
            return (int) cfg.getNodeBaseHealth();
        }
        if (menu instanceof MonsterSettingsMenu) {
            // slot 19 = explode-beetle health（step 10）
            return (int) cfg.getMonsters().get("explode-beetle").getHealth();
        }
        throw new IllegalArgumentException("unexpected menu " + menu.getClass());
    }

    private AbstractConfigMenu openMenu(AbstractConfigMenu menu) {
        menu.open();
        return menu;
    }

    // ================================================================== 调用点 1

    @Nested
    @DisplayName("调用点 1 —— GameRulesMenu 的整数版 helper")
    class GameRulesCallSite {

        @Test
        @DisplayName("MIDDLE / DROP / NUMBER_KEY / SWAP_OFFHAND 都不改 min-players，只有 LEFT 加 1")
        void nonLeftClicksAreInertOnMinPlayers() {
            cfg.setMinPlayers(5);
            AbstractConfigMenu menu = openMenu(new GameRulesMenu(admin, cm, null, lang));

            assertOnlyLeftIncrements(menu, 10, 5, 1, "GameRulesMenu[min-players]");
        }

        @Test
        @DisplayName("victory-waves 上的 MIDDLE 不改值（同一 helper 的第二个槽位，排除「只给某个槽位打了补丁」）")
        void nonLeftClicksAreInertOnVictoryWaves() {
            cfg.setVictoryWaves(7);
            AbstractConfigMenu menu = openMenu(new GameRulesMenu(admin, cm, null, lang));
            int slot = 14;

            MenuTestSupport.click(admin, menu, slot, ClickType.MIDDLE);
            server.getScheduler().performTicks(2);
            assertEquals(7, cfg.getVictoryWaves(),
                    "MIDDLE 不得把 victory-waves 从 7 改成 8 —— helper 是共用的，"
                            + "只修一个槽位不是 slice-08-logic-009 的修复范围");

            MenuTestSupport.click(admin, menu, slot, ClickType.DROP);
            server.getScheduler().performTicks(2);
            assertEquals(7, cfg.getVictoryWaves(), "Q 键丢出图标不该改胜利波数");

            MenuTestSupport.click(admin, menu, slot, ClickType.LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(8, cfg.getVictoryWaves(), "对照半：LEFT 仍然 +1");
        }
    }

    // ================================================================== 调用点 2

    @Nested
    @DisplayName("调用点 2 —— ItemSettingsMenu#addDblItem")
    class ItemSettingsCallSite {

        @Test
        @DisplayName("smash-timeout 上非白名单点击不改值，LEFT 加 20")
        void nonLeftClicksAreInertOnSmashTimeout() {
            cfg.setHammerSmashTimeoutTicks(600);
            AbstractConfigMenu menu = openMenu(new ItemSettingsMenu(admin, cm, null, lang));

            assertOnlyLeftIncrements(menu, 19, 600, 20, "ItemSettingsMenu[smash-timeout]");
        }

        @Test
        @DisplayName("同类的 double 槽位（med-charge，step 0.5）也不吃双击")
        void doubleSlotIgnoresDoubleClick() {
            cfg.setHammerMediumCharge(5.0);
            AbstractConfigMenu menu = openMenu(new ItemSettingsMenu(admin, cm, null, lang));
            int slot = 33;

            MenuTestSupport.click(admin, menu, slot, ClickType.DOUBLE_CLICK);
            server.getScheduler().performTicks(2);
            assertEquals(5.0, cfg.getHammerMediumCharge(),
                    "DOUBLE_CLICK 是 Bukkit 的「双击聚合」语义，管理员只是想快速点两次，"
                            + "不该被当成两次独立的 +0.5");

            MenuTestSupport.click(admin, menu, slot, ClickType.LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(5.5, cfg.getHammerMediumCharge(), "对照半：LEFT 仍然 +0.5");
        }
    }

    // ================================================================== 调用点 3

    @Nested
    @DisplayName("调用点 3 —— NodeSettingsMenu#addAdjustableItem")
    class NodeSettingsCallSite {

        @Test
        @DisplayName("node base-health 上非白名单点击不改值，LEFT 加 50")
        void nonLeftClicksAreInertOnNodeHealth() {
            cfg.setNodeBaseHealth(500.0);
            AbstractConfigMenu menu = openMenu(new NodeSettingsMenu(admin, cm, null, lang));

            assertOnlyLeftIncrements(menu, 10, 500, 50, "NodeSettingsMenu[base-health]");
        }

        @Test
        @DisplayName("buff-radius 上的 SWAP_OFFHAND（F 键）不改值")
        void swapOffhandDoesNotEditBuffRadius() {
            cfg.setNodeBuffRadius(20.0);
            AbstractConfigMenu menu = openMenu(new NodeSettingsMenu(admin, cm, null, lang));
            int slot = 14;

            MenuTestSupport.click(admin, menu, slot, ClickType.SWAP_OFFHAND);
            server.getScheduler().performTicks(2);
            assertEquals(20.0, cfg.getNodeBuffRadius(),
                    "SWAP_OFFHAND（F 键）是原版换手，不该把节点增益半径 +2");
        }
    }

    // ================================================================== 调用点 4

    @Nested
    @DisplayName("调用点 4 —— MonsterSettingsMenu#addAdjustableItem")
    class MonsterSettingsCallSite {

        @Test
        @DisplayName("beetle health 上非白名单点击不改值，LEFT 加 10")
        void nonLeftClicksAreInertOnMonsterHealth() {
            cfg.getMonsters().get("explode-beetle").setHealth(40);
            AbstractConfigMenu menu = openMenu(new MonsterSettingsMenu(admin, cm, null, lang));

            assertOnlyLeftIncrements(menu, 19, 40, 10, "MonsterSettingsMenu[health]");
        }

        @Test
        @DisplayName("speed 上的 NUMBER_KEY（热键 1-9）不改值 —— 双击行为与 0.05 步长共存")
        void numberKeyDoesNotEditSpeed() {
            cfg.getMonsters().get("explode-beetle").setSpeed(0.3);
            AbstractConfigMenu menu = openMenu(new MonsterSettingsMenu(admin, cm, null, lang));
            int slot = 20;

            MenuTestSupport.click(admin, menu, slot, ClickType.NUMBER_KEY);
            server.getScheduler().performTicks(2);
            assertEquals(0.3, cfg.getMonsters().get("explode-beetle").getSpeed(), 1e-9,
                    "NUMBER_KEY 是原版热键交换，不该把怪物速度 +0.05");
        }
    }

    // ================================================================== 反向对照

    @Nested
    @DisplayName("反向对照 —— 白名单必须仍然是白名单")
    class WhitelistIsNotOverNarrowed {

        @Test
        @DisplayName("RIGHT 与 LEFT 都在白名单内，且方向相反")
        void rightAndLeftBothStillWork() {
            cfg.setMinPlayers(5);
            AbstractConfigMenu menu = openMenu(new GameRulesMenu(admin, cm, null, lang));

            MenuTestSupport.click(admin, menu, 10, ClickType.RIGHT);
            server.getScheduler().performTicks(2);
            assertEquals(4, cfg.getMinPlayers(), "RIGHT 仍然是减号");

            MenuTestSupport.click(admin, menu, 10, ClickType.LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(5, cfg.getMinPlayers(), "LEFT 仍然是加号");
            assertNotEquals(4, cfg.getMinPlayers());
        }

        @Test
        @DisplayName("LEFT 在上界处被拦住（白名单化不该顺手放宽区间）")
        void leftStillRespectsMax() {
            cfg.setVictoryWaves(100);
            AbstractConfigMenu menu = openMenu(new GameRulesMenu(admin, cm, null, lang));

            MenuTestSupport.click(admin, menu, 14, ClickType.LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(100, cfg.getVictoryWaves(),
                    "100 是声明的上界，LEFT 不得把它推到 101");

            MenuTestSupport.click(admin, menu, 14, ClickType.MIDDLE);
            server.getScheduler().performTicks(2);
            assertEquals(100, cfg.getVictoryWaves(), "同时确认 MIDDLE 在上界处也不动");
        }

        @Test
        @DisplayName("SHIFT 分支仍在（收窄 else 不能顺手吃掉 SHIFT）")
        void shiftStillOpensChatInput() {
            cfg.setMinPlayers(5);
            AbstractConfigMenu menu = openMenu(new GameRulesMenu(admin, cm, null, lang));

            MenuTestSupport.click(admin, menu, 10, ClickType.SHIFT_LEFT);
            // SHIFT 只是登记一次聊天输入，此刻值还没变
            assertEquals(5, cfg.getMinPlayers(),
                    "SHIFT 分支不得直接改值 —— 它应该等聊天输入回来再写");

            MenuTestSupport.say(server, handler, admin, "9");
            assertEquals(9, cfg.getMinPlayers(),
                    "SHIFT 聊天输入仍然生效（收窄 else 不得把 SHIFT 一起吃成无操作）");
        }

        @Test
        @DisplayName("菜单确实渲染了这个槽位（动作不是凭空注册的）")
        void slotIsActuallyRendered() {
            AbstractConfigMenu menu = openMenu(new GameRulesMenu(admin, cm, null, lang));
            assertNotNull(menu.getInventory().getItem(10), "slot 10 必须有图标");
            assertNotEquals(Material.AIR, menu.getInventory().getItem(10).getType());
            assertTrue(menu.clickActions.containsKey(10),
                    "slot 10 必须同时在 clickActions 里 —— 只渲染不注册等于按钮坏了");
        }
    }
}
