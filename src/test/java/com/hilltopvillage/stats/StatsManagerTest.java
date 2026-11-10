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
        assertTrue(new File(host.getDataFolder(), "player-stats.yml.corrupt").isFile(),
                "损坏现场应保留为 .corrupt");
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
