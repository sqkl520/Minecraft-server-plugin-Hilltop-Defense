package com.hilltopvillage.config.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

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
 * {@code slice-08-logic-002} —— {@code spawn-radius-max} 槽位按右键，值会<b>往上跳</b>。
 *
 * <p><b>缺陷原貌</b>（{@code git show HEAD:...GameRulesMenu.java} 的 60-119 行）：</p>
 * <pre>
 * addAdjustableItem(22, ..., cfg.getSpawnRadiusMax(), 10, 200, 5,
 *     () -&gt; cfg.getSpawnRadiusMax(),
 *     v -&gt; cfg.setSpawnRadiusMax(Math.max(cfg.getSpawnRadiusMin() + 5, v)),  // ← 跨字段抬底
 *     ...);
 * ...
 * } else if (clickType == ClickType.RIGHT) {
 *     int val = getter.getAsInt() - step;
 *     if (val &gt;= min) setter.accept(val);       // ← min 只认字面 10，不认 cross-field 的 105
 * }
 * </pre>
 *
 * <p><b>跳高的精确算术</b>：槽位 20（{@code spawn-radius-min}）与槽位 22
 * 是<b>可以各自独立修改</b>的。管理员先把 min 从 20 抬到 100（合法，菜单允许 5..100），
 * 此时 max 仍是出厂 40 —— 一个<b>低于有效下界 105</b> 的存量状态。这时在 max 上按右键：</p>
 * <ol>
 *   <li>{@code val = 40 - 5 = 35}；</li>
 *   <li>{@code 35 >= 10}（字面 min）成立 → <b>放行</b>；</li>
 *   <li>{@code setSpawnRadiusMax(Math.max(100 + 5, 35)) = 105}。</li>
 * </ol>
 * <p>结果是 <b>40 → 105</b>：按下"减号"，数字上升 65。这就是 slice-08-logic-002 的签名。</p>
 *
 * <p><b>修复</b>：把有效下界显式交给 helper（{@code () -> cfg.getSpawnRadiusMin() + 5}），
 * 右键判据改成 {@code val >= floor}，{@code floor = Math.max(min, effectiveMin)}。
 * 同样输入下 {@code 35 >= 105} 为假 → setter 根本不被调用 → 值保持 40。</p>
 *
 * <p><b>为什么这里的判据是「不得上升」而不是「必须下降」</b>：修复后该场景的正确行为是
 * <b>不动</b>（值已经在有效下界以下，降无可降），而不是降到 35。判据必须能同时区分三种实现：
 * 缺陷版（40→105）、钳位版（40→105 或 40→40）、正确版（40→40）。"不得上升"这一条
 * 就把缺陷版挑出来了；"等于 40"再排除掉"顺手把 min 约束删掉"的实现。</p>
 */
class SpawnRadiusMaxRightClickTest extends MockBukkitTestBase {

    /** 与源码 slot 22 对齐（见 {@code GameRulesMenu.buildMenu()}）。 */
    private static final int SPAWN_MAX_SLOT = 22;

    private final NumericInputHandler handler = new NumericInputHandler();
    private final List<PlayerMock> players = new ArrayList<>();

    private BareHost host;
    private MockedStatic<JavaPlugin> lookup;
    private LanguageManager lang;
    private GameConfig cfg;
    private ConfigManager cm;
    private PlayerMock admin;
    private GameRulesMenu menu;

    @BeforeEach
    void setUpFixture() {
        host = loadBareHost();
        lookup = MenuTestSupport.pluginLookup(host);
        lang = MenuTestSupport.lang();
        cfg = new GameConfig();
        cm = MenuTestSupport.config(cfg);
        admin = server.addPlayer("radius-admin");
        players.add(admin);
        menu = new GameRulesMenu(admin, cm, null, lang);
        menu.open();
    }

    @AfterEach
    void tearDown() {
        MenuTestSupport.clearPending(handler, players);
        if (lookup != null) {
            lookup.close();
        }
    }

    /** 设定 min/max 后按一次右键，返回右键之后的值。 */
    private int rightClickAndRead(int spawnMin, int spawnMax) {
        cfg.setSpawnRadiusMin(spawnMin);
        cfg.setSpawnRadiusMax(spawnMax);
        MenuTestSupport.click(admin, menu, SPAWN_MAX_SLOT, ClickType.RIGHT);
        server.getScheduler().performTicks(2);
        return cfg.getSpawnRadiusMax();
    }

