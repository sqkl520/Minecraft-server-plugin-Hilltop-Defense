package com.hilltopvillage.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link StatsManager} 的排行榜过滤、累加语义与落盘口径。
 *
 * <p><b>这个类守的核心缺陷</b>：{@code top()} 的「零记录过滤」曾硬编码为
 * {@code gamesPlayed == 0 && kills == 0}，而排序用的是 {@code type} 指定的字段。
 * 于是<b>中途离开的玩家</b>（{@code victory == null}，不计场次）只要本局没击杀、
 * 只修了节点，就永远进不了 {@code top(NODES)} —— 数据其实落盘了，玩家却在榜上找不到自己。
 * 修复把它改成 {@code type.value(stats) <= 0}，即「过滤字段与排序字段同源」。
 *
 * <p>用 {@code BareHost}（空 {@code onEnable}）：{@code StatsManager} 的构造器只需要
 * {@code getDataFolder()} / {@code getLogger()} / {@code isEnabled()}，不碰世界与调度器。
 */
class StatsManagerTest extends MockBukkitTestBase {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID CARA = UUID.nameUUIDFromBytes("cara".getBytes());

    /**
     * 数 player-stats.yml 的隔离产物（固定名或带时间戳的都算）。
     *
     * <p>隔离产物自 S10 系列修复起带时间戳，固定名只作前缀；测试不能再硬编码
     * {@code player-stats.yml.corrupt}。</p>
     */
    private static long countQuarantineFiles(BareHost host) {
        File[] all = host.getDataFolder().listFiles();
        if (all == null) return 0;
        return java.util.Arrays.stream(all)
                .filter(f -> f.getName().equals("player-stats.yml.corrupt")
                        || f.getName().startsWith("player-stats.yml.corrupt."))
                .count();
    }

    // ---------------------------------------------------------------- top() 过滤

    @Test
    @DisplayName("top(NODES): 中途离开、0 击杀、只修了节点的玩家必须出现在榜单上")
    void topIncludesNodeOnlyMidLeaver() {
        BareHost host = loadBareHost();
        StatsManager stats = new StatsManager(host);

        PlayerData bobData = new PlayerData(BOB);
        bobData.addNodeRepaired();
        bobData.addNodeRepaired();
        // victory == null → 不计场次，kills 保持 0。这正是旧过滤判据漏掉的那类玩家。
        stats.recordOffline(BOB, "Bob", bobData, null, 3);

        List<Map.Entry<UUID, StatsManager.PlayerStats>> nodes = stats.top(StatsManager.StatType.NODES, 10);

        assertEquals(1, nodes.size(),
                "top(NODES) 必须包含只修过节点的中途离开玩家。旧实现（gamesPlayed==0 && kills==0 过滤）"
                        + "会把他永久排除 —— 这是本轮 top() 修复的回归锚点");
        assertEquals(BOB, nodes.get(0).getKey());
        assertEquals(2, nodes.get(0).getValue().getNodesRepaired());
    }

    @Test
    @DisplayName("top(): 每个玩家只出现在「本轮他自己有值」的榜上（过滤与排序同源）")
    void topIsolatesEachStatistic() {
        BareHost host = loadBareHost();
        StatsManager stats = new StatsManager(host);

        PlayerData aliceData = new PlayerData(ALICE);
        aliceData.addKill();
        aliceData.addKill();
        aliceData.addKill();
        aliceData.addKill();
        aliceData.addKill();
        stats.recordOffline(ALICE, "Alice", aliceData, Boolean.TRUE, 7);

        PlayerData bobData = new PlayerData(BOB);
        bobData.addNodeRepaired();
        stats.recordOffline(BOB, "Bob", bobData, null, 3);

        PlayerData caraData = new PlayerData(CARA);
        caraData.addDeath();
        stats.recordOffline(CARA, "Cara", caraData, Boolean.FALSE, 0);

        assertEquals(List.of(ALICE), ids(stats.top(StatsManager.StatType.KILLS, 10)),
                "top(KILLS) 只应有 Alice");
        assertEquals(List.of(BOB), ids(stats.top(StatsManager.StatType.NODES, 10)),
                "top(NODES) 只应有 Bob（中途离开、kills=0）");
        assertEquals(List.of(CARA), ids(stats.top(StatsManager.StatType.DEATHS, 10)),
                "top(DEATHS) 只应有 Cara（0 击杀、0 场次、0 节点）");
        assertEquals(List.of(ALICE), ids(stats.top(StatsManager.StatType.WINS, 10)),
                "top(WINS) 只应有 Alice");
    }

