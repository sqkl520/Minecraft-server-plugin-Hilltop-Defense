package com.hilltopvillage.config.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.LinkedHashMap;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * F-3：把「<b>菜单半边</b>」与「<b>磁盘半边</b>」接在同一条用例里。
 *
 * <p><b>为什么需要它（对账结论）</b>：round3 之前，两条半边各自有测试，但从没人在
 * <b>同一条用例</b>里把它们接起来喵：</p>
 *
 * <ul>
 *   <li><b>菜单半边</b>：{@code WaveMenusLeftWhitelistTest#shiftClicksStillDeleteTheWave}
 *       验证 {@code SHIFT_LEFT → verify(cm).removeWave(1) + reopen()}，但那里的
 *       {@code ConfigManager} 是 {@code MenuTestSupport.config(cfg)} 造出来的 <b>mock</b> ——
 *       它能证明「菜单点了会调 removeWave」，却证明不了「removeWave 之后磁盘真的干净」；</li>
 *   <li><b>磁盘半边</b>：{@code ConfigManagerTest#removeWaveTombstonesTheKeyOnDisk} 验证
 *       「{@code removeWave(42)} + {@code saveToFile()} → 磁盘上 {@code waves.42} 消失」，
 *       但它是<b>直接调 API</b>，从不经过菜单。</li>
 * </ul>
 *
 * <p>本类补的正是这条缝：用 <b>真实</b> {@link ConfigManager}（不是
 * {@code MenuTestSupport.config(cfg)} 的 mock）+ 真实 {@link WaveSettingsMenu}，
 * 走完整的「点 {@code SHIFT_LEFT} → 真实 {@code removeWave} → 真实 {@code saveToFile} →
 * 磁盘 → <b>新开一个 ConfigManager</b> 重新载入」。只有这条链全绿，
 * 「管理员在 GUI 删掉的波次，重启后不会复活」才算真的被守住喵。</p>
 *
 * <p><b>为什么不测出厂波次（1 / 5 / 10）</b>：那三个编号会被 {@code GameConfig} 的构造器
 * 无条件预置，删除后<b>重新载入必然复活</b> —— 这是一条<b>真实缺陷</b>（见
 * {@code round4-04-r2-f3.md} 的 F3-D1），本轮<b>只测不改</b>，故本类刻意绕开出厂编号，
 * 用「管理员自建的非预置波次」来验证墓碑链本身是正确的喵。</p>
 */
class WaveDeleteEndToEndTest extends MockBukkitTestBase {

    /** 非预置的波次号：{@code GameConfig} 构造器只预置 1 / 5 / 10。 */
    private static final int ADMIN_WAVE = 42;
    private static final int ADMIN_WAVE_TOTAL_MOBS = 77;

    private BareHost host;
    private MockedStatic<JavaPlugin> lookup;
    private LanguageManager lang;
    private ConfigManager cm;
    private PlayerMock admin;

    @BeforeEach
    void setUp() {
        host = loadBareHost();
        lookup = MenuTestSupport.pluginLookup(host);
        lang = MenuTestSupport.lang();
        // 关键：真实 ConfigManager，不是 MenuTestSupport.config(cfg) 的 mock。
        cm = new ConfigManager(host);
        admin = server.addPlayer("wave-delete-admin");
    }

    @AfterEach
    void tearDown() {
        if (lookup != null) {
            lookup.close();
        }
    }

    private static File settingsFile(BareHost host) {
        return new File(host.getDataFolder(), "game-settings.yml");
    }

    /** 直接读磁盘上 {@code waves} 段；返回 null 表示整段不存在。 */
    private static ConfigurationSection diskWaves(BareHost host) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(settingsFile(host));
        return yaml.getConfigurationSection("waves");
    }

    /**
     * 造一个「管理员经 GUI 新建」的波次：直接写进内存 + 落盘。
     *
     * <p>用 {@code saveToFile()} 而不是手写 YAML，是因为这条用例要验证的正是
     * 「本插件自己写出去的东西能被自己读回来」喵。</p>
     */
    private void seedAdminWave() {
        cm.getActiveConfig().getWaves().put(ADMIN_WAVE,
                new GameConfig.WaveConfig(ADMIN_WAVE_TOTAL_MOBS, new LinkedHashMap<>()));
        assertTrue(cm.saveToFile(), "前置条件：种子波次必须能落盘");
    }

    /**
     * 波次 {@code ADMIN_WAVE} 在列表里的可见槽位。
     *
     * <p>{@code WaveSettingsMenu#buildMenu} 把波次号排序后从 slot 9 起逐行渲染
     * （{@code SLOTS_PER_PAGE = 21}）。本类只放行 4 个波次：[1, 5, 10, 42]，
     * 42 是 index 3 → slot 12 喵。</p>
     *
     * <p>把这个布局假设集中在一个方法里，并让调用方用「删前 42 在 / 删后 42 不在且
     * 5 与 10 都在」双向钉住 —— 布局一变，断言会先红，不会静默点错行喵。</p>
     */
    private static final int WAVE_42_ROW_SLOT = 12;

    private WaveSettingsMenu openMenu() {
        WaveSettingsMenu menu = new WaveSettingsMenu(admin, cm, null, lang);
        menu.open();
        return menu;
    }

    @Test
    @DisplayName("菜单 SHIFT_LEFT + 真实 ConfigManager：磁盘上的波次键真的消失")
    void shiftLeftWithRealConfigManagerTombstonesTheKeyOnDisk() throws Exception {
        seedAdminWave();
        ConfigurationSection before = diskWaves(host);
        assertNotNull(before, "前置条件：磁盘上应有 waves 段");
        assertNotNull(before.getConfigurationSection(String.valueOf(ADMIN_WAVE)),
                "前置条件：种子波次 waves.42 应已落盘，实际键：" + before.getKeys(false));

        WaveSettingsMenu menu = openMenu();
        assertTrue(menu.clickActions.containsKey(WAVE_42_ROW_SLOT),
                "夹具前提：slot " + WAVE_42_ROW_SLOT + " 应登记波次 42 的点击动作。"
                        + " 若波次列表布局变了，本用例会在这里先红，而不是在后面假绿");

        MenuTestSupport.click(admin, menu, WAVE_42_ROW_SLOT, ClickType.SHIFT_LEFT);
        server.getScheduler().performTicks(2);

        assertFalse(cm.getActiveConfig().getWaves().containsKey(ADMIN_WAVE),
                "SHIFT_LEFT 必须真的把波次从内存里删掉（removeWave 的第一半）");
        assertTrue(cm.hasUnsavedChanges(),
                "★ 删除后必须让「未保存」亮起 —— 若 hasUnsavedChanges() 仍是 false，"
                        + "主菜单的保存按钮（MainConfigMenu 的 LIME_WOOL）不会出现，"
                        + "removedWaveKeys 墓碑<b>永远不会落盘</b>，波次重启后照样复活。"
                        + "这条是「菜单半边」与「磁盘半边」之间唯一的开关喵");

        assertTrue(cm.saveToFile(), "保存应成功");

        ConfigurationSection after = diskWaves(host);
        assertNotNull(after, "保存后 waves 段应仍在（只删被记账的那一个键，不整段清空）");
        assertEquals(null, after.getConfigurationSection(String.valueOf(ADMIN_WAVE)),
                "★ 磁盘上的 waves.42 必须消失。这是 P2-33 墓碑的全链验证："
                        + "菜单 → 真实 removeWave → removedWaveKeys → saveToFile → 磁盘");
        assertNotNull(after.getConfigurationSection("1"),
                "墓碑只能清掉删过的键；出厂波次 1 必须原样留在磁盘上");
    }

    @Test
    @DisplayName("删掉的非预置波次：新开一个 ConfigManager 重新载入也不复活")
    void deletedAdminWaveStaysGoneAcrossANewConfigManager() throws Exception {
        seedAdminWave();

        WaveSettingsMenu menu = openMenu();
        assertTrue(menu.clickActions.containsKey(WAVE_42_ROW_SLOT),
                "夹具前提：slot " + WAVE_42_ROW_SLOT + " 是波次 42");

        MenuTestSupport.click(admin, menu, WAVE_42_ROW_SLOT, ClickType.SHIFT_LEFT);
        server.getScheduler().performTicks(2);
        assertTrue(cm.saveToFile(), "删除后保存应成功");

        // 「重启」等价物：新开一个 ConfigManager，它会在构造期重新读盘
        ConfigManager reloaded = new ConfigManager(host);

        assertFalse(reloaded.getActiveConfig().getWaves().containsKey(ADMIN_WAVE),
                "★ 重启后波次 42 不得复活。复活意味着磁盘上的键没被清干净"
                        + "（saveToFile 对内存中存在的键逐个写值、从不整段清空），"
                        + "而 GameConfig 构造器只预置 1/5/10，42 不在预置集里 —— 它若出现，"
                        + "只剩「从磁盘读回来」这一条路径");
        assertTrue(reloaded.getActiveConfig().getWaves().containsKey(1),
                "对照组：出厂波次 1 必须仍在（否则「42 不在」可能只是整份配置读失败）");
    }

    @Test
    @DisplayName("反向对照：未经菜单的 removeWave 也走同一墓碑链（证明上一条的绿不是菜单巧合）")
    void directRemoveWaveUsesTheSameTombstoneChain() throws Exception {
        seedAdminWave();

        cm.removeWave(ADMIN_WAVE);
        assertTrue(cm.hasUnsavedChanges(), "直接 removeWave 同样要让未保存亮起");
        assertTrue(cm.saveToFile(), "保存应成功");

        assertEquals(null, diskWaves(host).getConfigurationSection(String.valueOf(ADMIN_WAVE)),
                "直接 API 删除也必须清掉磁盘键 —— 与菜单路径共用同一条 removedWaveKeys 链");
    }
}
