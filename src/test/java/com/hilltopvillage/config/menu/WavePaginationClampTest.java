package com.hilltopvillage.config.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * {@code slice-08-logic-006}（round3 R-11）—— {@code WaveSettingsMenu} 分页的
 * <b>渲染入口上界钳制</b>。
 *
 * <p><b>缺陷原貌</b>：{@code buildMenu()} 里原本只算 {@code totalPages} 却从不使用它，
 * {@code page} 也没有任何钳制。在末页删除波次（波数从 &gt; 21 掉回个位数）后，
 * {@code page} 仍是旧的大值，{@code startIdx = page * 21} 越过 {@code waveNums.size()}，
 * {@code for} 循环体一次都不执行，菜单渲染成「一页里没有任何波次，也没有 next 按钮」的空页 ——
 * 管理员只能按返回键，且无法从空页里退回第 0 页。</p>
 *
 * <p><b>为什么这个类能测它</b>：round2/round3 都判定「需 22+ 波次，且 {@code page} 是私有字段」
 * 所以做不成。实测有两条更省的路：</p>
 * <ol>
 *   <li><b>多页路径不需要造 22 个真实波次</b> —— {@code GameConfig#getWaves()} 就是一个可变
 *       {@code Map}，直接 {@code put} 21 个 {@code WaveConfig} 即可；而 {@code page} 靠点击
 *       slot 53 的 next 按钮就能推到 1，全程不碰反射。</li>
 *   <li><b>单页路径用反射设 {@code page}</b> —— 仓库里已有大量 {@code getDeclaredField}
 *       先例（{@code ArenaResetServiceTest:372}、{@code DropperAttackGoalTest:186} 等）。</li>
 * </ol>
 *
 * <p><b>判定标准是外部可观测的渲染结果</b>：断言的是 {@code inventory} 里波次行的材质与
 * {@code clickActions} 是否登记，<b>不</b>断言 {@code page} 字段本身 ——
 * 直接读字段等于复述实现，钳制写在哪一行都测不出区别喵。</p>
 */
class WavePaginationClampTest extends MockBukkitTestBase {

    /** 与生产常量同步；源码改动这里会立刻表现为「页数算错」的断言失败喵。 */
    private static final int SLOTS_PER_PAGE = 21;

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
        admin = server.addPlayer("pager-admin");
        players.add(admin);
    }

    @AfterEach
    void tearDown() {
        MenuTestSupport.clearPending(handler, players);
        if (lookup != null) {
            lookup.close();
        }
    }

    /**
     * 把出厂配置扩成 {@code count} 个波次（编号 1..count），只保留最小字段：
     * 每个波次 1 个怪种，避免夹具本身太贵喵。
     */
    private void installWaves(int count) {
        cfg.getWaves().clear();
        for (int n = 1; n <= count; n++) {
            GameConfig.WaveConfig wc = new GameConfig.WaveConfig(10, new LinkedHashMap<>());
            wc.getCompositions().put("explode-beetle", new GameConfig.MobComposition(2, 1));
            cfg.getWaves().put(n, wc);
        }
    }

    /** 只留出厂那三个波次（1 / 5 / 10），即波数掉回单页喵。 */
    private void shrinkToFactoryWaves() {
        cfg.getWaves().keySet().removeIf(n -> n != 1 && n != 5 && n != 10);
    }

    private WaveSettingsMenu openMenu() {
        WaveSettingsMenu menu = new WaveSettingsMenu(admin, cm, null, lang);
        menu.open();
        return menu;
    }

    /** 反射设 {@code page}：模拟「配置被外部改小、page 却还停在旧值」喵。 */
    private void forcePage(WaveSettingsMenu menu, int page) {
        try {
            Field field = WaveSettingsMenu.class.getDeclaredField("page");
            field.setAccessible(true);
            field.setInt(menu, page);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("反射设置 page 失败 —— 字段名或可见性变了", e);
        }
    }

    private void assertWaveRowsRendered(WaveSettingsMenu menu, String when) {
        // 钳制后回到第 0 页：编号 1 / 5 / 10 三个波次必须落在 slot 9 / 10 / 11
        List<Integer> slots = List.of(9, 10, 11);
        for (int slot : slots) {
            assertTrue(menu.clickActions.containsKey(slot),
                    when + "：slot " + slot + " 必须登记波次动作 —— 钳制缺失时 page 停在旧大值，"
                            + "startIdx 越过 waveNums.size()，for 循环体一次都不执行，"
                            + "整页只剩 fillEmpty 的黑色玻璃板（slice-08-logic-006 / round3 R-11）");
        }
        assertEquals(Material.GREEN_WOOL, menu.getInventory().getItem(9).getType(),
                when + "：slot 9 是波次 1 的图标（编号 ≤3 用 GREEN_WOOL）。若得到 "
                        + Material.BLACK_STAINED_GLASS_PANE + " 说明这一格是 fillEmpty 的占位，"
                        + "菜单已退化成空页（slice-08-logic-006 / round3 R-11）");
        assertEquals(Material.YELLOW_WOOL, menu.getInventory().getItem(10).getType(),
                when + "：slot 10 是波次 5 的图标（4-6 用 YELLOW_WOOL）");
        assertEquals(Material.ORANGE_WOOL, menu.getInventory().getItem(11).getType(),
                when + "：slot 11 是波次 10 的图标（7-10 用 ORANGE_WOOL）");
    }

    @Nested
    @DisplayName("多页 → 删波次 → 仍是旧 page（不碰反射，走真实 next 按钮）")
    class RealPaginationPath {

        @Test
        @DisplayName("末页删波次后重开不再渲染成空页，且回到第 0 页")
        void deletingWavesOnLastPageDoesNotStrandAnEmptyPage() {
            installWaves(SLOTS_PER_PAGE + 1); // 22 个波次 = 2 页

            WaveSettingsMenu menu = openMenu();
            assertTrue(menu.clickActions.containsKey(53),
                    "夹具前提：22 个波次超过 SLOTS_PER_PAGE=" + SLOTS_PER_PAGE
                            + "，第 0 页必须出现 next 按钮（slot 53）");

            // 真实点击 next → page 变成 1，渲染第 2 页
            MenuTestSupport.click(admin, menu, 53, ClickType.LEFT);
            server.getScheduler().performTicks(2);
            assertTrue(menu.clickActions.containsKey(9),
                    "夹具前提：第 2 页仍有波次 22 落在 slot 9");
            assertTrue(menu.clickActions.containsKey(45),
                    "夹具前提：page>0 时 slot 45 是上一页按钮");

            // 管理员在第 2 页把波次删到只剩 3 个，然后菜单重开（reopen 会重跑 buildMenu）
            shrinkToFactoryWaves();
            assertEquals(3, cfg.getWaves().size(), "夹具前提：只剩 3 个波次，页数掉回 1 页");
            menu.reopen();
            server.getScheduler().performTicks(2);

            assertWaveRowsRendered(menu, "末页删波次后重开");
            assertEquals(Material.BLACK_STAINED_GLASS_PANE,
                    menu.getInventory().getItem(45).getType(),
                    "钳制后回到第 0 页，slot 45 不该再渲染上一页按钮 —— 若这里仍是 ARROW，"
                            + "说明 page 还停在旧值 1（slice-08-logic-006 / round3 R-11）");
            assertFalse(menu.clickActions.containsKey(53),
                    "只剩 3 个波次没超过单页，不该再有 next 按钮");
        }

        @Test
        @DisplayName("对照半：22 个波次时第 1 页确实渲染第 2 页（钳制不得把翻页打死）")
        void clampDoesNotKillRealPagination() {
            installWaves(SLOTS_PER_PAGE + 1);
            WaveSettingsMenu menu = openMenu();

            MenuTestSupport.click(admin, menu, 53, ClickType.LEFT);
            server.getScheduler().performTicks(2);

            // 第 1 页只剩 index 21（波次 22），落在 slot 9，且是 RED_WOOL（>10）
            assertEquals(Material.RED_WOOL, menu.getInventory().getItem(9).getType(),
                    "对照半：第 1 页 slot 9 必须是波次 22 的图标 —— 若钳制写成恒 0，"
                            + "翻页会变成永远停在第 0 页（slice-08-logic-006 的过度收紧形态）");
            assertTrue(menu.clickActions.containsKey(9),
                    "对照半：第 1 页那一行必须有动作，不能只剩图标");
        }
    }

    @Nested
    @DisplayName("单页 + page 被人为设大（反射，模拟外部改配置后 page 未复位）")
    class SinglePageStaleValue {

        @Test
        @DisplayName("page=7 但只有 3 个波次：渲染入口把 page 收敛到 0，波次行照常出现")
        void stalePageIsClampedAtRenderEntry() {
            WaveSettingsMenu menu = new WaveSettingsMenu(admin, cm, null, lang);
            forcePage(menu, 7);

            menu.open();

            assertWaveRowsRendered(menu, "page=7 / 3 个波次");
            assertEquals(Material.BLACK_STAINED_GLASS_PANE,
                    menu.getInventory().getItem(45).getType(),
                    "钳制到第 0 页后不该有上一页按钮（page=7 若原样保留，slot 45 会是 ARROW）喵");
        }

        @Test
        @DisplayName("page=-3 的下界也被收住（负数不该让 startIdx 变负）")
        void negativePageIsClampedToZero() {
            WaveSettingsMenu menu = new WaveSettingsMenu(admin, cm, null, lang);
            forcePage(menu, -3);

            menu.open();

            assertWaveRowsRendered(menu, "page=-3 / 3 个波次");
        }
    }
}
