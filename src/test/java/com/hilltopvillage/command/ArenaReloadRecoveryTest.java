package com.hilltopvillage.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.Mockito;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * arenas.yml 被隔离后的恢复闭环（死路修复）。
 *
 * <p><b>缺陷原貌</b>：arenas.yml 读不出来 → 文件被改名成 {@code *.corrupt}、闸门锁只读、
 * registry 被清空。管理员按日志/闸门提示修好文件后执行 {@code /hilltop reloadconfig}，
 * 该分支当时只 {@code reset()} 了闸门，<b>从不重读 arenas.yml</b>。结果是
 * 「闸门已解封、文件可写，但 registry 仍是空集」：玩家 {@code /hilltop join} 报
 * no-arenas-available，管理员 {@code /hilltop enable} 报 arena-not-found，
 * 唯一出路是重启服务器。</p>
 *
 * <p><b>本类守什么</b>：{@code reloadconfig} 之后 registry 必须真的重新填上，
 * 且<b>运行中的对局不受影响</b>（同一实例对象、仍在 activeArenas 里）——
 * 后者是 {@code MultiArenaManager#reloadDefinitions()} 安全性的证据。</p>
 *
 * <p><b>为什么用真实插件</b>：被测的是「闸门真实读盘 + 真实隔离 + 命令分支」三段连起来的行为，
 * 用 mock 把每一段都钉死只能证明自己写了 if。坏文件写空内容即触发与生产相同的
 * SUSPECT → 隔离 → 清空 registry 链。</p>
 */
class ArenaReloadRecoveryTest extends MockBukkitTestBase {

    private HilltopVillagePlugin plugin;
    private PlayerMock admin;
    private Command bukkitCommand;

    @BeforeEach
    void setUp() {
        plugin = loadFullPlugin();
        server.getScheduler().performTicks(20);
        admin = server.addPlayer("ArenaReloadAdmin");
        admin.addAttachment(plugin, "hilltopvillage.admin", true);
        bukkitCommand = Mockito.mock(Command.class);
        Mockito.when(bukkitCommand.getName()).thenReturn("hilltop");
    }

    private File arenasFile() {
        return new File(plugin.getDataFolder(), "arenas.yml");
    }

    private List<String> runReloadconfig() {
        new HilltopCommandHandler(plugin).handle(admin, bukkitCommand, "hilltop", new String[]{"reloadconfig"});
        List<String> out = new ArrayList<>();
        String message;
        while ((message = admin.nextMessage()) != null) {
            out.add(message);
        }
        return out;
    }

    /** 复现事故前置态：arenas.yml 读不出来 → 隔离 + 闸门只读 + registry 清空。 */
    private String breakArenasYml() throws Exception {
        String good = MockBukkitTestBase.TestPaths.read(arenasFile());
        assertFalse(good.isBlank(), "前置：插件启动时应已写出/读到 arenas.yml");
        MockBukkitTestBase.TestPaths.write(arenasFile(), "   \n");            // 空白文件 = 与生产相同的 SUSPECT
        plugin.getMultiArenaManager().loadArenaDefinitions();
        return good;
    }

    @Test
    @DisplayName("★ 修好文件 + reloadconfig → registry 重新填上（不再需要重启）")
    void reloadconfigRepopulatesRegistry() throws Exception {
        String good = breakArenasYml();
        assertTrue(plugin.getMultiArenaManager().getDefinitionManager().getRegistry().list().isEmpty(),
                "前置：坏 arenas.yml 必须把 registry 清空，否则本用例测不到恢复路径");
        assertTrue(plugin.getMultiArenaManager().getDefinitionManager().getArenasGate().isReadOnly(),
                "前置：闸门必须已锁只读");

        MockBukkitTestBase.TestPaths.write(arenasFile(), good);               // 管理员把文件修好
        List<String> messages = runReloadconfig();

        assertFalse(plugin.getMultiArenaManager().getDefinitionManager().getRegistry().list().isEmpty(),
                "★ /hilltop reloadconfig 必须真的重读 arenas.yml 并重建 registry —— 缺陷版只 reset() 闸门，"
                        + "registry 永远是空集，管理员只能重启服务器。实际播报=" + messages);
        assertNotNull(plugin.getMultiArenaManager().getDefinitionManager().getArena("arena1"),
                "★ arena1 必须回来（出厂模板里它是 enabled: true 的默认竞技场）");
        assertFalse(plugin.getMultiArenaManager().getDefinitionManager().getArenasGate().isReadOnly(),
                "闸门必须因「真的读到并通过校验」而解封");
    }

    @Test
    @DisplayName("对局进行中的实例不受影响：同一实例仍在 activeArenas 里（证明重载是安全的）")
    void runningInstanceSurvivesTheReload() throws Exception {
        ArenaInstance before = plugin.getMultiArenaManager().getArena("arena1");
        assertNotNull(before, "前置：arena1 应已启用（loadFullPlugin + 20 tick）");
        String good = breakArenasYml();

        MockBukkitTestBase.TestPaths.write(arenasFile(), good);
        runReloadconfig();

        assertSame(before, plugin.getMultiArenaManager().getArena("arena1"),
                "★ reloadDefinitions 只替换定义 registry，不得重建/停止活动实例 —— "
                        + "否则日常 reloadconfig 会把正在打的对局踢掉");
    }

    @Test
    @DisplayName("空 registry + 无活动场 + 无待启用项 → reloadconfig 把 enabled 的场重新入队（等价重启）")
    void emptyRegistryRecoveryAlsoRequeuesArenas() throws Exception {
        // 先把唯一的活动场摘掉：disable 会退役实例、清空 activeArenas，并把该世界放进隔离窗口，
        // 因此「空 registry + 无活动场 + 无待启用项」这一恢复态是真的能出现的
        assertEquals("deploy.ok", plugin.getMultiArenaManager().disableArena("arena1"));
        assertTrue(plugin.getMultiArenaManager().getActiveArenas().isEmpty(),
                "前置：disable 后不应还有活动实例");
        assertTrue(plugin.getMultiArenaManager().getEnableQueue().isEmpty(),
                "前置：disable 不应留下待启用项");

        String good = breakArenasYml();
        assertTrue(plugin.getMultiArenaManager().getDefinitionManager().getRegistry().list().isEmpty());

        MockBukkitTestBase.TestPaths.write(arenasFile(), good);
        runReloadconfig();

        assertNotNull(plugin.getMultiArenaManager().getDefinitionManager().getArena("arena1"),
                "定义必须回来");
        assertTrue(plugin.getMultiArenaManager().getEnableQueue().contains("arena1")
                        || plugin.getMultiArenaManager().getArena("arena1") != null,
                "★ 光把定义读回来玩家仍然 join 不到（joinArena 查的是活动实例）："
                        + "重启会重新启用 enabled 的场，恢复路径必须等价。实际队列="
                        + plugin.getMultiArenaManager().getEnableQueue());
    }

    @Test
    @DisplayName("对照：文件仍是坏的 → reloadconfig 如实播报「没有加载到竞技场定义」")
    void stillBrokenFileIsReportedHonestly() throws Exception {
        breakArenasYml();                                   // 不修复，直接 reloadconfig

        List<String> messages = runReloadconfig();

        String emptyNotice = plugin.getLanguageManager().getFor(admin, "config.arenas-reload-empty");
        assertTrue(messages.contains(emptyNotice),
                "★ 文件仍不可用时不能只播「已重载」，必须点名没有竞技场定义可用。实际播报=" + messages);
        assertTrue(plugin.getMultiArenaManager().getDefinitionManager().getRegistry().list().isEmpty(),
                "仍未修复 → registry 必须是空的（闸门重新锁上，隔离未被绕过）");
        assertTrue(plugin.getMultiArenaManager().getDefinitionManager().getArenasGate().isReadOnly(),
                "★ 修不好就必须重新锁定只读 —— 恢复路径不得削弱 YAML 安全设计");
    }
}