    @Test
    @DisplayName("top(): 按数值降序，limit 生效，limit 超过总数时返回全量")
    void topSortsDescendingAndRespectsLimit() {
        BareHost host = loadBareHost();
        StatsManager stats = new StatsManager(host);

        for (int i = 0; i < 5; i++) {
            PlayerData data = new PlayerData(UUID.nameUUIDFromBytes(("p" + i).getBytes()));
            for (int k = 0; k <= i; k++) data.addKill();
            stats.recordOffline(UUID.nameUUIDFromBytes(("p" + i).getBytes()), "P" + i, data, Boolean.TRUE, 1);
        }

        List<Map.Entry<UUID, StatsManager.PlayerStats>> top2 = stats.top(StatsManager.StatType.KILLS, 2);
        assertEquals(2, top2.size(), "limit=2 应返回 2 条");
        assertTrue(top2.get(0).getValue().getKills() >= top2.get(1).getValue().getKills(),
                "必须降序：第一名击杀数不得少于第二名");
        assertEquals(5, top2.get(0).getValue().getKills(), "击杀最多的 P4（5 杀）应在首位");

        assertEquals(5, stats.top(StatsManager.StatType.KILLS, 999).size(),
                "limit 超过总数时应返回全部记录，不抛异常");
    }

    @Test
    @DisplayName("top(): 空缓存返回空列表（新服首次启动不能抛）")
    void topOnEmptyCacheIsEmpty() {
        StatsManager stats = new StatsManager(loadBareHost());

        assertTrue(stats.top(StatsManager.StatType.KILLS, 10).isEmpty());
    }

    // ---------------------------------------------------------------- 累加语义

    @Test
    @DisplayName("recordOffline: victory==null 不计场次，但波次与最佳波次照常累计")
    void midLeaveCountsWavesButNotGames() {
        BareHost host = loadBareHost();
        StatsManager stats = new StatsManager(host);

        stats.recordOffline(BOB, "Bob", new PlayerData(BOB), null, 3);

        StatsManager.PlayerStats bob = stats.get(BOB);
        assertEquals(0, bob.getGamesPlayed(), "中途离开不计场次（victory == null）");
        assertEquals(3, bob.getWavesSurvived(), "波次必须照常累计");
        assertEquals(3, bob.getBestWave(), "最佳波次必须照常更新");
    }

    @Test
    @DisplayName("recordOffline: bestWave 用 Math.max，不倒退")
    void bestWaveNeverRegresses() {
        BareHost host = loadBareHost();
        StatsManager stats = new StatsManager(host);

        stats.recordOffline(BOB, "Bob", new PlayerData(BOB), Boolean.TRUE, 7);
        stats.recordOffline(BOB, "Bob", new PlayerData(BOB), Boolean.TRUE, 3);

        assertEquals(7, stats.get(BOB).getBestWave(),
                "bestWave 必须是历史最大值 —— 用赋值而非 Math.max 会让高分局被低分局覆盖");
        assertEquals(10, stats.get(BOB).getWavesSurvived(), "wavesSurvived 是累计值：7 + 3");
    }

    @Test
    @DisplayName("recordOffline: 负波次被夹到 0，不写负值")
    void negativeWaveIsClamped() {
        BareHost host = loadBareHost();
        StatsManager stats = new StatsManager(host);

        stats.recordOffline(BOB, "Bob", new PlayerData(BOB), Boolean.TRUE, -5);

        assertEquals(0, stats.get(BOB).getWavesSurvived(), "wavesSurvived 不得因负值减少");
        assertEquals(0, stats.get(BOB).getBestWave(), "bestWave 不得被写成负数");
    }