    // ================================================================== 缺陷本体

    @Nested
    @DisplayName("右键不得让 spawn-radius-max 上升（slice-08-logic-002 的签名）")
    class RightClickNeverRaises {

        @Test
        @DisplayName("存量越界：min=100、max=40（出厂值），右键后必须是 40，不是 105")
        void storedValueBelowEffectiveFloorIsNotJumpedUp() {
            int result = rightClickAndRead(100, 40);

            assertTrue(result <= 40,
                    "min=100 时有效下界是 105，而 max 的存量是出厂 40（管理员刚刚抬高了 min）。"
                            + "此时右键算出 40-5=35，缺陷版只比字面 min=10，"
                            + "35>=10 放行，setter 的 Math.max(105,35) 把值抬成 105 —— "
                            + "「按下减号，数字从 40 跳到 105」。实际得到 " + result);
            assertEquals(40, result,
                    "正确行为是<b>完全不动</b>：值已在有效下界以下，降无可降。"
                            + "得到 " + result + " 说明闸门没有把 35 拦住");
        }

        @Test
        @DisplayName("同一算术的另一组：min=60、max=20 → 右键后必须仍是 20（缺陷版跳成 65）")
        void secondArithmeticShowsTheSameJump() {
            int result = rightClickAndRead(60, 20);

            assertEquals(20, result,
                    "有效下界 = max(10, 65) = 65；存量 max=20 在它下面。"
                            + "缺陷版：20-5=15 >= 10 放行 → Math.max(65,15)=65，值 20→65（+45）。"
                            + "实际得到 " + result);
        }

        @Test
        @DisplayName("有效下界以上：右键正常减 5，不是恒不动")
        void decrementsNormallyAboveEffectiveFloor() {
            assertEquals(95, rightClickAndRead(80, 100),
                    "min=80 → 有效下界 85；max=100 在它之上，右键应正常减到 95。"
                            + "若这里也是 100，说明实现退化成「右键永远不动」，那是过度拦截");
        }

        @Test
        @DisplayName("恰好停在有效下界：110 - 5 = 105 = min+5，必须放行")
        void landsExactlyOnEffectiveFloor() {
            assertEquals(105, rightClickAndRead(100, 110),
                    "110-5=105 正好等于有效下界，应当放行并停在 105");
        }

