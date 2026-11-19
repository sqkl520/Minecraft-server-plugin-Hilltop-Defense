package com.hilltopvillage.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.Mockito;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * #8 —— <b>{@code /hilltop reloadconfig} 必须把 {@code loadFromFile()} 的失败告诉管理员</b>。
 *
 * <p><b>缺陷原貌</b>：命令路径比 GUI 更隐蔽。{@code loadFromFile()} 失败时既不抛异常也不返回
 * 任何信号（原签名 {@code void}），所以包住它的 {@code catch (RuntimeException)} 不会触发，
 * 随后 {@code config-reloaded} 与 {@code messages-reloaded} 照常发出。管理员看到「已重载」
 * 且控制台之外没有任何错误提示。</p>
 *
 * <p><b>为什么用真实插件</b>：本类要验证的是「命令层的消息分支」与「ConfigManager 的真实失败态」
 * 连在一起。用 mock 把 {@code loadFromFile()} 钉成失败，只能证明自己写了 if；这里用空文件让
 * settings 闸门真实锁定只读，走与生产相同的 SUSPECT → 隔离 → 提前 return 链。</p>
 */
class HilltopCommandReloadResultTest extends MockBukkitTestBase {

    private HilltopVillagePlugin plugin;
    private PlayerMock admin;
    private Command bukkitCommand;

    @BeforeEach
    void setUp() {
        plugin = loadFullPlugin();
        admin = server.addPlayer("ReloadResultAdmin");
        admin.addAttachment(plugin, "hilltopvillage.admin", true);
        bukkitCommand = Mockito.mock(Command.class);
        Mockito.when(bukkitCommand.getName()).thenReturn("hilltop");
    }

    private static File settingsFile(HilltopVillagePlugin plugin) {
        return new File(plugin.getDataFolder(), "game-settings.yml");
    }

    private List<String> run(String... args) {
        HilltopCommandHandler handler = new HilltopCommandHandler(plugin);
        handler.handle(admin, bukkitCommand, "hilltop", args);
        List<String> out = new ArrayList<>();
        String message;
        while ((message = admin.nextMessage()) != null) {
            out.add(message);
        }
        return out;
    }

    @Test
    @DisplayName("★ loadFromFile() 失败 → 发 config-reload-failed，绝不发 config-reloaded")
    void failedReloadDoesNotAnnounceSuccess() throws Exception {
        // 内存里先放一个与出厂值不同的值：失败时它必须原样保留。
        plugin.getConfigManager().getActiveConfig().setVictoryWaves(7);

        MockBukkitTestBase.TestPaths.write(settingsFile(plugin), "   \n");

        List<String> messages = run("reloadconfig");

        String failed = plugin.getLanguageManager().getFor(admin, "config-reload-failed");
        String success = plugin.getLanguageManager().getFor(admin, "config-reloaded");

        assertTrue(messages.contains(failed),
                "★ game-settings.yml 不可用时 /hilltop reloadconfig 必须发 config-reload-failed。"
                        + " 缺陷版完全不发失败消息，管理员只看到「已重载」。实际播报=" + messages);
        assertFalse(messages.contains(success),
                "★ 失败时绝不能发 config-reloaded —— 这正是「消息与事实不符」的核心。实际播报=" + messages);

        assertEquals(7, plugin.getConfigManager().getActiveConfig().getVictoryWaves(),
                "★ 失败时内存值必须保持不变（缺陷定义里的「内存里还是旧值」）");
        assertTrue(plugin.getConfigManager().getSettingsGate().isReadOnly(),
                "前置/结果校验：空文件必须让 settings 闸门锁定只读（否则失败分支根本没走到）");
    }

    @Test
    @DisplayName("反向对照：loadFromFile() 成功 → 仍发 config-reloaded，且磁盘新值真的进内存")
    void successfulReloadKeepsTheOriginalFeedback() throws Exception {
        File settings = settingsFile(plugin);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(settings));
        yaml.set("victory-waves", 33);
        MockBukkitTestBase.TestPaths.write(settings, yaml.saveToString());

        List<String> messages = run("reloadconfig");

        String success = plugin.getLanguageManager().getFor(admin, "config-reloaded");
        String messagesReloaded = plugin.getLanguageManager().getFor(admin, "messages-reloaded");

        assertTrue(messages.contains(success),
                "成功侧必须原样保留 config-reloaded —— 一个「永远播失败」的实现会在这一条红。"
                        + " 实际播报=" + messages);
        assertTrue(messages.contains(messagesReloaded),
                "messages.yml 的独立重载仍应照常播报。实际播报=" + messages);
        assertEquals(33, plugin.getConfigManager().getActiveConfig().getVictoryWaves(),
                "★ 成功侧必须真的把磁盘上的 33 读进内存，否则本用例只是验证了一句文案");
    }
}
