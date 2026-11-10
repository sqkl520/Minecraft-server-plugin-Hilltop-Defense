package com.hilltopvillage.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * F3 的安全网：**{@code waves.default.compositions} 的键集权威归磁盘**，以及三条守卫
 * （A 段缺失不清空 / B 显式空不清空且告警 / C {@code defaultWave} 为 null 不 NPE）。
 *
 * <p><b>为什么单独一个类</b>：{@link WavesKeySetAlignmentTest} 的类注释写死「只守 P1 的两件事」，
 * {@link WaveKeySetAuthorityTest} 只守 {@code getWaves().clear()} 的存在与落点。F3 动的是另一个字段
 * （{@code GameConfig.defaultWave}，独立于 {@code waves} map），红了必须一眼看出是哪条契约破了喵。</p>
 *
 * <p><b>取数纪律</b>：一律 {@code getActiveConfig().getDefaultWave()}，<b>零处 {@code getWaveConfig(n)}</b> ——
 * 后者是 {@code getOrDefault(waveNumber, defaultWave)}，会把「defaultWave 丢了」伪装成「拿到了别的波次」喵。</p>
 *
 * <p><b>夹具纪律</b>：手写文件必须发生在 {@code new ConfigManager(host)} <b>之前</b>
 * （{@code initSettingsFile()} 只在 {@code !exists} 时 {@code saveResource}）；
 * 且必须含 ≥3 个已知顶层键，否则 {@code YamlFiles} 的 {@code gameSettingsValidator} 判 SUSPECT 并隔离，
 * 整条载入走降级分支喵。</p>
 */
class DefaultWaveAlignmentTest extends MockBukkitTestBase {

    // ============================================================ 夹具

    private static File settingsFile(BareHost host) {
        return new File(host.getDataFolder(), "game-settings.yml");
    }

    /** 4 个已知顶层键 ⇒ 过 {@code gameSettingsValidator} 的 ≥3 门槛。 */
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

    /** null 安全地取某个怪种的 weight；缺失返回 -1。 */
    private static int weightOf(GameConfig.WaveConfig wave, String mobKey) {
        GameConfig.MobComposition comp = wave.getCompositions().get(mobKey);
        return comp == null ? -1 : comp.getWeight();
    }

    /** 构造器预置的 default 配比键集（{@code GameConfig:238-241}）。 */
    private static final Set<String> FACTORY_DEFAULT_KEYS =
            Set.of("explode-beetle", "hook-claw-hunter", "flying-dropper");

    /** 未知怪种告警的稳定片段（与生产代码文案前缀严格一致）。 */
    private static final String UNKNOWN_COMPOSITION_FRAGMENT =
            "neither a monsters-section key nor a spawnable vanilla entity type";

    // ============================================================ T1 删除不复活

    @Test
    @DisplayName("F3-T1: 磁盘删掉某条 default composition → reload 后真的消失（不得被构造器预置复活）")
    void removedDefaultCompositionDoesNotResurrect() throws Exception {
        BareHost host = loadBareHost();
        // 故意不写 flying-dropper：它在构造器预置里（GameConfig:241），旧实现会让它复活。
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  default:\n"
                + "    total-mobs: 10\n"
                + "    compositions:\n"
                + "      explode-beetle:\n"
                + "        count: 4\n"
                + "        weight: 3\n"
                + "      hook-claw-hunter:\n"
                + "        count: 3\n"
                + "        weight: 2\n");

        ConfigManager config = new ConfigManager(host);
        GameConfig.WaveConfig defaultWave = config.getActiveConfig().getDefaultWave();
        assertNotNull(defaultWave, "defaultWave 不得为 null（构造器必预置，守卫 C 兜底）");
        Map<String, GameConfig.MobComposition> comps = defaultWave.getCompositions();

        // 键集断言放第一条：fail-fast，失败信息直接给病因
        assertEquals(Set.of("explode-beetle", "hook-claw-hunter"), comps.keySet(),
                "★ defaultWave.compositions 的键集必须严格等于磁盘上的可用键集。"
                        + " 多出 flying-dropper 说明旧实现「只对内存里已有的怪种赋值」——"
                        + " 磁盘删掉的怪种被构造器预置（GameConfig:238-241）复活，"
                        + " 接着任何一次 saveToFile() 都会把它写回磁盘（删除永久无效）。"
                        + " 空集则是「清了但没建」的另一个方向。实际键集=" + comps.keySet());

