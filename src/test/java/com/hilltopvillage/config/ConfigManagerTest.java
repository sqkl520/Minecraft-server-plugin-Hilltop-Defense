package com.hilltopvillage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link ConfigManager} 里三条最容易静默回归的语义。
 *
 * <p>这个类刻意只覆盖 <b>round1 修过的三处</b>，不追求行覆盖率：
 *
 * <ol>
 *   <li><b>{@code saveToFile()} 的快照门控</b> —— 只有 {@code monsters-config.yml} 真的落盘，
 *       「已保存」快照才推进。若两份文件里有一份被只读降级，却照样推进快照，
 *       那么这一批改动<b>永远不会重试</b>：{@code hasUnsavedChanges()} 已经是 false，
 *       保存按钮不亮，重启后数据丢失且零日志。</li>
 *   <li><b>{@code loadFromFile()} 用磁盘重建内存</b> —— 旧实现只做「磁盘值覆盖内存」，
 *       于是磁盘上被删掉的键在内存里依然存活，下一次保存原样写回（键复活）。</li>
 *   <li><b>waves 段的未知键告警</b> —— 非数字波次键曾落进
 *       {@code catch (NumberFormatException ignored) {}} 零输出，管理员手滑写成
 *       {@code wave5:} 后只能靠「波次没变」猜。</li>
 * </ol>
 *
 * <p>用 {@code BareHost}：构造器只要 {@code getDataFolder()} / {@code getLogger()} /
 * {@code saveResource()}，不碰世界与调度器。单次构造约 1 秒，故本类用例数刻意收敛。
 */
class ConfigManagerTest extends MockBukkitTestBase {

    private static File settingsFile(BareHost host) {
        return new File(host.getDataFolder(), "game-settings.yml");
    }

    private static File monstersFile(BareHost host) {
        return new File(host.getDataFolder(), "monsters-config.yml");
    }

    /**
     * 把出厂模板释放到 host 的数据目录（{@code ConfigManager} 的构造器也会做同样的事，
     * 但下面几条用例要在<b>构造之前</b>改文件，所以必须自己先放一份出来）。
     *
     * <p>踩过的坑：直接 {@code TestPaths.read(settingsFile(host))} 得到空串 —— 因为此时
     * {@code saveResource()} 还没跑过。空串写回去会让文件只剩我补的那一个键，
     * {@code gameSettingsValidator}（要求 ≥3 个已知顶层键）判 SUSPECT → 整份文件被隔离 →
     * 被测的加载路径整个没跑到，测试却"绿"在一个无意义的分支上。
     */
    private static void ensureShippedSettings(BareHost host) {
        File file = settingsFile(host);
        if (!file.exists()) {
            host.saveResource("game-settings.yml", false);
        }
    }

    // ---------------------------------------------------------------- ① 快照门控

    @Test
    @DisplayName("saveToFile: monsters-config.yml 不可写时返回 false，且「已保存」快照不推进")
    void saveToFileDoesNotAdvanceSnapshotWhenMonstersSaveFails() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = new ConfigManager(host);

        // 在「载入之后」把 monsters-config.yml 打成 0 字节 —— 只有重新 load 过的闸门才会
        // 判它 SUSPECT。所以这里先破坏文件，再把闸门 reset + 重读一次，让只读闩真的落下。
        File monsters = monstersFile(host);
        MockBukkitTestBase.TestPaths.write(monsters, "");
        config.getMonstersGate().reset();
        assertFalse(config.getMonstersGate().load().usable(),
                "前置条件：0 字节文件必须被判不可用（YamlFiles 的空文件分支）");
        assertTrue(config.getMonstersGate().isReadOnly(),
                "前置条件：闸门必须已锁定为只读");

        // 换一个与出厂值明显不同的胜利波次，制造「未保存」
        config.getActiveConfig().setVictoryWaves(7);
        assertTrue(config.hasUnsavedChanges(), "前置条件：改过配置后应有未保存改动");

        boolean saved = config.saveToFile();

