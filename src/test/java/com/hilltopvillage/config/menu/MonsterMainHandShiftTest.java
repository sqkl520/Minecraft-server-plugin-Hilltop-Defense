package com.hilltopvillage.config.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

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
 * {@code slice-08-logic-004} —— 主手槽位缺 SHIFT 分支。
 *
 * <p><b>缺陷原貌</b>（{@code MonsterSettingsMenu} slot 30 的点击 lambda）：lore 里写着
 * {@code config.menu.common.click-toggle-custom}（"Shift+Click 自定义"），但该槽位
 * <b>完全不读 ClickType</b> —— 任何点击都走「循环下一个预设材质」。副手（slot 31）与四件护甲
 * （{@code addArmorSlot}）都有 SHIFT 分支，只有主手漏了。</p>
 *
 * <p>后果：主手只能从 {@code WEAPON_CYCLE} 那 12 个预设里选，<b>永远无法输入任意材质</b>。
 * 界面上承诺的能力与实际行为不符 —— 这是"UI 撒谎"类缺陷，比崩溃更难发现。</p>
 *
 * <p><b>修复</b>：补齐 {@code if (ct == SHIFT_LEFT || ct == SHIFT_RIGHT)} 分支，
 * 走 {@code NumericInputHandler.requestString}，回调里用
 * {@code Material.getMaterial(v.toUpperCase(ROOT))} + {@code isItem()} 守卫。</p>
 *
 * <p><b>本类怎么区分三种实现</b>：</p>
 * <ul>
 *   <li>缺陷版：SHIFT 与 LEFT 走同一条路 → 材质被"循环"到下一个，且<b>没有聊天提示</b>；</li>
 *   <li>修复版：SHIFT 弹出聊天输入，输入后材质被设为<b>任意</b>材质（不在预设表里也能设）；</li>
 *   <li>过度修复（SHIFT 也走循环）：与缺陷版不可区分 —— 所以必须同时断言"循环没发生"
 *       与"聊天输入真的生效"，用<b>预设表之外</b>的材质做输入值。</li>
 * </ul>
 */
class MonsterMainHandShiftTest extends MockBukkitTestBase {

    /** slot 30 = 主手，slot 31 = 副手（对照组）。 */
    private static final int MAIN_HAND_SLOT = 30;
    private static final int OFF_HAND_SLOT = 31;

    /** 刻意选一个<b>不在</b> WEAPON_CYCLE 里的物品材质。 */
    private static final Material NON_PRESET_ITEM = Material.BLAZE_ROD;
    /** 一个存在但不是物品的材质（isItem() == false）。 */
    private static final Material NON_ITEM = Material.WATER;

    private final NumericInputHandler handler = new NumericInputHandler();
    private final List<PlayerMock> players = new ArrayList<>();

    private BareHost host;
    private MockedStatic<JavaPlugin> lookup;
    private LanguageManager lang;
    private GameConfig cfg;
    private ConfigManager cm;
    private PlayerMock admin;
    private MonsterSettingsMenu menu;

    @BeforeEach
    void setUpFixture() {
        host = loadBareHost();
        lookup = MenuTestSupport.pluginLookup(host);
        lang = MenuTestSupport.lang();
        cfg = new GameConfig();
        cm = MenuTestSupport.config(cfg);
        admin = server.addPlayer("mob-admin");
        players.add(admin);
        menu = new MonsterSettingsMenu(admin, cm, null, lang);
        menu.open();
    }

    @AfterEach
    void tearDown() {
        MenuTestSupport.clearPending(handler, players);
        if (lookup != null) {
            lookup.close();
        }
    }

    private GameConfig.MonsterConfig beetle() {
        return cfg.getMonsters().get("explode-beetle");
    }

    // ================================================================== SHIFT 分支存在

    @Nested
    @DisplayName("SHIFT 会打开聊天输入，而不是循环材质")
    class ShiftOpensCustomInput {