    @Test
    @DisplayName("recordOffline: 名字为 null 时保留缓存里已有的名字")
    void nullNameKeepsExistingName() {
        BareHost host = loadBareHost();
        StatsManager stats = new StatsManager(host);

        stats.recordOffline(BOB, "Bob", new PlayerData(BOB), null, 1);
        stats.recordOffline(BOB, null, new PlayerData(BOB), null, 1);

        assertEquals("Bob", stats.get(BOB).getName(),
                "重连路径可能拿不到名字，此时不得把已有名字清空");
    }

    @Test
    @DisplayName("recordOffline(null, ...) 直接返回，不建记录")
    void nullUuidIsIgnored() {
        StatsManager stats = new StatsManager(loadBareHost());

        stats.recordOffline(null, "Ghost", new PlayerData(BOB), Boolean.TRUE, 5);

        assertTrue(stats.top(StatsManager.StatType.GAMES, 10).isEmpty(), "null UUID 不得产生任何记录");
    }

    @Test
    @DisplayName("findByName: 大小写不敏感；null / 未知名返回 null")
    void findByNameIsCaseInsensitive() {
        BareHost host = loadBareHost();
        StatsManager stats = new StatsManager(host);
        stats.recordOffline(ALICE, "Alice", new PlayerData(ALICE), Boolean.TRUE, 1);

        assertNotNull(stats.findByName("alice"), "大小写不敏感查找应命中");
        assertNotNull(stats.findByName("ALICE"));
        assertNull(stats.findByName("nobody"));
        assertNull(stats.findByName(null), "null 必须返回 null 而不是 NPE");
    }

    // ---------------------------------------------------------------- 落盘

    @Test
    @DisplayName("saveNow: 落盘后重建 StatsManager 能读回（榜单过滤与落盘口径不同源的显式规格）")
    void saveNowPersistsRecordsRejectedByTheOldFilter() {
        BareHost host = loadBareHost();
        StatsManager stats = new StatsManager(host);

        PlayerData bobData = new PlayerData(BOB);
        bobData.addNodeRepaired();
        bobData.addNodeRepaired();
        stats.recordOffline(BOB, "Bob", bobData, null, 3);
        stats.saveNow();

        File statsFile = new File(host.getDataFolder(), "player-stats.yml");
        assertTrue(statsFile.isFile(), "saveNow() 后 player-stats.yml 必须存在（不要依赖定时刷盘）");

        StatsManager reloaded = new StatsManager(host);
        List<Map.Entry<UUID, StatsManager.PlayerStats>> nodes = reloaded.top(StatsManager.StatType.NODES, 10);
        assertEquals(1, nodes.size(),
                "重建后 top(NODES) 仍应包含 Bob —— buildSnapshot() 的落盘判据（六字段全零才跳过）"
                        + " 与 top() 的判据（单字段）刻意不同源，两者都不得漏掉这条记录");
        assertEquals(2, nodes.get(0).getValue().getNodesRepaired());
        assertEquals("Bob", nodes.get(0).getValue().getName());
    }

    @Test
    @DisplayName("saveNow: 全零记录不落盘（get() 会建空记录，但不应写进文件）")
    void emptyRecordsAreNotPersisted() throws Exception {
        BareHost host = loadBareHost();
        StatsManager stats = new StatsManager(host);

        UUID ghost = UUID.nameUUIDFromBytes("ghost".getBytes());
        stats.get(ghost);                        // 建一条全零空记录
        stats.saveNow();

        File statsFile = new File(host.getDataFolder(), "player-stats.yml");
        assertTrue(statsFile.isFile(), "saveNow() 会写文件（dirty 被强制置位）");

        // ★ 必须直接看文件内容，不能只看 reload 后的榜单：全零记录即使写了盘，
        //   load() 读回来之后 top(GAMES) 也会因 gamesPlayed==0 把它过滤掉 ——
        //   用榜单断言会让「落盘判据失效」这条缺陷完全隐形（第一版测试就栽在这里）。
        String raw = MockBukkitTestBase.TestPaths.read(statsFile);
        assertFalse(raw.contains(ghost.toString()),
                "从未参与过对局的空记录不得落盘 —— buildSnapshot() 的六字段全零判据必须真的跳过它，"
                        + " 否则文件会随扫描过的玩家无限膨胀。文件内容：" + raw);

        StatsManager reloaded = new StatsManager(host);
        assertTrue(reloaded.top(StatsManager.StatType.GAMES, 10).isEmpty(),
                "重建后也不应有记录");
    }