        @Test
        @DisplayName("连续右键单调不增，且最终收敛到有效下界")
        void repeatedRightClicksAreMonotonic() {
            cfg.setSpawnRadiusMin(100);
            cfg.setSpawnRadiusMax(130);

            int previous = cfg.getSpawnRadiusMax();
            List<Integer> trail = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                MenuTestSupport.click(admin, menu, SPAWN_MAX_SLOT, ClickType.RIGHT);
                server.getScheduler().performTicks(2);
                int now = cfg.getSpawnRadiusMax();
                trail.add(now);
                assertTrue(now <= previous,
                        "第 " + (i + 1) + " 次右键把值从 " + previous + " 变成 " + now
                                + "（轨迹 " + trail + "）—— 减号按钮不得让该槽位上升");
                previous = now;
            }
            assertEquals(105, previous,
                    "连续右键最终收敛到有效下界 105（轨迹 " + trail + "）");
        }
    }

    // ================================================================== 约束仍在

    @Nested
    @DisplayName("跨字段约束仍然成立（修复不能靠删掉约束换绿）")
    class CrossFieldInvariant {

        @Test
        @DisplayName("任意 (min,max) 组合右键后，max 都不会低于 max(原值, min+5)")
        void maxNeverDropsBelowTheEffectiveFloor() {
            // 注意：我不要求 post >= min+5，因为 min 与 max 可以各自独立改，
            // 存在「min 已抬到 100、max 还是出厂 40」这种存量越界状态 —— 右键降不下去，
            // 也不该被抬上去（抬上去就是 slice-08-logic-002 本身）。
            // 可断言的不变量是：右键要么不动、要么落在一个不低于有效下界的值上。
            for (int spawnMin : new int[]{5, 20, 30, 60, 95, 100}) {
                for (int spawnMax : new int[]{10, 40, spawnMin + 5, spawnMin + 10, 200}) {
                    if (spawnMax < 10 || spawnMax > 200) {
                        continue;
                    }
                    cfg.setSpawnRadiusMin(spawnMin);
                    cfg.setSpawnRadiusMax(spawnMax);
                    MenuTestSupport.click(admin, menu, SPAWN_MAX_SLOT, ClickType.RIGHT);
                    server.getScheduler().performTicks(2);
                    int after = cfg.getSpawnRadiusMax();
                    int floor = Math.max(10, spawnMin + 5);
                    assertTrue(after >= Math.min(spawnMax, floor),
                            "min=" + spawnMin + " max=" + spawnMax + " → 右键后 max=" + after
                                    + "，跌到了 max(原值, min+5)=" + Math.min(spawnMax, floor) + " 以下");
                    assertTrue(after <= spawnMax,
                            "min=" + spawnMin + " max=" + spawnMax + " → 右键后 max=" + after
                                    + " —— 减号按钮不得抬高该值（slice-08-logic-002 的签名）");
                }
            }
        }

        @Test
        @DisplayName("左键仍然 +5，且停在声明上界 200")
        void leftStillIncrementsAndRespectsDeclaredMax() {
            cfg.setSpawnRadiusMin(20);
            cfg.setSpawnRadiusMax(190);

            MenuTestSupport.click(admin, menu, SPAWN_MAX_SLOT, ClickType.LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(195, cfg.getSpawnRadiusMax(), "左键 190 → 195");

            MenuTestSupport.click(admin, menu, SPAWN_MAX_SLOT, ClickType.LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(200, cfg.getSpawnRadiusMax(), "左键 195 → 200");

            MenuTestSupport.click(admin, menu, SPAWN_MAX_SLOT, ClickType.LEFT);
            server.getScheduler().performTicks(2);
            assertEquals(200, cfg.getSpawnRadiusMax(), "200 是声明上界，再按不得变成 205");
        }

        @Test
        @DisplayName("SHIFT 聊天输入被 [min+5, 200] 双向钳制（slice-08-logic-003 的同类现场）")
        void shiftChatIsClampedOnBothEnds() {
            cfg.setSpawnRadiusMin(100);
            cfg.setSpawnRadiusMax(120);

            MenuTestSupport.click(admin, menu, SPAWN_MAX_SLOT, ClickType.SHIFT_LEFT);
            MenuTestSupport.say(server, handler, admin, "5000");
            assertEquals(200, cfg.getSpawnRadiusMax(),
                    "聊天写 5000 必须被<b>上界</b>钳到 200。修复前该分支根本不传 max，"
                            + "只吃 ±1e9 的宽松量程，5000 会原样落盘");

            MenuTestSupport.click(admin, menu, SPAWN_MAX_SLOT, ClickType.SHIFT_LEFT);
            MenuTestSupport.say(server, handler, admin, "1");
            assertEquals(105, cfg.getSpawnRadiusMax(),
                    "聊天写 1 必须被<b>有效下界</b>钳到 105，而不是字面 min=10");
        }

        @Test
        @DisplayName("SHIFT 聊天输入在区间内原样接受（对照半，排除恒返边界）")
        void shiftChatAcceptsValueInsideRange() {
            cfg.setSpawnRadiusMin(100);
            cfg.setSpawnRadiusMax(120);

            MenuTestSupport.click(admin, menu, SPAWN_MAX_SLOT, ClickType.SHIFT_LEFT);
            MenuTestSupport.say(server, handler, admin, "150");

            assertEquals(150, cfg.getSpawnRadiusMax(),
                    "150 在 [105, 200] 内，必须原样写入");
        }
    }

    // ================================================================== 夹具自检

    @Nested
    @DisplayName("夹具自检")
    class Wiring {

        @Test
        @DisplayName("slot 22 真的渲染了图标并登记了动作")
        void slotTwentyTwoIsWired() {
            assertNotNull(menu.getInventory().getItem(SPAWN_MAX_SLOT),
                    "slot 22 必须有图标 —— 否则本类所有点击都落在空气上");
            assertTrue(menu.clickActions.containsKey(SPAWN_MAX_SLOT),
                    "slot 22 必须登记点击动作");
        }

        @Test
        @DisplayName("同菜单的普通槽位（min-players）不受本修复影响")
        void unrelatedSlotStillUsesItsOwnFloor() {
            cfg.setMinPlayers(10);
            MenuTestSupport.click(admin, menu, 10, ClickType.RIGHT);
            server.getScheduler().performTicks(2);
            assertEquals(9, cfg.getMinPlayers(),
                    "min-players 的下界是字面 1，右键 10 → 9 必须正常");
        }
    }
}