        @Test
        @DisplayName("SHIFT_LEFT 不改变主手材质（循环会立刻改值）")
        void shiftLeftDoesNotCycle() {
            beetle().setMainHandItem(Material.DIAMOND_AXE);

            MenuTestSupport.click(admin, menu, MAIN_HAND_SLOT, ClickType.SHIFT_LEFT);
            server.getScheduler().performTicks(2);

            assertEquals(Material.DIAMOND_AXE, beetle().getMainHandItem(),
                    "SHIFT_LEFT 必须走「自定义输入」分支。"
                            + "缺陷版不读 ClickType，DIAMOND_AXE 会被循环成 BOW —— "
                            + "lore 承诺的 Shift+Click Custom 根本不存在（slice-08-logic-004）");
        }

        @Test
        @DisplayName("SHIFT_RIGHT 同样不循环")
        void shiftRightDoesNotCycle() {
            beetle().setMainHandItem(Material.DIAMOND_AXE);

            MenuTestSupport.click(admin, menu, MAIN_HAND_SLOT, ClickType.SHIFT_RIGHT);
            server.getScheduler().performTicks(2);

            assertEquals(Material.DIAMOND_AXE, beetle().getMainHandItem(),
                    "SHIFT_RIGHT 也必须走自定义输入分支");
        }

        @Test
        @DisplayName("SHIFT 后聊天输入任意材质生效 —— 而且是预设表里没有的那个")
        void shiftChatCanSetAnyItemMaterial() {
            beetle().setMainHandItem(Material.DIAMOND_AXE);

            MenuTestSupport.click(admin, menu, MAIN_HAND_SLOT, ClickType.SHIFT_LEFT);
            MenuTestSupport.say(server, handler, admin, NON_PRESET_ITEM.name());

            assertEquals(NON_PRESET_ITEM, beetle().getMainHandItem(),
                    "BLAZE_ROD 不在 WEAPON_CYCLE 里。缺陷版<b>永远</b>拿不到它 —— "
                            + "这正是 slice-08-logic-004 的实际后果：主手只能从 12 个预设里选");
        }

        @Test
        @DisplayName("SHIFT 聊天输入小写材质名也能识别（toUpperCase(ROOT)）")
        void shiftChatAcceptsLowercase() {
            MenuTestSupport.click(admin, menu, MAIN_HAND_SLOT, ClickType.SHIFT_LEFT);
            MenuTestSupport.say(server, handler, admin, "blaze_rod");

            assertEquals(NON_PRESET_ITEM, beetle().getMainHandItem(),
                    "小写输入必须被 toUpperCase(Locale.ROOT) 归一化");
        }

        @Test
        @DisplayName("SHIFT 聊天输入空串 = 清空主手（setMainHandItem(null)）")
        void blankInputClearsTheSlot() {
            beetle().setMainHandItem(Material.DIAMOND_AXE);

            MenuTestSupport.click(admin, menu, MAIN_HAND_SLOT, ClickType.SHIFT_LEFT);
            MenuTestSupport.say(server, handler, admin, "");

            assertNull(beetle().getMainHandItem(),
                    "空输入应清空主手 —— 缺陷版连这条路径都进不去");
        }

        @Test
        @DisplayName("SHIFT 聊天输入非法材质名时保留原值并提示")
        void unknownMaterialIsRejected() {
            beetle().setMainHandItem(Material.DIAMOND_AXE);
            admin.nextMessage(); // 清空提示消息队列，便于下面断言真的发了消息

            MenuTestSupport.click(admin, menu, MAIN_HAND_SLOT, ClickType.SHIFT_LEFT);
            MenuTestSupport.say(server, handler, admin, "NOT_A_REAL_MATERIAL");

            assertEquals(Material.DIAMOND_AXE, beetle().getMainHandItem(),
                    "解析失败（null）时不得覆盖既有材质");
            assertNotNull(admin.nextMessage(),
                    "必须给管理员一条错误提示，否则他不知道为什么没生效");
        }
    }

    // ================================================================== 循环分支仍完好

    @Nested
    @DisplayName("非 SHIFT 点击仍然循环预设（修复不得吃掉原有行为）")
    class NonShiftStillCycles {

