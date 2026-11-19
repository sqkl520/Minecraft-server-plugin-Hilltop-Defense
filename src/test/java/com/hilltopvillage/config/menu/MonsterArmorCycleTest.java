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
 * S03-F2 —— 怪物属性菜单的四件护甲各自一套循环表。
 *
 * <p><b>缺陷原貌</b>：{@code MonsterSettingsMenu#addArmorSlot} 被四件护甲各调用一次，
 * 但每次都在 {@code cycleMaterial(current, ARMOR_CYCLE)} 里用同一份
 * <b>「全头盔」</b>数组。于是左键点「胸甲」槽会把 {@code LEATHER_HELMET} 写进
 * {@code chestplate} 字段；而管理员通过 SHIFT 输入或手改 {@code monsters-config.yml}
 * 配好的真实胸甲（如 {@code IRON_CHESTPLATE}）不在该表内，
 * {@code cycleMaterial} 的「不在表里就回落到第一项」兜底会让<b>第一次左键就把它覆盖掉</b>，
 * 且覆盖后的值（表内头盔）再也循环不回原值 —— 不可逆的数据破坏。
 * {@code WaveSettingsMenu.MonsterPropMenu} 里另有一份同样的「全头盔」数组 {@code ARMOR_LIST}。</p>
 *
 * <p><b>修复口径</b>：四张表（头盔/胸甲/护腿/靴子）+ 「表外值拒绝改动并提示」。
 * 本类两个方向都断言：既证明四个槽位各走自己的表，也证明表外值<b>没有被静默换掉</b>。</p>
 */
class MonsterArmorCycleTest extends MockBukkitTestBase {

    /** 四件护甲在 {@code MonsterSettingsMenu} 里的槽位。 */
    private static final int HELMET_SLOT = 37;
    private static final int CHESTPLATE_SLOT = 38;
    private static final int LEGGINGS_SLOT = 39;
    private static final int BOOTS_SLOT = 40;

    /** 一个「合法装备但不是本槽位预设」的材质：龟壳头盔，不在任何一张护甲表里。 */
    private static final Material OUTSIDE_CYCLE_HELMET = Material.TURTLE_HELMET;

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
        admin = server.addPlayer("armor-admin");
        players.add(admin);
    }

    @AfterEach
    void tearDown() {
        MenuTestSupport.clearPending(handler, players);
        if (lookup != null) {
            lookup.close();
        }
    }

    private GameConfig.MonsterConfig beetle() {
        GameConfig.MonsterConfig mc = cfg.getMonsters().get("explode-beetle");
        assertNotNull(mc, "出厂配置必须包含 explode-beetle，否则本类什么都没测到");
        return mc;
    }

    private MonsterSettingsMenu monsterMenu() {
        MonsterSettingsMenu menu = new MonsterSettingsMenu(admin, cm, null, lang);
        menu.open();
        return menu;
    }

    /** 循环一格的便捷封装：点一次左键并让 reopen 的调度器跑完。 */
    private void leftClick(AbstractConfigMenu menu, int slot) {
        MenuTestSupport.click(admin, menu, slot, ClickType.LEFT);
        server.getScheduler().performTicks(2);
    }

    // ================================================================ 各槽位各走自己的表

    @Nested
    @DisplayName("四个槽位各自循环 —— 不再把头盔写进胸甲")
    class EachSlotHasItsOwnCycle {

        @Test
        @DisplayName("胸甲槽：LEATHER_CHESTPLATE → CHAINMAIL_CHESTPLATE（缺陷版会写 LEATHER_HELMET）")
        void chestplateCyclesWithinChestplates() {
            beetle().setChestplate(Material.LEATHER_CHESTPLATE);
            MonsterSettingsMenu menu = monsterMenu();

            leftClick(menu, CHESTPLATE_SLOT);

            assertEquals(Material.CHAINMAIL_CHESTPLATE, beetle().getChestplate(),
                    "S03-F2：胸甲槽必须走胸甲表。缺陷版下这里是 LEATHER_HELMET —— "
                            + "装备部位被静默换成头盔，且 WaveManager.applyMonsterEquipment 会照此穿戴");
        }

        @Test
        @DisplayName("头盔槽：LEATHER_HELMET → CHAINMAIL_HELMET")
        void helmetCyclesWithinHelmets() {
            beetle().setHelmet(Material.LEATHER_HELMET);
            MonsterSettingsMenu menu = monsterMenu();

            leftClick(menu, HELMET_SLOT);

            assertEquals(Material.CHAINMAIL_HELMET, beetle().getHelmet());
        }

        @Test
        @DisplayName("护腿槽：LEATHER_LEGGINGS → CHAINMAIL_LEGGINGS")
        void leggingsCyclesWithinLeggings() {
            beetle().setLeggings(Material.LEATHER_LEGGINGS);
            MonsterSettingsMenu menu = monsterMenu();

            leftClick(menu, LEGGINGS_SLOT);

            assertEquals(Material.CHAINMAIL_LEGGINGS, beetle().getLeggings());
        }

        @Test
        @DisplayName("靴子槽：LEATHER_BOOTS → CHAINMAIL_BOOTS")
        void bootsCycleWithinBoots() {
            beetle().setBoots(Material.LEATHER_BOOTS);
            MonsterSettingsMenu menu = monsterMenu();

            leftClick(menu, BOOTS_SLOT);

            assertEquals(Material.CHAINMAIL_BOOTS, beetle().getBoots());
        }

        @Test
        @DisplayName("空槽左键落到本槽位的第 1 档（胸甲 → LEATHER_CHESTPLATE，不是头盔）")
        void nullStartsAtFirstTierOfThatSlot() {
            beetle().setChestplate(null);
            MonsterSettingsMenu menu = monsterMenu();

            leftClick(menu, CHESTPLATE_SLOT);

            assertEquals(Material.LEATHER_CHESTPLATE, beetle().getChestplate(),
                    "null（未配置）时从左键开始配装 —— 起点必须是本槽位的第 1 档");
        }

        @Test
        @DisplayName("AIR 视同空槽，同样落到本槽位第 1 档")
        void airIsTreatedAsEmpty() {
            beetle().setBoots(Material.AIR);
            MonsterSettingsMenu menu = monsterMenu();

            leftClick(menu, BOOTS_SLOT);

            assertEquals(Material.LEATHER_BOOTS, beetle().getBoots());
        }

        @Test
        @DisplayName("四张表的档位顺序完全一致（点同样次数得到同一档位的对应部位）")
        void allFourCyclesShareTheSameTierOrder() {
            beetle().setHelmet(null);
            beetle().setChestplate(null);
            beetle().setLeggings(null);
            beetle().setBoots(null);
            MonsterSettingsMenu menu = monsterMenu();

            List<String> helmetTiers = new ArrayList<>();
            List<String> chestTiers = new ArrayList<>();
            List<String> legTiers = new ArrayList<>();
            List<String> bootTiers = new ArrayList<>();

            // 空槽第一次左键就是第 1 档，共 7 档（第 7 档是 AIR = 空手）
            for (int i = 0; i < 7; i++) {
                leftClick(menu, HELMET_SLOT);
                leftClick(menu, CHESTPLATE_SLOT);
                leftClick(menu, LEGGINGS_SLOT);
                leftClick(menu, BOOTS_SLOT);
                helmetTiers.add(beetle().getHelmet() == null ? "AIR" : beetle().getHelmet().name());
                chestTiers.add(beetle().getChestplate() == null ? "AIR" : beetle().getChestplate().name());
                legTiers.add(beetle().getLeggings() == null ? "AIR" : beetle().getLeggings().name());
                bootTiers.add(beetle().getBoots() == null ? "AIR" : beetle().getBoots().name());
            }

            assertEquals(List.of("LEATHER_HELMET", "CHAINMAIL_HELMET", "IRON_HELMET",
                            "DIAMOND_HELMET", "NETHERITE_HELMET", "GOLDEN_HELMET", "AIR"),
                    helmetTiers, "头盔表档位顺序是跨槽位一致性的基准");
            assertEquals(helmetTiers.stream().map(t -> t.replace("HELMET", "CHESTPLATE")).toList(),
                    chestTiers, "胸甲必须与头盔同档位顺序（否则四个槽位的 UX 不一致）");
            assertEquals(helmetTiers.stream().map(t -> t.replace("HELMET", "LEGGINGS")).toList(),
                    legTiers, "护腿必须与头盔同档位顺序");
            assertEquals(helmetTiers.stream().map(t -> t.replace("HELMET", "BOOTS")).toList(),
                    bootTiers, "靴子必须与头盔同档位顺序");
        }

        @Test
        @DisplayName("未在表内但合法的真实装备（IRON_CHESTPLATE 经 SHIFT 输入）仍能按胸甲表继续循环")
        void configuredRealChestplateKeepsCycling() {
            // IRON_CHESTPLATE 确实在胸甲表里（第 3 档）→ 左键应前进到第 4 档
            beetle().setChestplate(Material.IRON_CHESTPLATE);
            MonsterSettingsMenu menu = monsterMenu();

            leftClick(menu, CHESTPLATE_SLOT);

            assertEquals(Material.DIAMOND_CHESTPLATE, beetle().getChestplate(),
                    "管理员配好的真实胸甲必须能在胸甲表里正常前进，而不是被当成未知值");
        }
    }

    // ================================================================ 表外值不得被静默覆盖

    @Nested
    @DisplayName("表外值：不改配置，只提示（不可逆的静默覆盖被堵住）")
    class OutsideCycleIsNotOverwritten {

        @Test
        @DisplayName("头盔是 TURTLE_HELMET（表外合法装备）时左键不动它，并发提示")
        void turtleHelmetSurvivesLeftClick() {
            beetle().setHelmet(OUTSIDE_CYCLE_HELMET);
            MonsterSettingsMenu menu = monsterMenu();
            admin.nextMessage(); // 清空提示队列，确保下面断言的是本次点击产生的消息

            leftClick(menu, HELMET_SLOT);

            assertEquals(OUTSIDE_CYCLE_HELMET, beetle().getHelmet(),
                    "S03-F2：表外值若被「回落到第 1 档」覆盖，管理员手配的龟壳头盔就永久丢了 —— "
                            + "因为覆盖后的表内值再也循环不回它");
            assertNotNull(admin.nextMessage(),
                    "拒绝改动时必须说明原因，否则管理员只会觉得「点了没反应」");
        }

        @Test
        @DisplayName("胸甲槽里放了头盔（缺陷版的产物）也不会被继续破坏")
        void helmetInChestplateSlotIsLeftAlone() {
            beetle().setChestplate(Material.IRON_HELMET);
            MonsterSettingsMenu menu = monsterMenu();

            leftClick(menu, CHESTPLATE_SLOT);

            assertEquals(Material.IRON_HELMET, beetle().getChestplate(),
                    "槽位与材质不匹配说明配置被别处改过；本菜单不替管理员「猜」他想换成哪件");
        }

        @Test
        @DisplayName("SHIFT 仍可输入任意物品材质（拒绝循环不吃掉自定义输入的能力）")
        void shiftStillAcceptsAnyMaterial() {
            beetle().setChestplate(Material.LEATHER_CHESTPLATE);
            MonsterSettingsMenu menu = monsterMenu();

            MenuTestSupport.click(admin, menu, CHESTPLATE_SLOT, ClickType.SHIFT_LEFT);
            MenuTestSupport.say(server, handler, admin, OUTSIDE_CYCLE_HELMET.name());

            assertEquals(OUTSIDE_CYCLE_HELMET, beetle().getChestplate(),
                    "表外值只能靠 SHIFT 写入 —— 这条路径必须保持可用，否则「拒绝循环」会变成「配不了」");
        }

        @Test
        @DisplayName("清空路径仍可用：SHIFT 输入空串把护甲清成 null")
        void shiftBlankClearsArmor() {
            beetle().setChestplate(Material.DIAMOND_CHESTPLATE);
            MonsterSettingsMenu menu = monsterMenu();

            MenuTestSupport.click(admin, menu, CHESTPLATE_SLOT, ClickType.SHIFT_LEFT);
            MenuTestSupport.say(server, handler, admin, "");

            assertNull(beetle().getChestplate());
        }
    }

    // ================================================================ 波次菜单里的同一份副本

    @Nested
    @DisplayName("WaveSettingsMenu.MonsterPropMenu 的第二份「全头盔」表同版修复")
    class PropMenuArmorCycle {

        private WaveSettingsMenu.MonsterPropMenu propMenu(String monsterKey) {
            WaveSettingsMenu waveMenu = new WaveSettingsMenu(admin, cm, null, lang);
            WaveSettingsMenu.WaveDetailMenu parent =
                    new WaveSettingsMenu.WaveDetailMenu(admin, cm, 1, waveMenu, lang);
            WaveSettingsMenu.MonsterPropMenu menu =
                    new WaveSettingsMenu.MonsterPropMenu(admin, cm, monsterKey, parent, lang);
            menu.open();
            return menu;
        }

        @Test
        @DisplayName("胸甲槽走胸甲表（缺陷版写的是 LEATHER_HELMET）")
        void propMenuChestplateUsesChestplateList() {
            beetle().setChestplate(Material.LEATHER_CHESTPLATE);
            WaveSettingsMenu.MonsterPropMenu menu = propMenu("explode-beetle");

            leftClick(menu, CHESTPLATE_SLOT);

            assertEquals(Material.IRON_CHESTPLATE, beetle().getChestplate(),
                    "该页历来不含 CHAINMAIL 档，皮革之后是铁；关键是不能变成头盔");
        }

        @Test
        @DisplayName("表外值同样只提示不改（该页没有 SHIFT 输入，更不能覆盖）")
        void propMenuDoesNotOverwriteOutsideValue() {
            // 必须先落值再打开菜单：buildMenu 时的 current 被 lambda 捕获，
            // 打开之后再改配置与界面显示不一致（那不是本用例要测的东西）。
            beetle().setChestplate(OUTSIDE_CYCLE_HELMET);
            WaveSettingsMenu.MonsterPropMenu menu = propMenu("explode-beetle");
            admin.nextMessage();

            leftClick(menu, CHESTPLATE_SLOT);

            assertEquals(OUTSIDE_CYCLE_HELMET, beetle().getChestplate(),
                    "该页没有 SHIFT 输入，覆盖等于彻底丢失该配置");
            assertNotNull(admin.nextMessage(), "同样必须给出提示");
        }

        @Test
        @DisplayName("该页的四件护甲也各走自己的表")
        void propMenuAllFourSlotsUseOwnLists() {
            beetle().setHelmet(Material.LEATHER_HELMET);
            beetle().setLeggings(Material.LEATHER_LEGGINGS);
            beetle().setBoots(Material.LEATHER_BOOTS);
            WaveSettingsMenu.MonsterPropMenu menu = propMenu("explode-beetle");

            leftClick(menu, HELMET_SLOT);
            leftClick(menu, LEGGINGS_SLOT);
            leftClick(menu, BOOTS_SLOT);

            assertEquals(Material.IRON_HELMET, beetle().getHelmet());
            assertEquals(Material.IRON_LEGGINGS, beetle().getLeggings());
            assertEquals(Material.IRON_BOOTS, beetle().getBoots());
            assertTrue(beetle().getHelmet().name().endsWith("_HELMET"));
        }
    }
}
