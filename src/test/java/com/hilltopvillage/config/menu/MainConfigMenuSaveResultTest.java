package com.hilltopvillage.config.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;

import org.bukkit.Sound;
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
 * round5 N1 —— <b>主菜单的「保存并应用配置」必须消费 {@code ConfigManager#saveToFile()} 的返回值</b>。
 *
 * <p><b>缺陷原貌</b>：{@code MainConfigMenu} 的 LIME_WOOL 动作直接调
 * {@code configManager.saveToFile();} 把返回值丢掉，随后<b>无条件</b>播「所有配置已保存」
 * 与一句「新配置将在下一局/重载后生效」，还放成功音效。{@code saveToFile()} 的
 * {@code false} 涵盖三条静默失败路径：settings 闸门只读、settings 的 IO 失败、
 * settings 写成功但 monsters-config.yml 写失败。掉返回值 ⇒ 管理员在磁盘完全没变的情况下
 * 被告知保存成功 —— 重启后设置消失且他没有任何线索。</p>
 *
 * <p><b>本类证明什么</b>：同一条点击链分别跑「成功」与「失败」两侧，并<b>两个方向都断言</b> —
 * 只断言失败侧的话，一个「永远播失败」的实现同样会绿；只断言成功侧的话，被测的缺陷根本测不到喵。</p>
 *
 * <p><b>为什么要真实 {@link ConfigManager}</b>：{@code MenuTestSupport.config(cfg)} 造的是 mock，
 * 它的 {@code saveToFile()} 恒返回 {@code false}（Mockito 布尔默认值），成功侧永远进不去。
 * 因此这里和 {@code WaveDeleteEndToEndTest} 一样用真实 {@code ConfigManager}，
 * 失败态用「把 monsters-config.yml 打成 0 字节 + 闸门重读」制造（与
 * {@code ConfigManagerTest#saveToFileDoesNotAdvanceSnapshotWhenMonstersSaveFails} 同一套夹具）。</p>
 */
class MainConfigMenuSaveResultTest extends MockBukkitTestBase {

    /** 当 {@code hasUnsavedChanges()} 为真时主菜单渲染保存按钮的槽位（{@code bottomRow = 45}）。 */
    private static final int SAVE_SLOT = 45;

    private BareHost host;
    private MockedStatic<JavaPlugin> lookup;
    private LanguageManager lang;
    private PlayerMock admin;

    @BeforeEach
    void setUp() {
        host = loadBareHost();
        lookup = MenuTestSupport.pluginLookup(host);
        // 关键：语言桩「原样回传 key」，而不是 MenuTestSupport.lang() 的固定 "k" ——
        // 成功文案与失败文案必须可区分，否则断言「播了哪一条」在两者都是 "k" 时形同虚设。
        lang = mock(LanguageManager.class);
        when(lang.getFor(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(lang.getFor(any(), anyString(), any(Object[].class))).thenAnswer(inv -> inv.getArgument(1));
        admin = server.addPlayer("save-result-admin");
    }

    @AfterEach
    void tearDown() {
        if (lookup != null) {
            lookup.close();
        }
    }

    private static File monstersFile(BareHost host) {
        return new File(host.getDataFolder(), "monsters-config.yml");
    }

    /** 把 monsters 闸门打成只读（真实失败态），返回「已被判只读」的前提已断言过的 ConfigManager。 */
    private ConfigManager configWithUnwritableMonsters() throws Exception {
        ConfigManager config = new ConfigManager(host);
        MockBukkitTestBase.TestPaths.write(monstersFile(host), "");
        config.getMonstersGate().reset();
        assertFalse(config.getMonstersGate().load().usable(),
                "前置条件：0 字节的 monsters-config.yml 必须被判不可用");
        assertTrue(config.getMonstersGate().isReadOnly(),
                "前置条件：闸门必须已锁定为只读，否则 saveMonstersConfig 会照常成功");
        return config;
    }

    private MainConfigMenu openMainMenu(ConfigManager config) {
        MainConfigMenu menu = new MainConfigMenu(admin, config, lang);
        menu.open();
        assertTrue(menu.clickActions.containsKey(SAVE_SLOT),
                "夹具前提：有未保存改动时 slot " + SAVE_SLOT + " 必须是保存按钮。"
                        + " 若菜单布局变了，本用例要在这里先红，而不是在后面假绿");
        return menu;
    }

    private void drainSounds() {
        admin.getHeardSounds().clear();
    }

    @Test
    @DisplayName("★ saveToFile() 返回 false → 播 save-failed + 失败音效，绝不播 saved-msg / 成功音")
    void failedSaveReportsFailureInsteadOfSuccess() throws Exception {
        ConfigManager config = configWithUnwritableMonsters();
        config.getActiveConfig().setVictoryWaves(7);
        assertTrue(config.hasUnsavedChanges(), "前置条件：改过配置后应有未保存改动");

        MainConfigMenu menu = openMainMenu(config);
        drainSounds();

        MenuTestSupport.click(admin, menu, SAVE_SLOT, ClickType.LEFT);
        server.getScheduler().performTicks(2);

        assertEquals("config.menu.main.save-failed", admin.nextMessage(),
                "★ 写盘失败时必须播 save-failed（而不是 saved-msg）。"
                        + " 缺陷版在这里播的是 saved-msg —— 管理员会以为已经落盘，重启后设置消失且零线索");
        assertEquals(null, admin.nextMessage(),
                "失败时不得再发第二条「新配置将在下一局/重载后生效」—— 那句只对成功成立");

        assertEquals(1, admin.getHeardSounds().size(),
                "失败时必须恰好播一次音效（0 次 = 静默，2 次 = 成功/失败音叠加）");
        assertEquals(Sound.BLOCK_ANVIL_LAND.getKey().getKey(),
                admin.getHeardSounds().get(0).getSound(),
                "失败音必须是铁砧落地；缺陷版播的是 BLOCK_NOTE_BLOCK_PLING（成功音），"
                        + " 玩家听到的就是「保存成功了」。实际：" + admin.getHeardSounds().get(0).getSound());

        assertTrue(config.hasUnsavedChanges(),
                "★ 失败后脏标志必须仍为真（快照未推进），否则保存按钮熄灭、这批改动永远不再重试");
        assertTrue(menu.clickActions.containsKey(SAVE_SLOT),
                "失败后 reopen() 重建的菜单里保存按钮必须仍然可见 —— 管理员修好文件后才能原地重试");
    }

    @Test
    @DisplayName("反向对照：saveToFile() 返回 true → 仍播 saved-msg + saved-hint + 成功音（修复不得吃掉成功路径）")
    void successfulSaveKeepsTheOriginalFeedback() throws Exception {
        ConfigManager config = new ConfigManager(host);
        config.getActiveConfig().setVictoryWaves(7);

        MainConfigMenu menu = openMainMenu(config);
        drainSounds();

        MenuTestSupport.click(admin, menu, SAVE_SLOT, ClickType.LEFT);
        server.getScheduler().performTicks(2);

        assertEquals("config.menu.main.saved-msg", admin.nextMessage(),
                "成功侧必须原样保留 saved-msg —— 一个「永远播失败」的实现会在这一条红");
        assertEquals("config.menu.main.saved-hint", admin.nextMessage(),
                "成功侧的第二条提示（生效时机）同样必须保留");

        assertEquals(1, admin.getHeardSounds().size(), "成功时恰好一次成功音效");
        assertEquals(Sound.BLOCK_NOTE_BLOCK_PLING.getKey().getKey(),
                admin.getHeardSounds().get(0).getSound(),
                "成功音必须仍是 PLING；实际：" + admin.getHeardSounds().get(0).getSound());

        assertFalse(config.hasUnsavedChanges(),
                "全部落盘后脏标志必须归 false，保存按钮熄灭");
        assertNotNull(menu.getInventory().getItem(SAVE_SLOT),
                "reopen() 后槽位仍应有物品（成功时是 GREEN_WOOL「已是最新」）");
    }
}