        @Test
        @DisplayName("LEFT 循环到 WEAPON_CYCLE 的下一个")
        void leftCyclesToNextPreset() {
            beetle().setMainHandItem(Material.DIAMOND_AXE);

            MenuTestSupport.click(admin, menu, MAIN_HAND_SLOT, ClickType.LEFT);
            server.getScheduler().performTicks(2);

            assertEquals(Material.DIAMOND_SWORD, beetle().getMainHandItem(),
                    "DIAMOND_AXE 在 WEAPON_CYCLE 里排第一，LEFT 应循环到 DIAMOND_SWORD");
        }

        @Test
        @DisplayName("null 主手时 LEFT 落到预设表第一个")
        void leftFromNullStartsAtFirstPreset() {
            beetle().setMainHandItem(null);

            MenuTestSupport.click(admin, menu, MAIN_HAND_SLOT, ClickType.LEFT);
            server.getScheduler().performTicks(2);

            assertEquals(Material.DIAMOND_AXE, beetle().getMainHandItem(),
                    "null 时 cycleMaterial 返回 cycleList[0]");
        }

        @Test
        @DisplayName("预设表之外的材质 LEFT 也回落到第一个（不是崩、不是保持）")
        void leftFromUnknownMaterialFallsBackToFirst() {
            beetle().setMainHandItem(NON_PRESET_ITEM);

            MenuTestSupport.click(admin, menu, MAIN_HAND_SLOT, ClickType.LEFT);
            server.getScheduler().performTicks(2);

            assertEquals(Material.DIAMOND_AXE, beetle().getMainHandItem(),
                    "BLAZE_ROD 不在循环表里，cycleMaterial 的兜底是 cycleList[0]");
        }

        @Test
        @DisplayName("MIDDLE 也会循环 —— 主手槽位本就没有白名单，这是既有行为不是 009 的现场")
        void middleAlsoCyclesAsBefore() {
            beetle().setMainHandItem(Material.DIAMOND_AXE);

            MenuTestSupport.click(admin, menu, MAIN_HAND_SLOT, ClickType.MIDDLE);
            server.getScheduler().performTicks(2);

            assertEquals(Material.DIAMOND_SWORD, beetle().getMainHandItem(),
                    "主手槽位的 else 分支（非 SHIFT → 循环）是<b>设计如此</b>：循环没有副作用，"
                            + "不像数值槽位那样会静默改配置。这里钉住既有行为，"
                            + "避免后续把 009 的白名单化误搬到本槽位");
        }
    }

    // ================================================================== 对照：副手本来就有

    @Nested
    @DisplayName("对照组 —— 副手 SHIFT 分支（缺陷版与修复版都该绿，证明夹具本身没问题）")
    class OffHandControl {

        @Test
        @DisplayName("副手 SHIFT 也不循环，且能设任意材质")
        void offHandShiftWorks() {
            beetle().setOffHandItem(Material.SHIELD);

            MenuTestSupport.click(admin, menu, OFF_HAND_SLOT, ClickType.SHIFT_LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(Material.SHIELD, beetle().getOffHandItem(),
                    "副手 SHIFT 不循环（它一直有 SHIFT 分支）");

            MenuTestSupport.say(server, handler, admin, NON_PRESET_ITEM.name());
            assertEquals(NON_PRESET_ITEM, beetle().getOffHandItem(),
                    "副手 SHIFT 聊天输入生效");
        }
    }

    // ================================================================== isItem 守卫

    @Nested
    @DisplayName("fix-036 的 isItem() 守卫（同一段新增代码，顺带钉住）")
    class IsItemGuard {

        @Test
        @DisplayName("主手 SHIFT 输入非物品材质（WATER）被拒绝，原值保留")
        void nonItemMaterialIsRejectedOnMainHand() {
            beetle().setMainHandItem(Material.DIAMOND_AXE);
            assertTrue(!NON_ITEM.isItem(),
                    "夹具前提：WATER.isItem() 必须是 false，否则本条测不到守卫");

            MenuTestSupport.click(admin, menu, MAIN_HAND_SLOT, ClickType.SHIFT_LEFT);
            MenuTestSupport.say(server, handler, admin, NON_ITEM.name());

            assertEquals(Material.DIAMOND_AXE, beetle().getMainHandItem(),
                    "WATER 存在但不是物品，写进配置会让 WaveManager 的 new ItemStack(mat) 抛错。"
                            + "必须被 isItem() 挡下（fix-round1 fix-036）");
        }
    }
}