        assertFalse(saved,
                "monsters-config.yml 不可写时 saveToFile() 必须返回 false —— "
                        + "返回 true 会让调用方（GUI 保存按钮 / 命令回执）宣告一个没完成的保存");
        assertTrue(config.hasUnsavedChanges(),
                "★ 关键：monsters 落盘失败时「已保存快照」不得推进。"
                        + " 若推进了，hasUnsavedChanges() 立刻变 false，保存按钮熄灭，"
                        + " 这批改动永远不再重试 —— 重启后静默丢失且零日志");
    }

    @Test
    @DisplayName("saveToFile: 两份都成功时返回 true，快照推进，hasUnsavedChanges 归 false")
    void saveToFileAdvancesSnapshotOnFullSuccess() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = new ConfigManager(host);

        config.getActiveConfig().setVictoryWaves(7);
        assertTrue(config.hasUnsavedChanges());

        assertTrue(config.saveToFile(), "两份文件都可写时应保存成功");
        assertFalse(config.hasUnsavedChanges(),
                "全部落盘后快照必须推进，否则保存按钮永远亮着");
        assertTrue(MockBukkitTestBase.TestPaths.read(settingsFile(host)).contains("victory-waves: 7"),
                "game-settings.yml 应真的写入了新值");
    }

    @Test
    @DisplayName("saveToFile: game-settings.yml 自身不可写时直接 false，不写 monsters")
    void saveToFileRefusesWhenSettingsUnwritable() throws Exception {
        BareHost host = loadBareHost();
        File settings = settingsFile(host);
        MockBukkitTestBase.TestPaths.write(settings, "   \n");   // 空文件 → SUSPECT

        ConfigManager config = new ConfigManager(host);

        assertTrue(config.getSettingsGate().isReadOnly(), "前置条件：空文件应让 settings 闸门只读");
        assertFalse(config.saveToFile(), "只读降级下必须拒绝写盘");
        assertFalse(config.hasUnsavedChanges(),
                "从未读到磁盘内容 → 内存就是唯一权威，且没有待保存的差异");
    }

    // ---------------------------------------------------------------- ② 磁盘重建内存

    @Test
    @DisplayName("loadFromFile: 磁盘上删掉的键不会在内存里复活（用磁盘重建，而不是覆盖）")
    void loadFromFileRebuildsInsteadOfOverlaying() throws Exception {
        BareHost host = loadBareHost();
        ensureShippedSettings(host);
        File settings = settingsFile(host);

        // ① 先把 min-players 改成一个非默认值，让「旧值残留」与「回落到默认」可区分
        int defaultValue = new GameConfig().getMinPlayers();
        int nonDefault = defaultValue + 3;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(settings));
        yaml.set("min-players", nonDefault);
        MockBukkitTestBase.TestPaths.write(settings, yaml.saveToString());

        ConfigManager config = new ConfigManager(host);
        assertEquals(nonDefault, config.getActiveConfig().getMinPlayers(),
                "前置条件：应读到磁盘上的非默认值 " + nonDefault);

        // ② 在游戏外把该键删掉，然后重载。
        //    这一步必须是「重载」而不是「重新 new」—— 构造器本来就会 new 一个 GameConfig，
        //    所以只有二次 loadFromFile() 才能区分「用磁盘重建」与「if(contains) 覆盖」。
        YamlConfiguration trimmed = new YamlConfiguration();
        trimmed.loadFromString(MockBukkitTestBase.TestPaths.read(settings));
        trimmed.set("min-players", null);
        MockBukkitTestBase.TestPaths.write(settings, trimmed.saveToString());

        config.loadFromFile();

        assertEquals(defaultValue, config.getActiveConfig().getMinPlayers(),
                "★ 磁盘上已删掉的键必须回落到 GameConfig 的代码默认值。"
                        + " F-002 之前的实现只做 if(contains) 形式覆盖 —— 键不在就什么都不做，"
                        + " 内存里仍是旧值 " + nonDefault + "，接着任何一次 saveToFile() 都会把键写回（键复活）");
    }

    @Test
    @DisplayName("loadFromFile: 磁盘上存在的自建波次会按磁盘内容重建（键集与值都归磁盘，不靠 carry-over）")
    void loadFromFileRebuildsAdminCreatedWaveFromDisk() {
        BareHost host = loadBareHost();
        ConfigManager config = new ConfigManager(host);

        // 模拟 GUI 新建波次 42：内存里放一个，然后落盘
        GameConfig.WaveConfig wave42 = new GameConfig.WaveConfig(77, new LinkedHashMap<>());
        config.getActiveConfig().getWaves().put(42, wave42);
        assertTrue(config.saveToFile(), "落盘应成功");

        // reload：clear() 会把内存里的波次表（含构造器预置的 1/5/10 与自建的 42）全部清掉，
        // 再由磁盘上的数字键逐个新建 —— 磁盘上有 waves.42，所以 42 必须被建回来。
        config.loadFromFile();

        GameConfig.WaveConfig reloaded = config.getActiveConfig().getWaves().get(42);
        assertNotNull(reloaded,
                "★ 磁盘上确实存在 waves.42，reload 后必须还在。"
                        + " 「磁盘重建」先清空内存波次表，靠的是磁盘上有这个键把它建回来 ——"
                        + " 磁盘上没有的自建波次（删了但没保存）会在 reload 后消失，这是刻意的");
        assertEquals(77, reloaded.getTotalMobs(), "重建必须带上磁盘上的原值 77，而不是只留一个空壳");
    }

    @Test
    @DisplayName("removeWave + saveToFile: 被删的波次键真的从磁盘上消失（墓碑）")
    void removeWaveTombstonesTheKeyOnDisk() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = new ConfigManager(host);

        config.getActiveConfig().getWaves().put(42, new GameConfig.WaveConfig(77, new LinkedHashMap<>()));
        assertTrue(config.saveToFile());

        // 注意：YamlConfiguration 把整数键写成带引号的 '42'，匹配 "42:" 会假阴性
        assertNotNull(wavesSection(settingsFile(host)).getConfigurationSection("42"),
                "前置条件：waves.42 应已落盘");

        config.removeWave(42);
        assertTrue(config.saveToFile());

        assertNull(wavesSection(settingsFile(host)).getConfigurationSection("42"),
                "GUI 删掉的波次必须从磁盘清除。saveToFile 对内存里存在的键逐个写值、"
                        + "从不整段清空，所以删除必须靠 removedWaveKeys 墓碑——否则重启后波次复活");
        assertNotNull(wavesSection(settingsFile(host)).getConfigurationSection("1"),
                "墓碑只能清掉删过的键，其它波次必须原样保留");
    }

    @Test
    @DisplayName("removeWave: 同号波次被重新创建时，保存不会把它误删")
    void removeWaveThenRecreateSurvivesSave() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = new ConfigManager(host);

        config.getActiveConfig().getWaves().put(42, new GameConfig.WaveConfig(77, new LinkedHashMap<>()));
        config.saveToFile();

        config.removeWave(42);
        config.getActiveConfig().getWaves().put(42, new GameConfig.WaveConfig(99, new LinkedHashMap<>()));
        assertTrue(config.saveToFile());

        org.bukkit.configuration.ConfigurationSection wave42 =
                wavesSection(settingsFile(host)).getConfigurationSection("42");
        assertNotNull(wave42, "重新创建的波次不得被墓碑误删");
        assertEquals(99, wave42.getInt("total-mobs"), "重新创建的波次必须写的是新值");
    }

    // ---------------------------------------------------------------- ②b loadFromFile 返回值（#8）

    @Test
    @DisplayName("loadFromFile: 成功读到磁盘时返回 true（调用方才有资格宣告「已重载」）")
    void loadFromFileReturnsTrueOnSuccess() {
        BareHost host = loadBareHost();
        ensureShippedSettings(host);
        ConfigManager config = new ConfigManager(host);

        assertTrue(config.loadFromFile(),
                "出厂模板可用时必须返回 true —— 否则修好的服务器会被永久播报「未重载」");
        assertFalse(config.getSettingsGate().isReadOnly(),
                "成功读盘后闸门不应处于只读态（OK 会解闩）");
    }

    @Test
    @DisplayName("loadFromFile: 文件不可用时返回 false，且内存值保持不变（#8 的核心契约）")
    void loadFromFileReturnsFalseAndKeepsMemoryWhenSettingsUnusable() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = new ConfigManager(host);
        // 放一个与出厂值不同的内存值：失败时它必须原样保留。
        config.getActiveConfig().setVictoryWaves(7);

        MockBukkitTestBase.TestPaths.write(settingsFile(host), "   \n");

        assertFalse(config.loadFromFile(),
                "★ game-settings.yml 不可用时 loadFromFile() 必须返回 false —— "
                        + "void 版本让 GUI 与 /hilltop reloadconfig 都无法区分成功/失败");
        assertTrue(config.getSettingsGate().isReadOnly(),
                "前置/结果校验：空文件必须让 settings 闸门锁定只读");
        assertEquals(7, config.getActiveConfig().getVictoryWaves(),
                "失败分支的语义是「读不到就不动内存」：拒绝重载后内存值必须保持 7");
    }

    @Test
    @DisplayName("loadFromFile: 隔离后文件已不存在（ABSENT/usable）仍因只读闩返回 false")
    void loadFromFileReturnsFalseWhenGateIsReadOnlyEvenIfFileIsAbsent() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = new ConfigManager(host);

        // 第一次：空文件 → SUSPECT → 隔离成 *.corrupt → 闸门锁定只读 → false
        MockBukkitTestBase.TestPaths.write(settingsFile(host), "   \n");
        assertFalse(config.loadFromFile(), "前置条件：空文件必须让第一次 loadFromFile() 返回 false");
        assertTrue(config.getSettingsGate().isReadOnly(), "前置条件：隔离后闸门必须锁定只读");

        // 第二次：文件已被挪走，YamlFiles 返回 ABSENT（usable=true）。只判 usable() 的实现
        // 会在这里误判成功并把空配置当权威 —— 必须靠 isReadOnly() 继续拒绝。
        assertFalse(config.getActiveConfig().getWaves().isEmpty(),
                "夹具前提：内存在失败后仍保留构造器预置的波次（证明没有走重建）");
        assertFalse(config.loadFromFile(),
                "★ 文件不存在但闸门只读时仍必须返回 false；"
                        + "这正是 loadFromFile 里 isReadOnly() 那一半条件的回归网");
    }

    @Test
    @DisplayName("loadFromFile: 隔离后文件缺失且闸门被 reset，仍返回 false 而不是拿空配置洗掉内存")
    void loadFromFileReturnsFalseWhenFileIsAbsentEvenAfterGateReset() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = new ConfigManager(host);
        config.getActiveConfig().setVictoryWaves(7);

        // 第一次：空文件 → 隔离成 *.corrupt，文件从此不存在。
        MockBukkitTestBase.TestPaths.write(settingsFile(host), "   \n");
        assertFalse(config.loadFromFile(), "前置条件：空文件必须让第一次 loadFromFile() 返回 false");
        assertFalse(settingsFile(host).exists(), "前置条件：SUSPECT 文件必须已被隔离（原路径不再存在）");

        // /hilltop reloadconfig 的真实顺序：先 reset() 解闩，再 loadFromFile()。
        // 此时 load() 只能得到 ABSENT（usable=true），若只判 usable/isReadOnly 就会漏过去，
        // 用空配置重建内存并返回 true —— 管理员会看到「已重载」，配置却被洗成默认值。
        config.getSettingsGate().reset();

        assertFalse(config.loadFromFile(),
                "★ reset 之后、文件仍缺失时 loadFromFile() 必须返回 false（ABSENT 不等于可重载）");
        assertEquals(7, config.getActiveConfig().getVictoryWaves(),
                "★ 文件缺失时内存值必须保持 7；若被空配置重建，这里会变成出厂默认 20");
    }

    // ---------------------------------------------------------------- ③ 未知键告警

    @Test
    @DisplayName("加载告警: waves 段的非数字键会汇总成一条 warning（不再静默吞掉）")
    void unknownWaveKeysProduceWarning() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  default:\n"
                + "    total-mobs: 10\n"
                + "  1:\n"
                + "    total-mobs: 5\n"
                + "  wave5:\n"                  // ← 拼写错误：应该是 5:
                + "    total-mobs: 3\n");

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        new ConfigManager(host);

        assertTrue(logs.anyContains("unrecognized entries under waves"),
                "waves 段的未知键必须产生 warning，实际日志：" + logs.lines());
        assertTrue(logs.anyContains("wave5"),
                "warning 必须点名那个键 —— 只说「有未知键」管理员仍然找不到：" + logs.lines());
    }

    @Test
    @DisplayName("加载告警: 顶层未知键（拼写错）会汇总成一条 warning")
    void unknownTopLevelKeyProducesWarning() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(settingsFile(host),
                minimalSettings() + "victory-wave: 99\n");   // 少了 s

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        new ConfigManager(host);

        assertTrue(logs.anyContains("unrecognized top-level keys"),
                "顶层拼写错必须产生 warning，实际日志：" + logs.lines());
        assertTrue(logs.anyContains("victory-wave"),
                "warning 必须点名该键：" + logs.lines());
    }

    @Test
    @DisplayName("加载告警: 出厂模板本身零 warning（别把正常配置写成告警）")
    void shippedTemplateLoadsWithoutUnknownKeyWarnings() {
        BareHost host = loadBareHost();

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        new ConfigManager(host);

        assertFalse(logs.anyContains("unrecognized entries under waves"),
                "出厂模板不该有未知 waves 键，实际日志：" + logs.lines());
        assertFalse(logs.anyContains("unrecognized top-level keys"),
                "出厂模板不该有未知顶层键（新增键忘了加进 KNOWN_TOP_LEVEL_KEYS 会立刻红）："
                        + logs.lines());
    }

    // ---------------------------------------------------------------- 归一化

    @Test
    @DisplayName("loadFromFile: wave-timeout-seconds: 120 归一化为 0，并标记有未保存改动")
    void legacyWaveTimeoutIsNormalizedAndMarkedDirty() throws Exception {
        BareHost host = loadBareHost();
        ensureShippedSettings(host);
        File settings = settingsFile(host);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(settings));
        yaml.set("wave-timeout-seconds", 120);
        MockBukkitTestBase.TestPaths.write(settings, yaml.saveToString());

        ConfigManager config = new ConfigManager(host);

        assertEquals(0, config.getActiveConfig().getWaveTimeoutSeconds(),
                "旧的出厂默认值 120 必须归一化为 0（作者要求默认禁用超时保险），"
                        + " 否则存量服务器升级后「改了默认值但老存档不生效」");
        assertTrue(config.hasUnsavedChanges(),
                "归一化改了内存值但磁盘仍是 120 → 必须标记有未保存改动（loadTimeNormalizationPending），"
                        + " 否则这次升级永远不会被写回磁盘");
    }

    @Test
    @DisplayName("loadFromFile: 其它正数不被归一化（管理员显式设定必须保留）")
    void explicitWaveTimeoutIsPreserved() throws Exception {
        BareHost host = loadBareHost();
        ensureShippedSettings(host);
        File settings = settingsFile(host);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(settings));
        yaml.set("wave-timeout-seconds", 121);
        MockBukkitTestBase.TestPaths.write(settings, yaml.saveToString());

        ConfigManager config = new ConfigManager(host);

        assertEquals(121, config.getActiveConfig().getWaveTimeoutSeconds(),
                "只有恰好等于旧默认 120 才归一化；121 是管理员显式「启用」的意图，必须保留");
        assertFalse(config.hasUnsavedChanges(), "没有归一化发生 → 不该凭空标记未保存");
    }

    @Test
    @DisplayName("loadFromFile: 非法 hammer.material 被拒，保留默认并打 warning")
    void invalidHammerMaterialFallsBackToDefault() throws Exception {
        BareHost host = loadBareHost();
        ensureShippedSettings(host);
        File settings = settingsFile(host);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(settings));
        yaml.set("hammer.material", "NOT_A_REAL_MATERIAL");
        MockBukkitTestBase.TestPaths.write(settings, yaml.saveToString());

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        ConfigManager config = new ConfigManager(host);

        assertEquals(new GameConfig().getHammerMaterial(), config.getActiveConfig().getHammerMaterial(),
                "非法材质必须回落到代码默认，而不是让 createVanillaHammer 的 new ItemStack 抛异常");
        assertTrue(logs.anyContains("Invalid hammer material"),
                "回落必须留日志 —— 静默改写管理员的配置是最难查的一类问题：" + logs.lines());
    }

    @Test
    @DisplayName("loadFromFile: 合法但非 item 的材质（PISTON_HEAD）同样被拒 —— isItem 守卫是必需的")
    void nonItemHammerMaterialIsRejected() throws Exception {
        BareHost host = loadBareHost();
        ensureShippedSettings(host);
        File settings = settingsFile(host);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(settings));
        // PISTON_HEAD / WATER 都是合法 Material 但 isItem() == false。
        // 只判 null 的实现会放行它们，随后 new ItemStack(PISTON_HEAD) 在发锤时抛异常 ——
        // 那不是「配置写错」，是「开局就炸」，且炸在玩家的发锤路径上。
        yaml.set("hammer.material", "PISTON_HEAD");
        MockBukkitTestBase.TestPaths.write(settings, yaml.saveToString());

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        ConfigManager config = new ConfigManager(host);

        assertEquals(new GameConfig().getHammerMaterial(), config.getActiveConfig().getHammerMaterial(),
                "★ 合法但非 item 的材质必须同样被拒（fix6 Y2-05 的 isItem 守卫）。"
                        + " 只判 null 会把 PISTON_HEAD 放进来，之后 new ItemStack(PISTON_HEAD) 抛异常");
        assertTrue(logs.anyContains("Invalid hammer material"),
                "非 item 材质也必须走同一条 warning 分支：" + logs.lines());
    }

    // ---------------------------------------------------------------- 工具

    /** 最小但能过 {@code gameSettingsValidator}（≥3 个已知顶层键）的配置。 */
    private static String minimalSettings() {
        return "world-name: world\n"
                + "min-players: 2\n"
                + "max-players: 6\n"
                + "victory-waves: 20\n";
    }

    /** 用 ConfigurationSection 读 waves —— 磁盘上的整数键是带引号的 {@code '42'}，字符串匹配会假阴性。 */
    private static org.bukkit.configuration.ConfigurationSection wavesSection(File file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(file));
        return yaml.getConfigurationSection("waves");
    }
}
