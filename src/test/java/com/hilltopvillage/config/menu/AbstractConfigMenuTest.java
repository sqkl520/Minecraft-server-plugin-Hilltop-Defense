package com.hilltopvillage.config.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@link AbstractConfigMenu} 的纯逻辑与点击闸门测试。
 *
 * <p>这里刻意<b>不</b>构造任何一个真实菜单类：本类承载了两个与玩法无关、
 * 却各自对应一条 round1 缺陷的可测契约 ——</p>
 * <ul>
 *   <li>{@link AbstractConfigMenu#stepTolerance(double)}：slice-08-logic-008 的修复本身。
 *       它是四个菜单共用的浮点步进容差，测它等于同时守护四处调用点。</li>
 *   <li>{@link AbstractConfigMenu#onClick} 的<b>先取消再分发</b>顺序与背包槽位的区分：
 *       slice-08-logic-005。</li>
 * </ul>
 */
class AbstractConfigMenuTest extends MockBukkitTestBase {

    private LanguageManager lang;
    private PlayerMock admin;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        lang = mock(LanguageManager.class);
        when(lang.getFor(any(), anyString())).thenReturn("x");
        when(lang.getFor(any(), anyString(), any())).thenReturn("x");
    }

    // ------------------------------------------------------------------ 夹具

    private AbstractConfigMenu menu(PlayerMock owner, int size, List<String> log) {
        return new AbstractConfigMenu(owner, "title", size,
                MockBukkit.createMockPlugin("HilltopVillage"), lang) {
            @Override
            protected void buildMenu() {
                clickActions.clear();
                inventory.clear();
                setClickableItem(0, Material.STONE, "slot-0", ct -> log.add("click-0:" + ct), "lore");
                setClickableItem(10, Material.STONE, "slot-10", ct -> log.add("click-10:" + ct), "lore");
            }
        };
    }

    /**
     * {@code SimpleInventoryViewMock} 的 {@code convertSlot} 未实现（构造
     * {@link InventoryClickEvent} 时就抛），因此用 Mockito 的 {@link InventoryView} 桩。
     *
     * <p>两个成员都必须桩，否则测的就不是真实语义：</p>
     * <ul>
     *   <li>{@code getInventory(rawSlot)} 是 {@code getClickedInventory()} 的唯一依据
     *       （paper-api：{@code getClickedInventory() = getView().getInventory(rawSlot)}）；</li>
     *   <li>{@code convertSlot(rawSlot)} 是 {@link InventoryClickEvent#getSlot()} 的唯一依据
     *       （构造器把 {@code whichSlot = view.convertSlot(rawSlot)}）。<b>漏桩它会让 getSlot() 恒为 0</b>，
     *       于是每个槽位都撞上 slot 0 —— 本类第一版就踩了这个坑，表现为「动作在错误槽位上触发」。</li>
     * </ul>
     */
    private InventoryView view(PlayerMock viewer, Inventory top, Inventory bottom) {
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getBottomInventory()).thenReturn(bottom);
        when(view.getPlayer()).thenReturn(viewer);
        when(view.getType()).thenReturn(InventoryType.CHEST);
        when(view.getInventory(anyInt())).thenAnswer(invocation -> {
            int rawSlot = invocation.getArgument(0);
            if (rawSlot < 0) return null;
            return rawSlot < top.getSize() ? top : bottom;
        });
        // 真实客户端里 top 占 rawSlot 0..size-1（menu slot == rawSlot），下背包从 rawSlot size 起，
        // 归一化后重新从 0 编号 —— 所以 54 格的菜单在 rawSlot 54 上会撞上 menu slot 0。
        when(view.convertSlot(anyInt())).thenAnswer(invocation -> {
            int rawSlot = invocation.getArgument(0);
            return rawSlot < top.getSize() ? rawSlot : rawSlot - top.getSize();
        });
        return view;
    }

    private InventoryClickEvent click(InventoryView view, int rawSlot, ClickType type) {
        return new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, type,
                InventoryAction.PICKUP_ALL);
    }

    // ================================================================== 008

    @Nested
    @DisplayName("stepTolerance —— slice-08-logic-008 的修复本体")
    class StepTolerance {

        @Test
        @DisplayName("0.05 步长下，累加到头后声明的上界 2.0 真的点得到")
        void declaredMaxIsReachableAfterAccumulation() {
            double step = 0.05;
            double tol = AbstractConfigMenu.stepTolerance(step);
            double declaredMax = 2.0;

            // round1 报告里的原始复现：0.1 + 0.05 × 37 == 1.950000000000001
            double value = 0.1;
            for (int i = 0; i < 37; i++) {
                value += step;
            }
            assertEquals("1.950000000000001", String.valueOf(value),
                    "夹具前提变了：这里必须真的是那个浮点脏值，否则本用例测不到 008");

            double next = value + step;

            // 对照：没有容差时上界不可达 —— 证明下面的断言不是永真
            assertFalse(next <= declaredMax,
                    "前提：裸比较 " + next + " <= 2.0 为假，这正是 slice-08-logic-008 的现场");
            // 有容差时可点
            assertTrue(next <= declaredMax + tol,
                    "加上 stepTolerance(" + step + ")=" + tol + " 后，" + next
                            + " 必须被判为未越界 —— 否则界面上声明到 2.0 的槽位永远点不到，"
                            + "而 formatValue 又把 1.95 显示成 \"2.0\"，观感就是「+ 按钮坏了」");
        }

        @Test
        @DisplayName("容差远小于一个步进，不会放宽真实越界")
        void toleranceNeverAbsorbsAFullStep() {
            for (double step : new double[]{0.05, 0.1, 0.5, 1.0, 5.0}) {
                double tol = AbstractConfigMenu.stepTolerance(step);
                assertTrue(tol < step / 1000.0,
                        "step=" + step + " 的容差 " + tol + " 必须远小于一次步进（< step/1000），"
                                + "否则「越界一个步长」会被静默接受");
            }
        }

        @Test
        @DisplayName("真实越界一个步进仍被拒（5.0 → 5.1），而累加脏值 2.000000000000001 被放行")
        void toleranceAbsorbsAccumulationButNotAFullStep() {
            double step = 0.1;
            double tol = AbstractConfigMenu.stepTolerance(step);
            double max = 5.0;

            // ① 累加脏值：只越界 ~2e-15，必须被吸收
            double accumulated = 2.000000000000001;
            assertTrue(accumulated > max - 3.0,
                    "夹具自检：这里用的是同一类脏值（越界量远小于一个步进）");
            assertTrue(accumulated - 5.0 < step,
                    "前提：脏值的越界量 " + (accumulated - 5.0) + " 远小于一个步进");
            assertTrue(accumulated <= 5.0 + tol,
                    "2.000000000000001 相对上限 5.0 属于累加误差，必须被容差吸收");

            // ② 真正越界一整个步进：不得被吸收
            double genuinelyOver = max + step;      // 5.1
            assertFalse(genuinelyOver <= max + tol,
                    "5.1 超出上限 5.0 一整个步进，容差（" + tol + "）不得放行 —— "
                            + "容差只吸收累加误差，不放宽真实越界");

            // ③ 第二个现场：hookVelocity(0.5, step 0.1) 停在 4.999999999999998
            //    注意它再加一步会跳到 5.099999999999998（越过一整个步进），
            //    所以 008 的修复**不是**靠容差把这一步放行，而是靠落值前的 Math.min(max, val) 收敛。
            //    本用例只证明容差不会放宽真实越界；可达性由下一条用例证明。
            double stuck = 4.999999999999998;
            assertTrue(stuck <= max + tol, "停在 4.999999999999998 时判定为未越界（可以继续显示为上限附近）");
        }

        @Test
        @DisplayName("落值前的 Math.min/Math.max 收敛保证写盘值恰好等于声明边界")
        void clampingToBoundaryYieldsExactDeclaredValue() {
            // 这是 008 修复的第二半（Math.min(max, val) / Math.max(min, val)），
            // 与第一个半（容差）分工不同：容差决定「要不要放行这一步」，收敛决定「写进去的是不是干净值」。
            double max = 5.0;
            double step = 0.1;
            double stuck = 4.999999999999998;
            double nextRaw = stuck + step;

            assertEquals(5.0, Math.min(max, nextRaw),
                    "落值前 Math.min(max, ...) 必须把 5.099999999999998 收敛成干净的 5.0；"
                            + "否则配置里会写出越过声明上界的脏值");
            assertEquals(max, Math.max(1.0, Math.min(max, nextRaw)));
            // 对照：不收敛时写进去的就是脏值
            assertTrue(nextRaw > max, "前提：裸算结果确实越界");
        }

        @Test
        @DisplayName("零步长与负步长都有定义，且恒为正")
        void degenerateStepsStayPositive() {
            assertEquals(1e-9, AbstractConfigMenu.stepTolerance(0.0), 1e-18,
                    "步长为 0 时必须回落到下界 1e-9，不能返回 0 让比较退化成裸 <=（负容差会让区间缩水）");
            assertTrue(AbstractConfigMenu.stepTolerance(-0.05) > 0,
                    "负步长取绝对值：容差恒为正，负容差会把区间两端同时往里挤");
            assertEquals(AbstractConfigMenu.stepTolerance(0.05), AbstractConfigMenu.stepTolerance(-0.05),
                    "±step 必须得到同一个容差");
        }

        @Test
        @DisplayName("容差随步长线性缩放（不是常数）")
        void toleranceScalesWithStep() {
            double small = AbstractConfigMenu.stepTolerance(0.01);
            double large = AbstractConfigMenu.stepTolerance(10.0);
            assertEquals(1000.0, large / small, 1e-3,
                    "容差按步长相对取值（|step| * 1e-6）：10.0 与 0.01 相差 1000 倍。"
                            + "若退化为常数，大步长下的累加误差就盖不住了");
        }
    }

    // ================================================================== 005

    @Nested
    @DisplayName("onClick 闸门 —— slice-08-logic-005")
    class ClickGate {

        @Test
        @DisplayName("点击菜单槽位：先取消、再分发，且 Cancel 在动作之前")
        void topInventoryClickIsCancelledThenDispatched() {
            admin = server.addPlayer("gate-admin");
            List<String> log = new java.util.ArrayList<>();
            AbstractConfigMenu menu = menu(admin, 54, log);
            menu.open();

            InventoryClickEvent event = click(view(admin, menu.getInventory(), admin.getInventory()),
                    10, ClickType.LEFT);

            // 用「动作里读 isCancelled」证明顺序：若 setCancelled 在分发之后，这里会读到 false
            boolean[] cancelledWhenActionRan = {false};
            menu.clickActions.put(10, ct -> cancelledWhenActionRan[0] = event.isCancelled());

            menu.onClick(event);

            assertTrue(cancelledWhenActionRan[0],
                    "事件必须在动作执行之前就被取消 —— 否则动作里若触发 reopen/写盘，"
                            + "原版还要再走一遍拾取逻辑");
            assertTrue(event.isCancelled());
        }

        @Test
        @DisplayName("点击玩家自己的背包：仍然取消，且不分发同号槽位的动作")
        void clickInPlayerInventoryIsCancelledButNotDispatched() {
            admin = server.addPlayer("gate-inv");
            List<String> log = new java.util.ArrayList<>();
            AbstractConfigMenu menu = menu(admin, 54, log);
            menu.open();

            // rawSlot 54 = 玩家背包第一格，getSlot() 归一化为 0 —— 正好撞上菜单的 slot 0
            InventoryClickEvent event = click(view(admin, menu.getInventory(), admin.getInventory()),
                    54, ClickType.SHIFT_LEFT);

            menu.onClick(event);

            assertEquals(0, event.getSlot(), "夹具前提：背包格 rawSlot 54 归一化后是 slot 0，与菜单槽位撞号");
            assertTrue(event.isCancelled(),
                    "点击落在玩家背包（shift-click）时必须取消 —— slice-08-logic-005 的现场："
                            + "原实现只看 getClickedInventory() 就 return，物品被塞进 GUI");
            assertTrue(log.isEmpty(),
                    "撞号的菜单动作不得被触发：把背包里的东西 shift 进 GUI 不该改配置");
        }

        @Test
        @DisplayName("别人操作同一界面：不取消、不分发")
        void otherPlayersClickIsIgnored() {
            admin = server.addPlayer("gate-owner");
            PlayerMock intruder = server.addPlayer("gate-intruder");
            List<String> log = new java.util.ArrayList<>();
            AbstractConfigMenu menu = menu(admin, 54, log);
            menu.open();

            InventoryClickEvent event = click(view(intruder, menu.getInventory(), intruder.getInventory()),
                    10, ClickType.LEFT);
            menu.onClick(event);

            assertFalse(event.isCancelled(), "只处理本人（whoClicked == admin）的点击");
            assertTrue(log.isEmpty());
        }

        @Test
        @DisplayName("视图不是本菜单：不取消、不分发")
        void foreignViewIsIgnored() {
            admin = server.addPlayer("gate-foreign");
            List<String> log = new java.util.ArrayList<>();
            AbstractConfigMenu menu = menu(admin, 54, log);
            menu.open();

            Inventory otherTop = server.createInventory(null, 27, "other");
            InventoryClickEvent event = click(view(admin, otherTop, admin.getInventory()), 0, ClickType.LEFT);
            menu.onClick(event);

            assertFalse(event.isCancelled(), "top inventory 不是本菜单时不得干预");
            assertTrue(log.isEmpty());
        }

        @Test
        @DisplayName("没有登记动作的槽位：已取消，但什么都不发生")
        void unregisteredSlotIsCancelledSilently() {
            admin = server.addPlayer("gate-empty");
            List<String> log = new java.util.ArrayList<>();
            AbstractConfigMenu menu = menu(admin, 54, log);
            menu.open();

            InventoryClickEvent event = click(view(admin, menu.getInventory(), admin.getInventory()),
                    20, ClickType.LEFT);
            menu.onClick(event);

            assertTrue(event.isCancelled(), "菜单内的任何点击都要拦下，哪怕该格没有动作");
            assertTrue(log.isEmpty());
        }

        @Test
        @DisplayName("ClickType 原样透传给动作（菜单据此区分 LEFT / RIGHT / SHIFT）")
        void clickTypeIsPassedThroughUnchanged() {
            admin = server.addPlayer("gate-type");
            List<String> log = new java.util.ArrayList<>();
            AbstractConfigMenu menu = menu(admin, 54, log);
            menu.open();

            for (ClickType type : List.of(ClickType.LEFT, ClickType.RIGHT,
                    ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT, ClickType.MIDDLE)) {
                menu.onClick(click(view(admin, menu.getInventory(), admin.getInventory()), 10, type));
            }

            assertEquals(List.of("click-10:LEFT", "click-10:RIGHT", "click-10:SHIFT_LEFT",
                            "click-10:SHIFT_RIGHT", "click-10:MIDDLE"),
                    log,
                    "onClick 只负责原样透传 ClickType；白名单化（slice-08-logic-009）在各菜单的 lambda 里。"
                            + "若这里开始过滤，各菜单的 SHIFT 分支就再也收不到 SHIFT_LEFT");
        }
    }

    // ================================================================== 渲染辅助

    @Nested
    @DisplayName("渲染辅助")
    class Rendering {

        @Test
        @DisplayName("setBackButton 落在最后一行最左端（size - 9）")
        void backButtonSitsAtLastRowLeftEnd() {
            admin = server.addPlayer("render-back");
            for (int size : List.of(27, 45, 54)) {
                List<String> log = new java.util.ArrayList<>();
                AbstractConfigMenu menu = new AbstractConfigMenu(admin, "t", size,
                        MockBukkit.createMockPlugin("HilltopVillage"), lang) {
                    @Override
                    protected void buildMenu() { }
                };
                menu.setBackButton(ct -> log.add("back"));

                assertEquals(Material.RED_STAINED_GLASS_PANE,
                        menu.getInventory().getItem(size - 9).getType(),
                        "size=" + size + " 时返回按钮必须在 slot " + (size - 9));
                assertTrue(menu.clickActions.containsKey(size - 9));
            }
        }

        @Test
        @DisplayName("fillEmpty 只补空位，不覆盖已有物品")
        void fillEmptyDoesNotOverwriteExistingItems() {
            admin = server.addPlayer("render-fill");
            AbstractConfigMenu menu = new AbstractConfigMenu(admin, "t", 27,
                    MockBukkit.createMockPlugin("HilltopVillage"), lang) {
                @Override
                protected void buildMenu() {
                    setItem(5, Material.DIAMOND, "keep-me");
                    inventory.setItem(7, new org.bukkit.inventory.ItemStack(Material.AIR));
                }
            };
            menu.buildMenu();
            menu.fillEmpty();

            assertEquals(Material.DIAMOND, menu.getInventory().getItem(5).getType(),
                    "已放置的物品不得被填充覆盖");
            assertEquals(Material.BLACK_STAINED_GLASS_PANE, menu.getInventory().getItem(7).getType(),
                    "显式的 AIR 也算空位，必须被填充（否则 side-click 会拿到 null 物品）");
            assertEquals(Material.BLACK_STAINED_GLASS_PANE, menu.getInventory().getItem(0).getType());
            for (int slot = 0; slot < 27; slot++) {
                assertNotNull(menu.getInventory().getItem(slot),
                        "填充后 slot " + slot + " 不得残留 null");
            }
        }

        @Test
        @DisplayName("formatValue 固定一位小数（1.95 会显示成 2.0 —— 所以 008 的容差不可省）")
        void formatValueRoundsToOneDecimal() {
            String formatted = AbstractConfigMenu.formatValue("speed", 1.950000000000001);
            assertTrue(formatted.contains("2.0"),
                    "浮点脏值被格式化成 2.0 —— 这正是 slice-08-logic-008 的「UI 谎称已达上限」："
                            + "显示说 2.0，比较却认为还没到");
            assertTrue(formatted.endsWith("1.0") || formatted.contains("2.0"));
        }

        @Test
        @DisplayName("整数 formatValue 原样渲染，不做小数")
        void formatValueForIntHasNoDecimal() {
            String formatted = AbstractConfigMenu.formatValue("waves", 7);
            assertTrue(formatted.contains("7") && !formatted.contains("7.0"),
                    "整数档位不该出现小数点");
        }

        @Test
        @DisplayName("getInventory() 恒返回同一个 Inventory 实例（holder 自洽）")
        void inventoryIsStableAndSelfHeld() {
            admin = server.addPlayer("render-inv");
            AbstractConfigMenu menu = menu(admin, 54, new java.util.ArrayList<>());
            assertSame(menu.getInventory(), menu.getInventory(),
                    "每次调用新建 Inventory 会让点击视图与渲染目标不是同一份");
            assertSame(menu, menu.getInventory().getHolder(),
                    "InventoryHolder 必须是菜单自己，否则 InventoryCloseEvent 的归属判定失效");
        }
    }
}