    @Test
    @DisplayName("load: 损坏的 player-stats.yml 被隔离为 .corrupt，以空数据启动而不炸插件")
    void corruptFileIsQuarantined() throws Exception {
        BareHost host = loadBareHost();
        File statsFile = new File(host.getDataFolder(), "player-stats.yml");
        MockBukkitTestBase.TestPaths.write(statsFile, "players:\n  not-a-uuid:\n    name: [unclosed\n");
        // 注意：形如 `name: [unclosed` 的 YAML 解析会直接抛 —— 走 PARSE 路径。

        StatsManager stats = new StatsManager(host);

        assertTrue(stats.top(StatsManager.StatType.KILLS, 10).isEmpty(),
                "损坏文件应以空统计启动，而不是让 onEnable 抛异常");
        assertFalse(statsFile.exists(), "损坏文件应被移走");
        assertTrue(countQuarantineFiles(host) > 0,
                "损坏现场应保留为 .corrupt");
    }

    // ---------------------------------------------------------------- 读盘容错

    /**
     * 缺陷 A：0 字节文件曾被静默接受。
     *
     * <p>{@code loadFromString("")} 不抛异常 —— 它得到空配置，于是 {@code root == null}
     * 静默返回空缓存，既无日志也无隔离。而这份文件在真实部署目录中确实被实测为 0 字节形态，
     * 等于一个已知的真实故障模式完全无声。
     */
    @Test
    @DisplayName("load: 0 字节文件 = 截断产物，必须隔离并 SEVERE，不得静默接受")
    void emptyFileIsQuarantinedNotSilentlyAccepted() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        File statsFile = new File(host.getDataFolder(), "player-stats.yml");
        MockBukkitTestBase.TestPaths.write(statsFile, "");

        StatsManager stats = new StatsManager(host);

