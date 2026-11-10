package com.hilltopvillage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * round5 N2 —— <b>波次删除记账（{@code removedWaveKeys}）的寿命必须限于它那一次
 * {@code game-settings.yml} 写盘</b>。
 *
 * <h3>缺陷原貌</h3>
 *
 * <p>{@code saveToFile()} 把 {@code removedWaveKeys.clear()} 放在「monsters 也写成功」的分支里
 * （F-001 的门控），于是「settings 已落盘、墓碑仍存活」这个中间态可以跨过一次完整保存。
 * 而墓碑的判据读的是<b>当前内存</b>（{@code !activeConfig.getWaves().containsKey(n)}），
 * 一旦中间发生 {@code loadFromFile()} / {@code revertAll()} 这类<b>整份替换内存</b>的操作
 * 且不维护墓碑，判据就可能反向翻转。</p>
 *
 * <h3>P2 的影响判定（本类的前提）</h3>
 *
 * <p><b>P2（加载侧 {@code getWaves().clear()} + 按磁盘键集重建）已经把 N2 的 reload /
 * restart 主路径结构性修好了。</b>删波次 → 部分保存失败 → {@code loadFromFile()} 时，
 * 磁盘上没有该键 ⇒ 重建后内存也没有 ⇒ 判据根本不会翻转。因此「reload 后不复活」这条只能做
 * <b>回归守卫</b>（修前修后都绿），不是判别性用例 ——
 * {@link #deletedWaveStaysGoneAcrossReloadAndSave()} 以这个身份存在，注释里写明。
 * 唯一残留面是「磁盘文件整个没有 {@code waves:} 段」（{@code waveSection == null} 时
 * {@code clear()} 不执行），但 {@code saveToFile()} 无条件写 {@code waves.default.total-mobs}，
 * 所以一旦保存过就不可能命中。</p>
 *
 * <h3>那还有什么是判别性的</h3>
 *
 * <p>墓碑被延长寿命后，它的删除动作会越界到<b>下一次写盘</b>，而那次写盘面对的磁盘可能已不是
 * 它删除时的那份文件（管理员手改、其它进程写入）。
 * {@link #tombstoneDoesNotOutliveItsOwnWrite()} 正是这条：第一次保存（monsters 失败）后管理员
 * 手写把 {@code waves.1} 补回磁盘，第二次保存时修前版本会把这条<b>手写</b>波次静默删掉
 * （墓碑还活着、判据为 true），修后版本不会（墓碑在 settings 写成功那一刻已兑现并清空）。
 * 这与源码注释承诺的「不碰管理员手写进文件、内存里从未存在过的其它键」直接对应，也给出
 * 「红 → 绿」的判别力。</p>
 *
 * <p>{@link #failureBranchNoLongerPromisesWaveRetry()} 钉住同一处改动的另一半：失败分支的 warning
 * 原先承诺「keeping the pending wave deletions so the next save retries them」，而墓碑提前清空后
 * 这句承诺必为假，文案必须同步修正（只承诺「未保存状态」重试）。</p>
 *
 * <h3>夹具</h3>
 *
 * <p>「settings 写成功、monsters 写失败」用真实闸门制造（与
 * {@code ConfigManagerTest#saveToFileDoesNotAdvanceSnapshotWhenMonstersSaveFails} 同一套：
 * 把 monsters-config.yml 打成 0 字节 → 闸门 reset + 重读 → 只读闩落下），不依赖反射或 mock。</p>
 */
class WaveTombstoneLifetimeTest extends MockBukkitTestBase {

    /** 出厂预置波次号：构造器无条件放 1 / 5 / 10，因此「删除后是否复活 / 是否被越界删掉」对它最有意义。 */
    private static final int FACTORY_WAVE = 1;

    private static File settingsFile(BareHost host) {
        return new File(host.getDataFolder(), "game-settings.yml");
    }

    private static File monstersFile(BareHost host) {
        return new File(host.getDataFolder(), "monsters-config.yml");
    }

    /** 直接读磁盘上的 waves 段（整数键在 YAML 里是带引号的字符串，必须走 section）。 */
    private static ConfigurationSection diskWaves(BareHost host) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(settingsFile(host)));
        return yaml.getConfigurationSection("waves");
    }

    /**
     * 造出「settings 可写、monsters 只读」的部分保存失败态。
     *
     * <p>顺序不可换：先把文件打成 0 字节，再 {@code reset()} + 重读，只读闩才会真的落下
     * （构造期已经读过一次正常文件，不重读则闸门仍可写）。</p>
     */
    private static ConfigManager withUnwritableMonsters(BareHost host) throws Exception {
        ConfigManager config = new ConfigManager(host);
        MockBukkitTestBase.TestPaths.write(monstersFile(host), "");
        config.getMonstersGate().reset();
        assertFalse(config.getMonstersGate().load().usable(),
                "前置条件：0 字节的 monsters-config.yml 必须被判不可用");
        assertTrue(config.getMonstersGate().isReadOnly(),
                "前置条件：闸门必须已锁定为只读，否则 monsters 侧照常写成功，测不到 F-001 的中间态");
        return config;
    }

    /** 用 YamlConfiguration 改一个键再整份写回（测试夹具，不走生产写盘路径）。 */
    private static void handEdit(File file, String path, Object value) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(file));
        yaml.set(path, value);
        MockBukkitTestBase.TestPaths.write(file, yaml.saveToString());
        // 明确把 mtime 推到未来，确保下一次保存的「外部修改检测」真的介入
        // （同一秒内的两次写盘在毫秒粒度上可能相等，那样会静默走成普通写回路径）。
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 5_000L));
    }

    // ---------------------------------------------------------------- ① 回归守卫（修前修后都绿）

    @Test
    @DisplayName("回归守卫（P2 已结构性修好）：删波次 → 部分保存失败 → reload → 再保存，磁盘仍无 waves.1")
    void deletedWaveStaysGoneAcrossReloadAndSave() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = withUnwritableMonsters(host);

        ConfigurationSection before = diskWaves(host);
        assertNotNull(before, "前置条件：出厂模板应有 waves 段");
        assertNotNull(before.getConfigurationSection(String.valueOf(FACTORY_WAVE)),
                "前置条件：出厂波次 1 应已在磁盘上");

        config.removeWave(FACTORY_WAVE);
        assertFalse(config.saveToFile(),
                "前置条件：monsters 只读 ⇒ saveToFile 必须返回 false（这正是 N2 需要的部分失败态）");
        assertEquals(null, diskWaves(host).getConfigurationSection(String.valueOf(FACTORY_WAVE)),
                "前置条件：墓碑必须已经把磁盘上的 waves.1 清掉");

        // 「重启 / reload」等价物：内存按磁盘重建（P2 的 clear() + 重建）。
        config.loadFromFile();
        assertFalse(config.getActiveConfig().getWaves().containsKey(FACTORY_WAVE),
                "★ P2 之后 reload 不再复活出厂波次 1 —— 磁盘上没有该键，重建后的内存键集等于磁盘键集。"
                        + " 若这里为 true，说明 P2 的 clear() 失效，N2 的复活前提重新成立");

        config.saveToFile();

        ConfigurationSection after = diskWaves(host);
        assertNotNull(after, "waves 段必须仍在（墓碑只删单个键，不整段清空）");
        assertEquals(null, after.getConfigurationSection(String.valueOf(FACTORY_WAVE)),
                "★ 用户可见保证：删掉的波次在 reload 之后的那次保存里不得被写回磁盘。"
                        + " 修前修后都应成立（P2 之后内存里根本没有该波次可写）；本用例是回归守卫，"
                        + " 判别力在下面两条");
        assertNotNull(after.getConfigurationSection("5"),
                "对照组：未被删除的出厂波次 5 必须原样留在磁盘上，否则「1 不在」可能只是整份配置写失败");
    }

    // ---------------------------------------------------------------- ② 判别性：墓碑不得越界到下一次写盘

    @Test
    @DisplayName("★ 判别性：墓碑只服务于它那次写盘 —— 管理员事后手写补回的 waves.1 不得被下一次保存吃掉")
    void tombstoneDoesNotOutliveItsOwnWrite() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = withUnwritableMonsters(host);

        // 第 1 步：GUI 删波次 1 → 保存（settings 成功、monsters 失败）。
        config.removeWave(FACTORY_WAVE);
        assertFalse(config.saveToFile(), "前置条件：这是部分保存失败态");
        assertEquals(null, diskWaves(host).getConfigurationSection(String.valueOf(FACTORY_WAVE)),
                "前置条件：第一次保存应已把 waves.1 从磁盘清掉");

        // 第 2 步：模拟管理员事后手改文件，把 waves.1 手写补回（内存里依然没有它）。
        handEdit(settingsFile(host), "waves." + FACTORY_WAVE + ".total-mobs", 42);

        // 第 3 步：再保存一次。
        config.saveToFile();

        ConfigurationSection after = diskWaves(host);
        assertNotNull(after, "waves 段必须仍在");
        ConfigurationSection wave1 = after.getConfigurationSection(String.valueOf(FACTORY_WAVE));
        assertNotNull(wave1,
                "★ 管理员手写补回的 waves.1 必须保留。修前版本在这里红：墓碑在上一次部分失败后"
                        + "从未被清空（F-001 的 monsters 门控），判据「此刻内存里没有 1」仍为 true，"
                        + "于是这一次写盘把管理员手写的波次一并 set(null) 掉 —— 墓碑越界到了它不该管的那次写盘，"
                        + "与源码注释承诺的「不碰管理员手写进文件、内存里从未存在过的其它键」直接矛盾。"
                        + " 修后版本：墓碑在 settings 写成功那一刻已兑现并清空，本次不再删除任何键");
        assertEquals(42, wave1.getInt("total-mobs"),
                "不但要留着，而且必须是手写的那个值（被写回内存默认值同样是错的）");
    }

    // ---------------------------------------------------------------- ③ 判别性：失败分支的日志必须诚实

    @Test
    @DisplayName("★ 判别性：部分失败分支的 warning 不得再承诺「下次保存会重试波次删除」")
    void failureBranchNoLongerPromisesWaveRetry() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = withUnwritableMonsters(host);

        config.removeWave(FACTORY_WAVE);

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        assertFalse(config.saveToFile(), "前置条件：本用例需要部分保存失败态");

        assertTrue(logs.anyContains("monsters-config.yml was NOT saved"),
                "失败必须留下一条 warning，实际日志：" + logs.lines());
        assertFalse(logs.anyContains("pending wave deletions"),
                "★ 墓碑已在 settings 写成功时兑现，这句话从此为假：修前版本在这里红"
                        + "（原文案是「keeping the unsaved-changes state and pending wave deletions "
                        + "so the next save retries them」，承诺了一次不会发生的波次删除重试）。"
                        + " 实际日志：" + logs.lines());
        assertTrue(logs.anyContains("retries it"),
                "但仍然要说明「未保存状态」会被下次保存重试 —— 直接删掉整句会让运维以为连重试都没有了。"
                        + " 实际日志：" + logs.lines());
    }
}
