package com.hilltopvillage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * P2 的安全网：**{@code waves} 的键集权威归磁盘**，以及**唯一的清空动作
 * {@code activeConfig.getWaves().clear()} 的落点**。
 *
 * <p>为什么单独一个类而不是塞进 {@link WavesKeySetAlignmentTest}：</p>
 *
 * <ol>
 *   <li><b>章程冲突</b>：那个类的类注释写死「本类只守 P1 修好的两件事（F2 与症状 2），
 *       <b>不守症状 1（删除复活，留给 P2）</b>」。把它守的东西塞进去，等于当场作废那段注释喵。</li>
 *   <li><b>失败定位</b>：本类的两条用例守的是同一行代码（{@code clear()} 的存在与位置）。
 *       任何一次红都直接说「键集权威 / 清空落点这条契约破了」，不必翻 6 条 P1 用例的上下文喵。</li>
 *   <li><b>红的隔离</b>：本任务的产出是「测试先红」。让这 2 条红在独立类里，跑全量时一眼能看出
 *       「红的只有 P2 那 2 条，P1 的 6 条与本轮改动无关」；混进同一个类就失去这个可读性喵。</li>
 *   <li><b>CHANGELOG 数字</b>：P1 的实现报告已写明「{@code WavesKeySetAlignmentTest} 6 条」，
 *       扩展它会把这个已发布的数字改掉；新建类让 P2 可以干净地说「新增 2 条」喵。</li>
 * </ol>
 *
 * <p><b>本类在 P2 落地前是红的，这是刻意设计。</b>
 * {@link #memoryWaveKeysMatchDiskExactly} 守的是「漏了 {@code clear()}」——
 * 现在 {@code clear()} 还不存在，所以它必须红；红了才证明它有判别力喵。</p>
 *
 * <p><b>取数纪律</b>：一律 {@code getActiveConfig().getWaves().get(n)} + {@code assertNotNull}，
 * 绝不用 {@code getWaveConfig(n)}。后者是 {@code getOrDefault(waveNumber, defaultWave)}
 * （{@code GameConfig:829}），会把「波次丢失」伪装成「拿到了 defaultWave」喵。</p>
 */
class WaveKeySetAuthorityTest extends MockBukkitTestBase {

    // ============================================================ 夹具

    private static File settingsFile(BareHost host) {
        return new File(host.getDataFolder(), "game-settings.yml");
    }

    /**
     * 最小但能过 {@code gameSettingsValidator}（要求 ≥3 个**已知**顶层键，{@code YamlFiles:252-262}）的配置。
     *
     * <p>4 个键（world-name / min-players / max-players / victory-waves）都在
     * {@code GAME_SETTINGS_KNOWN_TOP_LEVEL_KEYS} 里，命中 4 ≥ 3 喵。
     * 不满 3 个时文件会被判 SUSPECT 并隔离成 {@code *.corrupt}，{@code loadFromFile()} 走
     * early return —— 测试会在一个与主题无关的分支上红/绿，诊断价值归零喵。</p>
     */
    private static String minimalSettings() {
        return "world-name: world\n"
                + "min-players: 2\n"
                + "max-players: 6\n"
                + "victory-waves: 20\n";
    }

    /** null 安全地取某个怪种的 count；缺失返回 -1（让失败信息一眼区分「缺失」与「值错」）。 */
    private static int countOf(GameConfig.WaveConfig wave, String mobKey) {
        GameConfig.MobComposition comp = wave.getCompositions().get(mobKey);
        return comp == null ? -1 : comp.getCount();
    }

    // ============================================================ ★ 守 clear() 的存在

    /**
     * 磁盘上只有 {@code waves.7} 时，内存键集必须**恰好**是 {@code {7}}。
     *
     * <p><b>判别力推演（三种实施情况）</b></p>
     *
     * <table border="1">
     *   <caption>同一 fixture 下的键集</caption>
     *   <tr><th>实施情况</th><th>键集</th><th>本测试</th></tr>
     *   <tr><td>P2 正确实施（{@code clear()} 在 {@code if (waveSection != null)} 之内）</td>
     *       <td>{@code {7}}</td><td>绿</td></tr>
     *   <tr><td>P2 漏写 {@code clear()}</td><td>{@code {1, 5, 7, 10}}</td><td><b>红</b>（键集断言）</td></tr>
     *   <tr><td>P2 把 7 也一起清掉（{@code clear()} 之后忘了 {@code put}）</td>
     *       <td>{@code {}}</td><td><b>红</b>（键集断言 + {@code get(7)} 的值断言）</td></tr>
     * </table>
     *
     * <p>为什么**一次构造**就够，不需要 reload：{@code GameConfig} 的构造器必然预置
     * {@code waves{1, 5, 10}}（{@code GameConfig:243-258}），所以「清空没清空」在第一次载入后
     * 就已经可观测喵。</p>
     */
    @Test
    @DisplayName("P2 键集权威: 磁盘只有 waves.7 时，内存 waves 不得残留构造器预置的 1/5/10")
    void memoryWaveKeysMatchDiskExactly() throws Exception {
        BareHost host = loadBareHost();
        // 手写文件必须发生在 new ConfigManager 之前：initSettingsFile() 只在 !exists 时
        // saveResource（ConfigManager:146-149），已存在的手写文件不会被出厂模板覆盖喵。
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  \"7\":\n"
                + "    total-mobs: 9\n"
                + "    compositions:\n"
                + "      explode-beetle:\n"
                + "        count: 2\n"
                + "        weight: 3\n");

        ConfigManager config = new ConfigManager(host);

        Map<Integer, GameConfig.WaveConfig> waves = config.getActiveConfig().getWaves();

        assertEquals(Set.of(7), waves.keySet(),
                "★ 内存 waves 键集必须严格等于磁盘的数字波次键集 {7}。"
                        + " 残留 1/5/10 说明漏了 activeConfig.getWaves().clear()，"
                        + " 症状 1（磁盘上删掉的波次在 reload 后复活）未修 ——"
                        + " 管理员删掉的波次会在下一次 saveToFile() 被原样写回磁盘，删除永久无效。"
                        + " 空集 { } 则是另一个方向的病：clear() 之后没把磁盘上的波次 put 回去（全场零波次）。"
                        + " 实际键集=" + waves.keySet());

        // 第二条断言的作用：证明 clear() 没有把磁盘上的 7 也一起清掉。
        // 只断言键集的话，「清空后重建了个空壳」与「重建正确」无法区分喵。
        GameConfig.WaveConfig wave7 = waves.get(7);
        assertNotNull(wave7, "waves.7 必须存在于内存表（键集断言已红时这条通常也会红）");
        assertEquals(9, wave7.getTotalMobs(),
                "waves.7 的 total-mobs 必须来自磁盘的 9，不是 defaultWave 的 10");
        assertEquals(2, countOf(wave7, "explode-beetle"),
                "waves.7.explode-beetle.count 必须来自磁盘的 2（-1 表示该键被判未知丢弃）");
        assertEquals(3, wave7.getTotalWeight(),
                "waves.7 的 weight 之和必须为 3（clear() 之后重建的是空 map 则会是 0）");
    }

    // ============================================================ ★ 守 clear() 的位置

    /**
     * {@code game-settings.yml} 读不到时，内存里构造器预置的 1/5/10 **必须还在**。
     *
     * <p><b>这条守的是 {@code clear()} 的位置，不是它的存在。</b>可用性守卫
     * （{@code ConfigManager:1246-1254}）的语义是「读不到就不动内存」，它在
     * {@code activeConfig = new GameConfig()}（{@code :1272}）**之前**提前 return。
     * 因此 {@code clear()} 只能落在守卫之后的 {@code if (waveSection != null)} 之内喵。</p>
     *
     * <p><b>判别力推演</b></p>
     *
     * <table border="1">
     *   <caption>文件不可用时的内存 waves</caption>
     *   <tr><th>实施情况</th><th>内存 waves</th><th>本测试</th></tr>
     *   <tr><td>P2 正确实施（{@code clear()} 在 {@code if} 内）</td>
     *       <td>{@code {1, 5, 10}} 原样</td><td>绿</td></tr>
     *   <tr><td>P2 把 {@code clear()} 挪到 {@code loadFromFile} 开头（早退分支之前）</td>
     *       <td>空（1/5/10 全没）</td><td><b>红</b>（三条 {@code assertNotNull} 同时炸）</td></tr>
     *   <tr><td>P2 把 {@code clear()} 放进不可用分支（守卫之内但提前 return 之前）</td>
     *       <td>空</td><td><b>红</b>（同上）</td></tr>
     *   <tr><td>P2 把 {@code clear()} 只挪到 {@code if} 之外、但仍在重建点之后</td>
     *       <td>此处早退已 return ⇒ 仍原样</td>
     *       <td>绿（<b>本测试抓不到</b>，由 {@link #missingWavesSectionKeepsConstructorWaves} 补）</td></tr>
     * </table>
     *
     * <p><b>必须与 {@link #missingWavesSectionKeepsConstructorWaves} 成对。</b>
     * 本测试覆盖「早退分支」，那一条覆盖「{@code waveSection == null} 分叉」；
     * 只留本测试会漏掉「{@code clear()} 挪出 {@code if} 但在重建点之后」这半个变异 ——
     * 而那条路径上文件是**可用**的，早退根本不会发生喵。</p>
     *
     * <p>后果为什么比原缺陷重：这条路上「管理员在游戏里改了波次但还没保存」与
     * 「磁盘文件被写坏」会同时发生，清空等于把**唯一一份**还在的数据也丢掉喵。</p>
     */
    @Test
    @DisplayName("P2 降级路径: game-settings.yml 读不到时不得清空内存 waves（clear 必须在可用性守卫之后）")
    void unusableFileKeepsInMemoryWaves() throws Exception {
        BareHost host = loadBareHost();
        // 第一步必须是一次**成功**的载入：要拿到构造器预置的 1/5/10（出厂模板也是这三条）。
        ConfigManager config = new ConfigManager(host);

        Map<Integer, GameConfig.WaveConfig> wavesBefore = config.getActiveConfig().getWaves();
        GameConfig.WaveConfig wave1Before = wavesBefore.get(1);
        assertNotNull(wave1Before, "前置条件：出厂模板应已载入 waves.1");
        int countBefore = wave1Before.getTotalCount();
        assertTrue(countBefore > 0, "前置条件：waves.1 的 compositions 应有内容（实际 " + countBefore + "）");

        // 第二步把文件打成 0 字节。空文件是「截断写」最典型的产物，YamlFiles 判 SUSPECT
        // （YamlFiles:88-90）→ quarantine() 把它改名成 game-settings.yml.corrupt → 闸门只读。
        // 随后 reset() 再 loadFromFile()：load() 总会重新读盘并重判一次（YamlFileGate:52-62
        // 没有 short-circuit），reset() 只是清掉可能残留的旧锁，与 ConfigManagerTest:74-80 同款手法喵。
        MockBukkitTestBase.TestPaths.write(settingsFile(host), "");
        config.getSettingsGate().reset();
        config.loadFromFile();

        // 前置条件（必须先证明「降级路真的被走到了」，否则后面三条断言可能绿在一条没执行的分支上）
        assertTrue(config.getSettingsGate().isReadOnly(),
                "前置条件：0 字节文件必须让 settings 闸门只读（YamlFiles 的空文件分支 → quarantine）");
        assertFalse(settingsFile(host).exists(),
                "前置条件：不可用文件必须已被隔离成 *.corrupt（否则后面的 loadFromFile 走的不是降级分支）");

        Map<Integer, GameConfig.WaveConfig> wavesAfter = config.getActiveConfig().getWaves();

        assertNotNull(wavesAfter.get(1),
                "★ 文件不可用时语义是「读不到就不动内存」（ConfigManager:1246-1254 的提前 return）。"
                        + " clear() 一旦被挪出 if (waveSection != null)、或放进 loadFromFile 开头，"
                        + " 这条路会把管理员尚未保存的整张波次表清空 —— 磁盘文件刚被判坏，"
                        + " 内存是唯一一份还在的数据，清掉即全丢。"
                        + " 实际键集=" + wavesAfter.keySet());
        assertNotNull(wavesAfter.get(5),
                "★ waves.5 同样必须原样保留（1/5/10 是 GameConfig 构造器的预置键）。"
                        + " 实际键集=" + wavesAfter.keySet());
        assertNotNull(wavesAfter.get(10),
                "★ waves.10 同样必须原样保留（1/5/10 是 GameConfig 构造器的预置键）。"
                        + " 实际键集=" + wavesAfter.keySet());

        assertEquals(Set.of(1, 5, 10), wavesAfter.keySet(),
                "★ 降级路径下内存 waves 必须与降级前完全一致（不多不少），"
                        + " 不能出现「清空后又被其它路径补回一部分」的半残状态。"
                        + " 实际键集=" + wavesAfter.keySet());

        assertEquals(countBefore, wavesAfter.get(1).getTotalCount(),
                "内存里的波次内容必须原样保留，不能只剩一个空壳："
                        + " getTotalCount() 从 " + countBefore + " 变成 0 意味着 compositions 被清空，"
                        + " 之后整波会回落 spawnDefaultWave（5+2×波数 只原版僵尸）喵");
    }

    // ============================================================ ★ 守 clear() 的位置（第二半）

    /**
     * 文件**可用**但整段 {@code waves} 缺失时，内存里构造器预置的 1/5/10 同样必须还在。
     *
     * <p><b>为什么必须有这一条 —— 它守的是上一条抓不到的那半个变异喵。</b></p>
     *
     * <p>上一条（{@link #unusableFileKeepsInMemoryWaves}）只覆盖「文件不可用 → 早退分支」。
     * 但 {@code loadFromFile} 的重建点是 {@code activeConfig = new GameConfig()}（{@code :1272}），
     * 它在早退分支**之后**。所以把 {@code clear()} 挪到
     * {@code if (waveSection != null)} 之外、却仍在重建点之后时，不可用路径照样先 return，
     * 上一条**依然绿** —— 位置错误漏网喵。</p>
     *
     * <p>这一条用「文件完全可用、只是没有 {@code waves} 段」把那条路径逼出来：
     * 此时早退分支不成立，执行流一定会走到 {@code waveSection == null} 的分叉，
     * 于是 {@code clear()} 在内还是在外立刻可分喵。</p>
     *
     * <table border="1">
     *   <caption>{@code waves} 段缺失时的内存 waves</caption>
     *   <tr><th>实施情况</th><th>内存 waves</th><th>本测试</th></tr>
     *   <tr><td>P2 正确实施（{@code clear()} 在 {@code if (waveSection != null)} 内）</td>
     *       <td>{@code {1, 5, 10}} 原样</td><td>绿</td></tr>
     *   <tr><td>P2 把 {@code clear()} 挪到 {@code if} 之外（重建点之后）</td>
     *       <td>空</td><td><b>红</b>（键集断言）</td></tr>
     *   <tr><td>P2 把 {@code clear()} 挪到重建点之前（含早退之前）</td>
     *       <td>空</td><td><b>红</b>（键集断言；上一条也会红）</td></tr>
     * </table>
     *
     * <p>语义依据：{@code round5-21} 边界 6(b) 明写「整段 {@code waves} 删除（只能手改）⇒ 不清、
     * 保留构造器 {@code M{1,5,10}}，下一次保存把 1/5/10 写回磁盘（= 恢复出厂）」。
     * 所以「段缺失」是**刻意保留**的兜底，不是「磁盘为空所以该清空」喵。</p>
     */
    @Test
    @DisplayName("P2 段缺失: 文件可用但没有 waves 段时，内存 waves 必须保留构造器预置（不得清空）")
    void missingWavesSectionKeepsConstructorWaves() throws Exception {
        BareHost host = loadBareHost();
        // 4 个已知顶层键 ⇒ 过 gameSettingsValidator（≥3），所以这是「文件完全可用」的夹具。
        // 刻意不写 waves 段：模拟管理员把整段 waves 删掉（只能手改，GUI 逼不出这种形态）。
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings());

        ConfigManager config = new ConfigManager(host);

        // 前置条件：证明文件真的被当作可用配置读进来了。若这里红，说明夹具本身没通过哨兵，
        // 后面的断言就绿/红在一个与主题无关的分支上喵。
        assertFalse(config.getSettingsGate().isReadOnly(),
                "前置条件：4 个已知顶层键的文件必须被判可用（只读则说明夹具没过 gameSettingsValidator）");
        assertTrue(settingsFile(host).exists(),
                "前置条件：可用的文件不得被隔离（0 字节/校验失败才隔离）");

        Map<Integer, GameConfig.WaveConfig> waves = config.getActiveConfig().getWaves();

        assertEquals(Set.of(1, 5, 10), waves.keySet(),
                "★ waves 段缺失时语义是「保留构造器预置的三波」（round5-21 边界 6(b)："
                        + "删掉整段 waves = 恢复出厂三波）。clear() 若被挪到 "
                        + "if (waveSection != null) 之外，这条路径会把 1/5/10 全部清空 ⇒"
                        + " getWaveConfig(n) 回落 defaultWave ⇒ 全场所有波次刷同一个固定编成，"
                        + "而配置里明明什么都没写错。实际键集=" + waves.keySet());
        assertNotNull(waves.get(1), "waves.1 必须原样保留（实际键集=" + waves.keySet() + "）");
        assertNotNull(waves.get(5), "waves.5 必须原样保留（实际键集=" + waves.keySet() + "）");
        assertNotNull(waves.get(10), "waves.10 必须原样保留（实际键集=" + waves.keySet() + "）");
        assertTrue(waves.get(1).getTotalCount() > 0,
                "保留的必须是**有内容**的预置波次，不能是空壳（GameConfig:243-245 预置 beetle 6 + hunter 2）");
    }
}