        assertTrue(stats.top(StatsManager.StatType.KILLS, 10).isEmpty(), "应以空统计启动");
        assertFalse(statsFile.exists(), "0 字节文件必须被移走 —— 它是截断写的签名，不是合法空表");
        assertTrue(countQuarantineFiles(host) > 0,
                "空文件的现场必须保留为 .corrupt，供服主核对");
        assertTrue(logs.anyMatch(line -> line.startsWith("SEVERE|") && line.contains("is empty")),
                "空文件必须打 SEVERE（旧实现在这里一行日志都没有）。捕获到：" + logs.lines());
    }

    @Test
    @DisplayName("load: 纯空白文件（非 0 字节但无 YAML 文档）同样按截断处理")
    void whitespaceOnlyFileIsQuarantined() throws Exception {
        BareHost host = loadBareHost();
        File statsFile = new File(host.getDataFolder(), "player-stats.yml");
        MockBukkitTestBase.TestPaths.write(statsFile, "  \n\t\n\n");

        new StatsManager(host);

        assertFalse(statsFile.exists(), "只有空白字符的文件同样没有任何 YAML 文档，必须隔离");
        assertTrue(countQuarantineFiles(host) > 0);
    }

    /**
     * 缺陷 A 的另一半：把「无 YAML 文档」与「有文档但没有 players 段」分开。
     *
     * <p>后者是<b>本类能读懂的合法形态</b>（当前零记录，或服主手写成这样）。
     * 若把两者一并隔离，服主在文件里留一行别的键就会丢掉整份文件 —— 代价与收益倒挂。
     */
    @Test
    @DisplayName("load: 合法 YAML 但没有 players 段 = 空但有效，绝不隔离")
    void validYamlWithoutPlayersSectionIsNotQuarantined() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        File statsFile = new File(host.getDataFolder(), "player-stats.yml");
        MockBukkitTestBase.TestPaths.write(statsFile, "some-other-key: 1\nplayers-empty: true\n");

        StatsManager stats = new StatsManager(host);

        assertTrue(stats.top(StatsManager.StatType.KILLS, 10).isEmpty(), "零记录应是空缓存");
        assertTrue(statsFile.isFile(), "能解析的文件不得被移走 —— 这是合法配置，不是损坏");
        assertFalse(countQuarantineFiles(host) > 0,
                "不得产生 .corrupt");
        assertEquals(0, logs.countAt(java.util.logging.Level.SEVERE),
                "合法空配置不该刷 SEVERE。捕获到：" + logs.lines());
    }

    @Test
    @DisplayName("buildSnapshot: 空表落盘产物是 `players: {}`，重读不得被当成截断隔离（自洽性）")
    void emptySnapshotRoundTripsWithoutSelfQuarantine() throws Exception {
        BareHost host = loadBareHost();
        StatsManager stats = new StatsManager(host);
        stats.saveNow();

        File statsFile = new File(host.getDataFolder(), "player-stats.yml");
        assertTrue(statsFile.isFile(), "saveNow() 必须真的写出文件");
        assertFalse(MockBukkitTestBase.TestPaths.read(statsFile).isBlank(),
                "空表落盘也不能产出 0 字节 —— 否则本类会亲手写出 load() 要隔离的截断形态。实际内容："
                        + MockBukkitTestBase.TestPaths.read(statsFile));

        // 重读：同一份文件不得被判损坏（否则「空表 → 落盘 → 重启 → 文件被隔离」形成闭环）
        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        new StatsManager(host);
        assertTrue(statsFile.isFile(), "自己写出的空表在重读时不得被隔离");
        assertEquals(0, logs.countAt(java.util.logging.Level.SEVERE),
                "重读不得打 SEVERE。捕获到：" + logs.lines());
    }

    /**
     * 缺陷 B：瞬时 I/O 失败曾与解析失败共用一个 catch。
     *
     * <p>用一个<b>目录</b>顶替 {@code player-stats.yml}：{@code exists()} 为 true，
     * 而 {@code Files.readString} 会抛 {@link java.io.IOException}（Windows 上是
     * {@code AccessDeniedException: Is a directory}）。这精确复现了「文件在、内容没问题、
     * 这次就是读不到」的语义 —— 而这正是杀软/备份占用时的情形（内容完好）。
     */
    @Test
    @DisplayName("load: I/O 失败 → 不隔离、进只读、拒写（绝不用空缓存覆盖读不出来的好数据）")
    void ioFailureEntersReadOnlyAndRefusesToSave() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        File statsFile = new File(host.getDataFolder(), "player-stats.yml");
        java.nio.file.Files.createDirectory(statsFile.toPath());   // exists() == true，读必失败

        StatsManager stats = new StatsManager(host);

        assertTrue(stats.top(StatsManager.StatType.KILLS, 10).isEmpty(), "应以空缓存启动");
        assertTrue(logs.anyMatch(line -> line.startsWith("SEVERE|")
                        && line.contains("could not be read") && line.contains("READ-ONLY")),
                "I/O 失败必须 SEVERE 且明确宣告进入只读。捕获到：" + logs.lines());
        assertFalse(countQuarantineFiles(host) > 0,
                "I/O 失败绝不能隔离 —— 那会把一份内容完好的文件挪走");
        assertTrue(java.nio.file.Files.isDirectory(statsFile.toPath()), "原文件必须原地保留");

        // 关键：本局结算出的战绩不得落盘（否则 60 秒后定时刷盘会用空快照覆盖那份好数据）
        stats.recordOffline(BOB, "Bob", new PlayerData(BOB), Boolean.TRUE, 5);
        stats.saveNow();

        assertTrue(java.nio.file.Files.isDirectory(statsFile.toPath()),
                "只读模式下 saveNow() 必须拒写 —— 文件路径上仍是原物，未被替换成空快照");
        assertTrue(logs.anyMatch(line -> line.startsWith("WARNING|")
                        && line.contains("Refusing to save " + "player-stats.yml")),
                "拒写必须明确告知（含恢复指引）。捕获到：" + logs.lines());
        assertEquals(1, logs.lines().stream()
                        .filter(line -> line.contains("Refusing to save player-stats.yml")).count(),
                "拒写提示必须只打一次 —— 每轮定时刷盘都刷一行会把日志淹掉");
    }

    @Test
    @DisplayName("load: 只读降级在「文件再次可读」时解除（对应 YamlFileGate 的 OK 才解封）")
    void readOnlyIsClearedWhenFileBecomesReadableAgain() throws Exception {
        BareHost host = loadBareHost();
        File statsFile = new File(host.getDataFolder(), "player-stats.yml");
        java.nio.file.Files.createDirectory(statsFile.toPath());

        StatsManager stats = new StatsManager(host);
        java.nio.file.Files.delete(statsFile.toPath());
        MockBukkitTestBase.TestPaths.write(statsFile, "players: {}\n");

        stats.load();                    // 管理员修好文件后的 reload 路径

        stats.recordOffline(BOB, "Bob", new PlayerData(BOB), Boolean.TRUE, 5);
        stats.saveNow();

        assertTrue(statsFile.isFile(), "解除只读后必须恢复写盘");
        assertFalse(MockBukkitTestBase.TestPaths.read(statsFile).isBlank(), "文件内容不得为空");
        assertFalse(MockBukkitTestBase.TestPaths.read(statsFile).isEmpty());
        assertEquals(1, new StatsManager(host).top(StatsManager.StatType.GAMES, 10).size(),
                "解除只读后写入的战绩必须能被重读回来");
    }

    // ---------------------------------------------------------------- StatType

    @Test
    @DisplayName("StatType: value() 与 comparator 指向同一字段（同源的直接证据）")
    void statTypeValueMatchesItsComparator() {
        BareHost host = loadBareHost();
        StatsManager stats = new StatsManager(host);

        PlayerData richData = new PlayerData(ALICE);
        for (int i = 0; i < 4; i++) richData.addKill();
        for (int i = 0; i < 3; i++) richData.addDeath();
        for (int i = 0; i < 2; i++) richData.addNodeRepaired();
        richData.addCurrencyCollected(6);
        stats.recordOffline(ALICE, "Alice", richData, Boolean.TRUE, 9);

        StatsManager.PlayerStats alice = stats.get(ALICE);
        assertEquals(4, StatsManager.StatType.KILLS.value(alice));
        assertEquals(3, StatsManager.StatType.DEATHS.value(alice));
        assertEquals(2, StatsManager.StatType.NODES.value(alice));
        assertEquals(6, StatsManager.StatType.CRYSTALS.value(alice));
        assertEquals(1, StatsManager.StatType.GAMES.value(alice));
        assertEquals(1, StatsManager.StatType.WINS.value(alice));
        assertEquals(9, StatsManager.StatType.BEST_WAVE.value(alice));

        // 排序方向必须与 value() 同向：value 大的排前面
        assertTrue(StatsManager.StatType.KILLS.descending().compare(alice,
                        stats.get(UUID.nameUUIDFromBytes("zero".getBytes()))) < 0,
                "descending() 必须让 value() 较大的排前面 —— 若比较器指向别的字段，"
                        + " top() 会出现「榜上第 1 名数值比第 2 名小」");
    }

    @Test
    @DisplayName("StatType: fromKey 大小写不敏感，未知 key 返回 null")
    void statTypeFromKey() {
        assertEquals(StatsManager.StatType.NODES, StatsManager.StatType.fromKey("nodes"));
        assertEquals(StatsManager.StatType.NODES, StatsManager.StatType.fromKey("NODES"));
        assertEquals(StatsManager.StatType.BEST_WAVE, StatsManager.StatType.fromKey("best-wave"));
        assertNull(StatsManager.StatType.fromKey("nope"), "未知 key 必须返回 null（命令层据此报错）");
        assertNull(StatsManager.StatType.fromKey(null));
        assertTrue(StatsManager.StatType.allKeys().contains("nodes"),
                "allKeys() 是命令补全与报错文案的唯一来源，必须含全部 7 个 key");
    }

    // ---------------------------------------------------------------- 工具

    private static List<UUID> ids(List<Map.Entry<UUID, StatsManager.PlayerStats>> entries) {
        return entries.stream().map(Map.Entry::getKey).toList();
    }
}
