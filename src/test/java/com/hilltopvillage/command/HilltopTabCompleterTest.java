package com.hilltopvillage.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.command.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaManager;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link HilltopTabCompleter} 的参数 → 补全列表映射契约测试。
 *
 * <p><b>为什么值得测</b>：补全器是「权限边界」的第二道展示层。它有两个容易回退、
 * 且回退后<b>症状极隐蔽</b>的点：
 * <ol>
 *   <li><b>权限过滤</b>：管理子命令不该出现在普通玩家的补全里。漏了不是报错，而是
 *       「玩家看得到、打了却被告知无权限」——发现成本高、观感差。</li>
 *   <li><b>未匹配的子命令必须返回空列表，而不是整个顶层命令表</b>。
 *       本类实测确认：{@code args=["nope","al"]} 与未知命令名都返回空；
 *       这条一旦回退成 `return 顶层列表`，玩家在任何位置按 Tab 都会看到全部子命令。</li>
 * </ol>
 *
 * <p><b>关于 {@code /hub} 与未知命令</b>：作者实测，MockBukkit 的 {@code PlayerMock#hasPermission}
 * 默认返回 false（除非 {@code addAttachment}）。因此「管理员视角」的用例必须显式授予权限，
 * 否则会把「被权限过滤掉」误读成「补全器坏了」。
 */
class HilltopTabCompleterTest extends MockBukkitTestBase {

    private HilltopTabCompleter completer;
    private PlayerMock admin;
    private PlayerMock player;
    private Command hilltopCommand;
    private Command hubCommand;
    private Command lobbyCommand;

    @BeforeEach
    void rig() {
        // 真实 ArenaManager + 真实 registry：注册表只是内存表，无需世界即可构造。
        var host = createDummyPlugin("TabHost");
        ArenaManager definitionManager = new ArenaManager(host);
        definitionManager.getRegistry().register(ArenaDefinition.builder("arena1", "w1", null).build());
        definitionManager.getRegistry().register(ArenaDefinition.builder("arena2", "w2", null).build());
        definitionManager.getRegistry().register(ArenaDefinition.builder("Arctic", "w3", null).build());

        MultiArenaManager multiArenaManager = mock(MultiArenaManager.class);
        when(multiArenaManager.getDefinitionManager()).thenReturn(definitionManager);

        HilltopVillagePlugin plugin = mock(HilltopVillagePlugin.class);
        when(plugin.getMultiArenaManager()).thenReturn(multiArenaManager);
        completer = new HilltopTabCompleter(plugin);

        admin = server.addPlayer("TabAdmin");
        // ⚠️ addAttachment 的 Plugin 参数必须是真实 Plugin（PluginMock），不能是 Mockito mock ——
        // 它内部会读 getDescription().getFullName()，mock 上返回 null 直接 NPE（实测踩过）。
        admin.addAttachment(host, "hilltopvillage.admin", true);

        player = server.addPlayer("TabPlayer");

        hilltopCommand = mock(Command.class);
        when(hilltopCommand.getName()).thenReturn("hilltop");
        hubCommand = mock(Command.class);
        when(hubCommand.getName()).thenReturn("hub");
        lobbyCommand = mock(Command.class);
        when(lobbyCommand.getName()).thenReturn("lobby");
    }

    private List<String> complete(PlayerMock sender, Command command, String... args) {
        return completer.onTabComplete(sender, command, command.getName(), args);
    }

    // ---------------------------------------------------------------- 顶层命令表

    @Test
    @DisplayName("管理员看得到管理子命令（start / stop / arenas ...）")
    void adminSeesAdminSubcommands() {
        List<String> out = complete(admin, hilltopCommand, "");

        assertTrue(out.contains("start"), "管理员必须在顶层补全里看到 start");
        assertTrue(out.contains("arenas"), "管理员必须在顶层补全里看到 arenas");
        assertTrue(out.contains("reloadconfig"), "管理员必须在顶层补全里看到 reloadconfig");
    }

    @Test
    @DisplayName("普通玩家看不到任何管理子命令，但看得到玩家子命令")
    void playerSeesNoAdminSubcommandsButSeesPlayerOnes() {
        List<String> out = complete(player, hilltopCommand, "");

        for (String adminOnly : List.of("start", "stop", "arenas", "reloadconfig", "setup", "deploy", "config")) {
            assertTrue(!out.contains(adminOnly),
                    "普通玩家的补全里不得出现管理子命令 '" + adminOnly + "'");
        }
        assertTrue(out.contains("join"), "普通玩家必须看得到 join");
        assertTrue(out.contains("leave"), "普通玩家必须看得到 leave");
        assertTrue(out.contains("list"), "普通玩家必须看得到 list");
    }

    @Test
    @DisplayName("fix6 Y3-R07-08：help 已撤出 admin 门，普通玩家也能补全到")
    void helpIsNotAdminGated() {
        List<String> out = complete(player, hilltopCommand, "");

        assertTrue(out.contains("help"),
                "fix6 Y3-R07-08：help 的命令体已开放给玩家，补全必须同步 —— "
                        + "否则玩家能看到 /hilltop help 的提示却补全不到它");
    }

    @Test
    @DisplayName("顶层补全按前缀过滤（大小写不敏感）")
    void topLevelFiltersByPrefixIgnoringCase() {
        List<String> lower = complete(admin, hilltopCommand, "st");
        List<String> upper = complete(admin, hilltopCommand, "ST");

        assertEquals(List.of("start", "stop", "status", "stats"), lower,
                "前缀 'st' 应补全 start / stop / status / stats（stop 也以 st 开头）");
        assertEquals(lower, upper, "前缀匹配必须大小写不敏感");
    }

    // ---------------------------------------------------------------- 竞技场 ID

    @Test
    @DisplayName("arena 类子命令的第 2 参数补全竞技场 ID，并按前缀过滤")
    void arenaSubcommandCompletesArenaIds() {
        List<String> out = complete(admin, hilltopCommand, "join", "arena");

        assertEquals(List.of("arena1", "arena2"), out,
                "join 的第 2 参数应补全以 'arena' 开头的竞技场 ID");
    }

    @Test
    @DisplayName("竞技场 ID 前缀过滤大小写不敏感（Arctic 用 'arc' 能命中）")
    void arenaIdPrefixIsCaseInsensitive() {
        List<String> out = complete(admin, hilltopCommand, "join", "arc");

        assertEquals(List.of("Arctic"), out,
                "registry 里的 ID 是 'Arctic'，用 'arc' 必须命中 —— 否则管理员只能凭记忆敲大小写");
    }

    @Test
    @DisplayName("非 arena 类子命令的第 2 参数不补全竞技场 ID（应为空）")
    void nonArenaSubcommandDoesNotCompleteArenaIds() {
        List<String> out = complete(admin, hilltopCommand, "nope", "arena");

        assertEquals(List.of(), out,
                "未知子命令的第 2 参数必须返回空列表 —— 若退化成返回顶层命令表，"
                        + "玩家在任何位置按 Tab 都会看到全部子命令");
    }

    // ---------------------------------------------------------------- 权限边界

    @Test
    @DisplayName("普通玩家在 admin 专属 arena 子命令后补全竞技场 ID 时得到空列表")
    void playerGetsNoArenaIdsForAdminOnlyArenaSubcommands() {
        List<String> out = complete(player, hilltopCommand, "start", "arena");

        assertEquals(List.of(), out,
                "start/enable/stop/disable 是 admin 专属，普通玩家连竞技场 ID 都不该补全出来");
    }

    @Test
    @DisplayName("普通玩家在 join 后仍能补全竞技场 ID（join 不是 admin 专属）")
    void playerGetsArenaIdsForJoin() {
        List<String> out = complete(player, hilltopCommand, "join", "arena");

        assertEquals(List.of("arena1", "arena2"), out,
                "join 对玩家开放，竞技场 ID 补全不该被权限拦掉");
    }

    // ---------------------------------------------------------------- 顶层快捷命令 / 未知命令

    @Test
    @DisplayName("/hub 永远没有补全项")
    void hubHasNoCompletions() {
        assertEquals(List.of(), complete(admin, hubCommand, ""),
                "/hub 无参数，补全必须为空");
    }

    @Test
    @DisplayName("/lobby setspawn 只在管理员、且 args[0] 落在 args[0] 位置时补全")
    void lobbySetspawnOnlyForAdmin() {
        assertEquals(List.of(), complete(player, lobbyCommand, ""),
                "普通玩家补全 /lobby 不该看到 setspawn");
        assertEquals(List.of("setspawn"), complete(admin, lobbyCommand, ""),
                "管理员补全 /lobby 应看到 setspawn（注意它在 args[0]，不是 args[1]）");
        assertEquals(List.of(), complete(admin, lobbyCommand, "setspawn", "x"),
                "/lobby 只有一个参数位，第 2 参数不得再补全任何东西");
    }

    @Test
    @DisplayName("未知命令返回 null（让 Bukkit 回落到别的补全器）")
    void unknownCommandReturnsNull() {
        Command other = mock(Command.class);
        when(other.getName()).thenReturn("somethingelse");

        assertNull(complete(admin, other, ""),
                "非本插件命令必须返回 null 而不是空列表 —— 返回空列表会抢掉其它插件的补全");
    }

    // ---------------------------------------------------------------- 子命令各自的候选表

    @Test
    @DisplayName("/hilltop wave 第 2 参数补全 clear / skip")
    void waveCompletesClearAndSkip() {
        assertEquals(List.of("clear", "skip"), complete(admin, hilltopCommand, "wave", ""));
        assertEquals(List.of("skip"), complete(admin, hilltopCommand, "wave", "s"));
    }

    @Test
    @DisplayName("/hilltop top 第 2 参数补全统计维度")
    void topCompletesStatisticDimensions() {
        List<String> out = complete(player, hilltopCommand, "top", "");

        assertTrue(out.contains("kills") && out.contains("wins") && out.contains("nodes"),
                "top 必须补全 kills / wins / nodes 等统计维度，实际=" + out);
    }

    @Test
    @DisplayName("/hilltop lang 第 2 参数补全 en / zh")
    void langCompletesLocales() {
        assertEquals(List.of("en", "zh"), complete(player, hilltopCommand, "lang", ""));
    }

    @Test
    @DisplayName("/hilltop setup goto 第 3 参数补全 1-8 步骤数字")
    void setupGotoCompletesStepNumbers() {
        List<String> all = complete(admin, hilltopCommand, "setup", "goto", "");
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8"), all,
                "setup goto 必须补全 1-8 的步骤号");

        assertEquals(List.of("1"), complete(admin, hilltopCommand, "setup", "goto", "1"),
                "前缀 '1' 只应命中步骤 1");
    }
}