        assertAll("磁盘两条配比（beetle 4·3 / hunter 3·2），total-mobs 10",
                () -> assertEquals(-1, countOf(defaultWave, "flying-dropper"),
                        "★ flying-dropper 必须真的不存在（-1）。>=0 就是删除复活"),
                () -> assertEquals(4, countOf(defaultWave, "explode-beetle"), "count 必须来自磁盘的 4"),
                () -> assertEquals(3, weightOf(defaultWave, "explode-beetle"), "weight 必须来自磁盘的 3"),
                () -> assertEquals(3, countOf(defaultWave, "hook-claw-hunter"), "count 必须来自磁盘的 3"),
                () -> assertEquals(2, weightOf(defaultWave, "hook-claw-hunter"), "weight 必须来自磁盘的 2"),
                () -> assertEquals(7, defaultWave.getTotalCount(), "getTotalCount() = 4+3 = 7（复活时会变 10）"),
                () -> assertEquals(5, defaultWave.getTotalWeight(), "getTotalWeight() = 3+2 = 5"),
                () -> assertEquals(10, defaultWave.getTotalMobs(), "total-mobs 必须来自磁盘的 10"));
    }

    // ============================================================ T2 新增生效

    @Test
    @DisplayName("F3-T2: 磁盘给 default 新增第 4 种怪 → reload 后生效，且不得被报成拼写错误")
    void addedDefaultCompositionTakesEffect() throws Exception {
        BareHost host = loadBareHost();
        // zombie 是合法可生成的 EntityType（P1 的 isSpawnableMobKey 已接受，见
        // WavesKeySetAlignmentTest#vanillaEntityTypeCompositionIsAccepted）。
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  default:\n"
                + "    total-mobs: 10\n"
                + "    compositions:\n"
                + "      explode-beetle:\n"
                + "        count: 4\n"
                + "        weight: 3\n"
                + "      hook-claw-hunter:\n"
                + "        count: 3\n"
                + "        weight: 2\n"
                + "      zombie:\n"
                + "        count: 2\n"
                + "        weight: 2\n");

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        ConfigManager config = new ConfigManager(host);
        GameConfig.WaveConfig defaultWave = config.getActiveConfig().getDefaultWave();
        Map<String, GameConfig.MobComposition> comps = defaultWave.getCompositions();

        assertEquals(Set.of("explode-beetle", "hook-claw-hunter", "zombie"), comps.keySet(),
                "★ 磁盘上的第 4 个怪种必须生效。旧实现在 comp == null 时记 unknown 后 continue，"
                        + " 于是写对的 zombie 既不生效、还被打成拼写错误（假 warning）。"
                        + " 实际键集=" + comps.keySet());

        assertAll("新增 zombie 2·2 生效",
                () -> assertEquals(2, countOf(defaultWave, "zombie"),
                        "zombie.count 必须来自磁盘的 2（-1 表示该键被判未知丢弃）"),
                () -> assertEquals(2, weightOf(defaultWave, "zombie"), "zombie.weight 必须来自磁盘的 2"),
                () -> assertEquals(9, defaultWave.getTotalCount(), "getTotalCount() = 4+3+2 = 9（新增键未生效时是 7）"),
                () -> assertEquals(7, defaultWave.getTotalWeight(), "getTotalWeight() = 3+2+2 = 7"),
                () -> assertFalse(logs.anyContains("default.zombie"),
                        "★ 写对的键不得被点名成拼写错误。出现 default.zombie 说明判据还是「内存里有没有」。"
                                + " 实际日志：" + logs.lines()),
                () -> assertFalse(logs.anyContains(UNKNOWN_COMPOSITION_FRAGMENT),
                        "本夹具所有键都合法，不该出现任何未知怪种告警。实际日志：" + logs.lines()));
    }

    // ============================================================ T3 守卫 A

    @Test
    @DisplayName("F3-T3: waves.default 缺失 compositions 子段 / 整段 waves 缺失 → 构造器预置的 3 条仍在（守卫 A）")
    void missingDefaultSectionKeepsConstructorCompositions() throws Exception {
        BareHost host = loadBareHost();

        // ---- 场景 ①：有 waves.default、有 total-mobs，但没有 compositions 子段
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  default:\n"
                + "    total-mobs: 12\n");
        ConfigManager configA = new ConfigManager(host);
        GameConfig.WaveConfig defaultA = configA.getActiveConfig().getDefaultWave();

        assertAll("场景①：total-mobs 磁盘赢（12），配比保留构造器预置（证明两条守卫互相独立）",
                () -> assertEquals(12, defaultA.getTotalMobs(),
                        "★ 只写 total-mobs 是合法用法（调未配置波次的上限），必须生效。"
                                + " 实际 " + defaultA.getTotalMobs()),
                () -> assertEquals(FACTORY_DEFAULT_KEYS, defaultA.getCompositions().keySet(),
                        "★ 没有 compositions 子段时绝不清空：预置 3 条必须原样保留。"
                                + " 清空的后果是 getTotalWeight()==0 ⇒ 全部未配置波次落进 spawnDefaultWave"
                                + " 的「5+2×波数 只原版僵尸」兜底（victory-waves: 20，模板只定义 1/5/10 ⇒ 16/20 波）。"
                                + " 实际键集=" + defaultA.getCompositions().keySet()),
                () -> assertEquals(4, countOf(defaultA, "explode-beetle"), "预置 beetle count = 4"),
                () -> assertEquals(3, countOf(defaultA, "hook-claw-hunter"), "预置 hunter count = 3"),
                () -> assertEquals(3, countOf(defaultA, "flying-dropper"), "预置 dropper count = 3"),
                () -> assertEquals(6, defaultA.getTotalWeight(), "预置 weight 之和 = 3+2+1 = 6"),
                () -> assertEquals(10, defaultA.getTotalCount(), "预置 count 之和 = 4+3+3 = 10"));

        // ---- 场景 ②：连 waves 段都不存在
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings());
        ConfigManager configB = new ConfigManager(host);
        GameConfig.WaveConfig defaultB = configB.getActiveConfig().getDefaultWave();

        assertAll("场景②：整段 waves 缺失 → 全部等于构造器预置（10 / 3 条）",
                () -> assertEquals(10, defaultB.getTotalMobs(), "缺键时保留构造器预置的上限 10"),
                () -> assertEquals(FACTORY_DEFAULT_KEYS, defaultB.getCompositions().keySet(),
                        "★ 段缺失 = 出厂模板，不是「清空」。实际键集=" + defaultB.getCompositions().keySet()),
                () -> assertEquals(4, countOf(defaultB, "explode-beetle"), "预置 beetle count = 4"),
                () -> assertEquals(3, countOf(defaultB, "hook-claw-hunter"), "预置 hunter count = 3"),
                () -> assertEquals(1, weightOf(defaultB, "flying-dropper"), "预置 dropper weight = 1"),
                () -> assertEquals(6, defaultB.getTotalWeight(), "预置 weight 之和 = 6 > 0（不会僵尸兜底）"));
    }

    // ============================================================ T4 守卫 B

    @Test
    @DisplayName("F3-T4: compositions 显式为空 → 保留出厂配比且告警点名 total-mobs: 0 的正道（守卫 B）")
    void explicitEmptyDefaultCompositionsKeepsFactoryTemplateAndWarns() throws Exception {
        BareHost host = loadBareHost();
        // compositions: {} —— 真正的映射（空），getConfigurationSection 返回非 null、getKeys == []
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  default:\n"
                + "    total-mobs: 10\n"
                + "    compositions: {}\n");

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        ConfigManager config = new ConfigManager(host);
        GameConfig.WaveConfig defaultWave = config.getActiveConfig().getDefaultWave();

        assertAll("守卫 B：显式空不清空 + 一条点名正道的告警",
                () -> assertEquals(FACTORY_DEFAULT_KEYS, defaultWave.getCompositions().keySet(),
                        "★ 显式空（{}）不得清空配比。实际键集=" + defaultWave.getCompositions().keySet()),
                () -> assertEquals(6, defaultWave.getTotalWeight(),
                        "★ 核心断言：getTotalWeight() 必须 > 0。为 0 时 WaveManager 的 totalWeight <= 0 分支"
                                + " 会把每一波都换成「5+2×波数 只原版僵尸」"),
                () -> assertEquals(10, defaultWave.getTotalCount(), "getTotalCount() 必须 > 0"),
                () -> assertTrue(logs.anyContains("'waves.default.compositions' has no usable entries"),
                        "★ 守卫 B 的另一半是可见性：静默保留会让管理员以为「改动生效了」。"
                                + " 实际日志：" + logs.lines()),
                () -> assertTrue(logs.anyContains("total-mobs: 0"),
                        "★ 告警必须给出正道 waves.default.total-mobs: 0，否则管理员只会换个地方卡住。"
                                + " 实际日志：" + logs.lines()));
    }

    // ============================================================ T5 total-mobs 类型判别

    @Test
    @DisplayName("F3-T5: default.total-mobs 是字符串 / 负数时不得静默变 0，必须回落并告警")
    void defaultTotalMobsStringAndNegativeAreReportedNotSilentlyZero() throws Exception {
        BareHost host = loadBareHost();

        // ---- 场景 ①：带引号的字符串（YAML 读成 String，裸 getInt 会返回 0）
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  default:\n"
                + "    total-mobs: '12'\n");
        MockBukkitTestBase.LogCapture logsA = attachLogCapture(host.getLogger());
        ConfigManager configA = new ConfigManager(host);
        GameConfig.WaveConfig defaultA = configA.getActiveConfig().getDefaultWave();

        assertAll("场景①：total-mobs: '12'（字符串）",
                () -> assertEquals(10, defaultA.getTotalMobs(),
                        "★ 非数值必须回落**当前值** 10，而不是 0。裸 getInt 对字符串返回 0 ⇒ "
                                + "所有没写 total-mobs 的数字波次在回落 defaultWave 上限时拿到 0 ⇒ 整局零刷怪。"
                                + " 实际 " + defaultA.getTotalMobs()),
                () -> assertTrue(logsA.anyContains("default.total-mobs"),
                        "必须点名是哪个键：" + logsA.lines()),
                () -> assertTrue(logsA.anyContains("is not a number"),
                        "必须说明是「类型不对」而不是静默：" + logsA.lines()),
                () -> assertEquals(6, defaultA.getTotalWeight(), "配比不受影响，仍为预置 6"));

        detachLogCapture();

        // ---- 场景 ②：负数（裸 getInt 原样返回 -3）
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  default:\n"
                + "    total-mobs: -3\n");
        MockBukkitTestBase.LogCapture logsB = attachLogCapture(host.getLogger());
        ConfigManager configB = new ConfigManager(host);
        GameConfig.WaveConfig defaultB = configB.getActiveConfig().getDefaultWave();

        assertAll("场景②：total-mobs: -3（负数）",
                () -> assertEquals(0, defaultB.getTotalMobs(),
                        "★ 负数必须钳到 0（而不是原样 -3）：-3 会让 WaveManager 算出 remaining <= 0，"
                                + " 整局一只不刷且零日志。实际 " + defaultB.getTotalMobs()),
                () -> assertTrue(logsB.anyContains("negative -> 0"),
                        "负数钳制必须打日志，否则手误无人察觉：" + logsB.lines()),
                () -> assertEquals(6, defaultB.getTotalWeight(), "配比不受影响，仍为预置 6"));
    }

    // ============================================================ T6 单条坏键不连坐

    @Test
    @DisplayName("F3-T6: 一条不可生成的怪种只丢自己，不连坐整段（并作为 T2 否定式日志的存活证明）")
    void unknownMobKeyIsDroppedWithWarningButKeepsTheRestUsable() throws Exception {
        BareHost host = loadBareHost();
        // arrow 是合法 EntityType 但不是 Mob 子类：parseSpawnableEntityType 会拒（spawnEntity 会抛）
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  default:\n"
                + "    total-mobs: 10\n"
                + "    compositions:\n"
                + "      explode-beetle:\n"
                + "        count: 4\n"
                + "        weight: 3\n"
                + "      arrow:\n"
                + "        count: 9\n"
                + "        weight: 9\n");

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        ConfigManager config = new ConfigManager(host);
        GameConfig.WaveConfig defaultWave = config.getActiveConfig().getDefaultWave();

        assertAll("丢 1 保 1",
                () -> assertEquals(Set.of("explode-beetle"), defaultWave.getCompositions().keySet(),
                        "★ 只丢不可生成的 arrow，不得因一条坏键放弃整段。"
                                + " 实际键集=" + defaultWave.getCompositions().keySet()),
                () -> assertEquals(-1, countOf(defaultWave, "arrow"), "arrow 必须被拒（-1）"),
                () -> assertEquals(3, defaultWave.getTotalWeight(),
                        "★ 保留的配比必须仍有刷怪依据（3 > 0），不能因一条坏键落进僵尸兜底"),
                () -> assertTrue(logs.anyContains("default.arrow"),
                        "★ 正向存活证明：被拒的键必须被点名。T2 的否定式日志断言靠这条证明串能被打出来。"
                                + " 实际日志：" + logs.lines()));
    }

    // ============================================================ T7 守卫 C

    @Test
    @DisplayName("F3-T7: defaultWave 为 null 时，降级载入路径不得 NPE（守卫 C 必须在方法最前面）")
    void nullDefaultWaveDoesNotNpeOnDegradedLoad() throws Exception {
        BareHost host = loadBareHost();
        // ① 正常载入，拿到出厂 10 / 3 条
        ConfigManager config = new ConfigManager(host);
        assertNotNull(config.getActiveConfig().getDefaultWave(), "前置条件：正常载入后 defaultWave 应非 null");

        // ② 注入 null（GameConfig:777 的 public setter 是唯一入口，正常路径不可达）
        config.getActiveConfig().setDefaultWave(null);

        // ③ 把文件打成 0 字节 → YamlFiles 判 SUSPECT → quarantine → 闸门只读
        MockBukkitTestBase.TestPaths.write(settingsFile(host), "");

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        config.getSettingsGate().reset();
        // ④ 必须走「文件不可用 → 保留内存值并提前 return」分支：那条分支在 return 之前就
        //    activeConfig.snapshot()（GameConfig:645 的 defaultWave.copy() 无 null 保护）
        assertDoesNotThrow(config::loadFromFile,
                "★ 降级路径的 snapshot() 会解引用 defaultWave（GameConfig:645）；守卫 C 若不在"
                        + " loadFromFile 最前面，这里直接 NPE，整次载入收场 —— 而不是「default 配比不对」这种可玩状态");

        assertTrue(config.getSettingsGate().isReadOnly(),
                "前置条件：0 字节文件必须让 settings 闸门只读（否则走的是正常分支，本用例绿在错误路径上）");

        GameConfig.WaveConfig defaultWave = config.getActiveConfig().getDefaultWave();
        assertNotNull(defaultWave, "守卫 C 必须把它恢复成构造器模板，不得留 null");
        assertAll("恢复的是构造器模板（不是空壳）",
                () -> assertEquals(10, defaultWave.getTotalMobs(), "构造器预置上限 10"),
                () -> assertEquals(FACTORY_DEFAULT_KEYS, defaultWave.getCompositions().keySet(),
                        "必须恢复 3 条预置配比（new WaveConfig(10, emptyMap) 会让 weight 为 0）"),
                () -> assertEquals(6, defaultWave.getTotalWeight(),
                        "★ weight 之和必须 6 > 0；恢复成空 WaveConfig 等于把大半波次推给僵尸兜底"),
                () -> assertTrue(logs.anyContains("defaultWave was null"),
                        "守卫 C 是「不可达护栏」，触发时必须留 SEVERE 痕迹：" + logs.lines()));
    }

    // ============================================================ T8（自加）G4 空表告警

    @Test
    @DisplayName("F3-T8: waves 段存在但零数字键 → 打一条「全部回落 default 配比」的 warning（G4）")
    void wavesSectionWithoutNumberedKeysWarnsAboutDefaultFallback() throws Exception {
        BareHost host = loadBareHost();
        // 段存在（只含 default）：clear() + 重建后内存 waves 为空，这条路径在 P2 之前不可达。
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  default:\n"
                + "    total-mobs: 10\n");

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        ConfigManager config = new ConfigManager(host);

        assertAll("G4：空波次表必须被点名",
                () -> assertTrue(config.getActiveConfig().getWaves().isEmpty(),
                        "前置条件：磁盘没有数字键 ⇒ 清空后内存 waves 应为空表。实际键集="
                                + config.getActiveConfig().getWaves().keySet()),
                () -> assertFalse(config.hasUnsavedChanges(),
                        "★ clear() + 重建必须完全发生在快照点之前：空表与快照一致 ⇒ 主菜单不得亮「待保存」。"
                                + " 若这里为 true，管理员一按保存就会把「空 waves 段」固化进磁盘"),
                () -> assertTrue(logs.anyContains("has no numbered wave entries"),
                        "★ 空表路径必须打 warning（清空让它第一次可达，而控制台原先零输出）："
                                + " 管理员会以为「波次配置没生效」，实际每一波都在用 default。"
                                + " 实际日志：" + logs.lines()),
                () -> assertTrue(logs.anyContains("waves.default"),
                        "warning 必须指向回落目标 default：" + logs.lines()));
    }
}
