package com.hilltopvillage.config.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;

import org.bukkit.Sound;
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
import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * #8 —— <b>主菜单的「加载配置文件」必须按 {@code loadFromFile()} 的真实结果发消息</b>。
 *
 * <p><b>缺陷原貌</b>：{@code MainConfigMenu} 的 CRAFTING_TABLE 动作先调
 * {@code configManager.loadFromFile();} 丢掉结果，随后<b>无条件</b>发
 * {@code config.menu.main.reloaded-msg} 并播成功音。而 {@code loadFromFile()} 在
 * {@code game-settings.yml} 不可用（或本进程已因隔离而只读）时只打一条 SEVERE、
 * 保留内存旧值就 {@code return} —— 管理员看到「已从 game-settings.yml 重新加载」，
 * 实际什么都没重载。</p>
 *
 * <p><b>本类证明什么</b>：同一条点击链跑「成功」与「失败」两侧，并两个方向都断言。
 * 失败侧用真实 {@link ConfigManager} + 空文件（{@code YamlFiles} 的 SUSPECT 分支），
 * 与 {@code ConfigManagerTest} 的降级夹具同源；成功侧还必须证明磁盘新值真的进了内存，
 * 否则「只改消息、不改加载」的实现也能让消息断言变绿。</p>
 */
class MainConfigMenuReloadResultTest extends MockBukkitTestBase {

    /** {@code bottomRow = 45}，reload 按钮在 {@code bottomRow + 4}。 */
    private static final int RELOAD_SLOT = 49;

    private BareHost host;
    private MockedStatic<JavaPlugin> lookup;
    private LanguageManager lang;
    private PlayerMock admin;

    @BeforeEach
    void setUp() {
        host = loadBareHost();
        lookup = MenuTestSupport.pluginLookup(host);
        // 原样回传 key：成功文案与失败文案必须可区分，否则断言「播了哪一条」形同虚设。
        lang = mock(LanguageManager.class);
        when(lang.getFor(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(lang.getFor(any(), anyString(), any(Object[].class))).thenAnswer(inv -> inv.getArgument(1));
        admin = server.addPlayer("reload-result-admin");
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

    private MainConfigMenu openMainMenu(ConfigManager config) {
        MainConfigMenu menu = new MainConfigMenu(admin, config, lang);
        menu.open();
        assertTrue(menu.clickActions.containsKey(RELOAD_SLOT),
                "夹具前提：slot " + RELOAD_SLOT + " 必须是「加载配置文件」按钮。"
                        + " 若菜单布局变了，本用例要在这里先红，而不是在后面假绿");
        return menu;
    }

    private void drainSounds() {
        admin.getHeardSounds().clear();
    }

    @Test
    @DisplayName("★ loadFromFile() 失败 → 发 reload-failed + 失败音，绝不发 reloaded-msg")
    void failedReloadReportsFailureInsteadOfSuccess() throws Exception {
        ConfigManager config = new ConfigManager(host);
        // 内存里先放一个与出厂值不同的值：失败时它必须原样保留，不能被重建/覆盖。
        config.getActiveConfig().setVictoryWaves(7);

        // 构造之后再破坏文件：空文件会让 YamlFiles 判 SUSPECT，settings 闸门锁定只读。
        MockBukkitTestBase.TestPaths.write(settingsFile(host), "   \n");

        MainConfigMenu menu = openMainMenu(config);
        drainSounds();

        MenuTestSupport.click(admin, menu, RELOAD_SLOT, ClickType.LEFT);
        server.getScheduler().performTicks(2);

        assertEquals("config.menu.main.reload-failed", admin.nextMessage(),
                "★ game-settings.yml 不可用时必须发 reload-failed（而不是 reloaded-msg）。"
                        + " 缺陷版在这里发的是 reloaded-msg —— 管理员会以为磁盘值已经生效");
        assertEquals(null, admin.nextMessage(),
                "失败时不得再发任何「成功」尾巴；成功路径只应有一条 reloaded-msg");

        assertEquals(1, admin.getHeardSounds().size(),
                "失败时必须恰好播一次音效（0 次 = 静默，2 次 = 成功/失败音叠加）");
        assertEquals(Sound.BLOCK_ANVIL_LAND.getKey().getKey(),
                admin.getHeardSounds().get(0).getSound(),
                "失败音必须是铁砧落地；缺陷版播的是 BLOCK_NOTE_BLOCK_PLING（成功音）");

        assertEquals(7, config.getActiveConfig().getVictoryWaves(),
                "★ 失败时内存值必须保持不变（缺陷定义里的「内存里还是旧值」）");
        assertTrue(config.getSettingsGate().isReadOnly(),
                "前置/结果校验：空文件必须让 settings 闸门锁定只读（否则失败分支根本没走到）");
    }

    @Test
    @DisplayName("反向对照：loadFromFile() 成功 → 仍发 reloaded-msg + 成功音，且磁盘新值真的进内存")
    void successfulReloadKeepsTheOriginalFeedback() throws Exception {
        ConfigManager config = new ConfigManager(host);

        // 在构造之后改磁盘：只有真的重新读盘，内存才会变成 12。
        File settings = settingsFile(host);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(settings));
        yaml.set("victory-waves", 12);
        MockBukkitTestBase.TestPaths.write(settings, yaml.saveToString());

        MainConfigMenu menu = openMainMenu(config);
        drainSounds();

        MenuTestSupport.click(admin, menu, RELOAD_SLOT, ClickType.LEFT);
        server.getScheduler().performTicks(2);

        assertEquals("config.menu.main.reloaded-msg", admin.nextMessage(),
                "成功侧必须原样保留 reloaded-msg —— 一个「永远播失败」的实现会在这一条红");
        assertEquals(null, admin.nextMessage(), "成功路径不应额外发失败消息");
        assertEquals(12, config.getActiveConfig().getVictoryWaves(),
                "★ 成功侧必须真的把磁盘上的 12 读进内存，否则本用例只是验证了一句文案");

        assertEquals(1, admin.getHeardSounds().size(), "成功时恰好一次成功音效");
        assertEquals(Sound.BLOCK_NOTE_BLOCK_PLING.getKey().getKey(),
                admin.getHeardSounds().get(0).getSound(),
                "成功音必须仍是 PLING；实际：" + admin.getHeardSounds().get(0).getSound());
        assertNotNull(menu.getInventory().getItem(RELOAD_SLOT),
                "reopen() 后 reload 槽位仍应有物品");
    }
}
