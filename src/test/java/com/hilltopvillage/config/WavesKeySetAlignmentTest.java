package com.hilltopvillage.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * P1 的安全网：**「波次的全部字段都必须从磁盘建出来」** 这条契约。
 *
 * <p>本类只守 P1 修好的两件事（F2 与症状 2），不守症状 1（删除复活，留给 P2）：</p>
 *
 * <ol>
 *   <li><b>F2 —— compositions 丢失</b>：旧实现把磁盘值「就地覆盖」到内存里已经存在的
 *       {@code MobComposition} 上，于是「内存里没有的怪种」被 {@code comp == null} 判成未知并
 *       {@code continue}。GUI 给第 1 波加一个 {@code flying-dropper}，reload 后它就静默消失。</li>
 *   <li><b>症状 2 —— 磁盘新波次被丢弃</b>：旧实现在 {@code wc == null} 时去 carry-over 取副本，
 *       取不到就 {@code continue}，于是「磁盘有 waves.7、内存没有」这个组合整条被丢弃，
 *       管理员手写的波次永远不生效。</li>
 * </ol>
 *
 * <p><b>判别力来源：断言数据结构，不断言日志。</b>现有的
 * {@code ConfigManagerTest#shippedTemplateLoadsWithoutUnknownKeyWarnings} 守不住这件事 ——
 * 它断言的是 {@code "unrecognized entries under waves"}（非数字波次键），而 compositions 丢失
 * 打的是另一条串，两个串互不包含，那条 {@code assertFalse} 恒绿喵。</p>
 *
 * <p><b>取数纪律：一律 {@code getWaves().get(n)} + {@code assertNotNull}，绝不用
 * {@code getWaveConfig(n)}。</b>后者是 {@code getOrDefault(waveNumber, defaultWave)}
 * （{@code GameConfig:829}），会把「波次丢失」伪装成「拿到了 defaultWave」——
 * 失败信息会变成「多了一个 flying-dropper」，指向完全错误的方向喵。</p>
 */
class WavesKeySetAlignmentTest extends MockBukkitTestBase {

    // ============================================================ 夹具

    private static File settingsFile(BareHost host) {
        return new File(host.getDataFolder(), "game-settings.yml");
    }

    /**
     * 最小但能过 {@code gameSettingsValidator}（要求 ≥3 个已知顶层键）的配置。
     *
     * <p>不满 3 个已知键时文件会被判 SUSPECT 并隔离成 {@code *.corrupt}，
     * {@code loadFromFile()} 走 early return —— 测试会红（或绿）在一个与主题无关的分支上喵。</p>
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

    /** null 安全地取某个怪种的 weight；缺失返回 -1。 */
    private static int weightOf(GameConfig.WaveConfig wave, String mobKey) {
        GameConfig.MobComposition comp = wave.getCompositions().get(mobKey);
        return comp == null ? -1 : comp.getWeight();
    }

    /** 未知怪种告警的**稳定片段**（与生产代码文案前缀严格一致，且不会撞上 monsters 段那条）。 */
    private static final String UNKNOWN_COMPOSITION_FRAGMENT =
            "neither a monsters-section key nor a spawnable vanilla entity type";

    // ============================================================ ★ 核心判别测试

    @Test
    @DisplayName("★ 出厂模板: waves.1 的 compositions 必须逐键逐值进内存（键集 / 逐怪 count·weight / 两合计 / total-mobs）")
    void shippedWaveOneCompositionsSurviveLoad() {
        BareHost host = loadBareHost();
        // 故意不改任何文件：被测的就是「出厂模板 → 内存」这条路。
        // 构造器做三件事：initSettingsFile()（saveResource + 校验）→ initMonstersConfigFile() → loadFromFile()
        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        ConfigManager config = new ConfigManager(host);

        Map<Integer, GameConfig.WaveConfig> waves = config.getActiveConfig().getWaves();
        if (!waves.containsKey(1)) {
            fail("waves.1 根本没进内存表（当前键集 " + waves.keySet() + "）。"
                    + " 若这里红而 getWaveConfig(1) 看起来「有值」，那是回落到了 defaultWave 的假象");
        }
        GameConfig.WaveConfig wave1 = waves.get(1);
        Map<String, GameConfig.MobComposition> comps = wave1.getCompositions();

        // 头条断言：fail-fast，失败信息直接指向病理与后果
        assertFalse(comps.isEmpty(),
                "出厂模板 waves.1 的 compositions 进内存后是空表。典型病因：加载循环以 "
                        + "wc.getCompositions().get(mobKey) == null 为判据 —— 新建的 WaveConfig 的 map 是空的"
                        + "（GameConfig:476-479 只存引用），于是磁盘上每个合法怪种都被判「未知」并 continue。"
                        + " 后果：getTotalCount()==0 且 getTotalWeight()==0，WaveManager:117/123 两条精确路径"
                        + "全部失配 ⇒ 每波刷 5+2×波数 只原版僵尸。实际 compositions=" + comps);

        assertAll("出厂模板 waves.1（total-mobs 8 / explode-beetle 6·5 / hook-claw-hunter 2·1）",

                // ---- 键层：键集恰好等于磁盘里的两个怪种（多一个是凭空造，少一个是丢配置）
                () -> assertEquals(Set.of("explode-beetle", "hook-claw-hunter"), comps.keySet(),
                        "compositions 键集必须恰好等于磁盘 keys（Set 比较，与迭代顺序无关）。"
                                + " 实际键集=" + comps.keySet()),

                // ---- 值层：逐怪 count / weight 必须来自磁盘，不是构造默认、不是 0
                () -> assertEquals(6, countOf(wave1, "explode-beetle"),
                        "explode-beetle.count 必须是磁盘的 6（-1 表示该键被判未知丢弃）"),
                () -> assertEquals(5, weightOf(wave1, "explode-beetle"),
                        "explode-beetle.weight 必须是磁盘的 5（-1 表示该键被判未知丢弃）"),
                () -> assertEquals(2, countOf(wave1, "hook-claw-hunter"),
                        "hook-claw-hunter.count 必须是磁盘的 2（-1 表示该键被判未知丢弃）"),
                () -> assertEquals(1, weightOf(wave1, "hook-claw-hunter"),
                        "hook-claw-hunter.weight 必须是磁盘的 1（-1 表示该键被判未知丢弃）"),

                // ---- 派生层：getTotalCount 是 count 之和，getTotalWeight 是 weight 之和
                () -> assertEquals(8, wave1.getTotalCount(),
                        "getTotalCount() 是 compositions 的 count 之和（6+2=8），不是 total-mobs（GameConfig:492-494）"),
                () -> assertEquals(6, wave1.getTotalWeight(),
                        "getTotalWeight() 必须为 6（5+1）；为 0 时 WaveManager:123 会把整波换成原版僵尸"),

                // ---- 不变量：本波至少要有一条可用的刷怪依据
                () -> assertTrue(wave1.getTotalCount() > 0 || wave1.getTotalWeight() > 0,
                        "不变量：count 全 0 且 weight 全 0 ⇒ WaveManager:117 与 :123 双双失配 ⇒ spawnDefaultWave"),

                // ---- 独立字段层：total-mobs 与 count 之和是两个字段，必须分别断言
                //      出厂模板四波的 sum(count) 与 total-mobs 恰好全部相等（8/8、14/14、20/20、10/10），
                //      所以「漏读 total-mobs」只有这一条能抓住。
                () -> assertEquals(8, wave1.getTotalMobs(),
                        "total-mobs 必须来自磁盘（8）。漏读会让预算回落构造默认 10，而模板里 sum(count) 恰好也是 8，"
                                + " 所以只有这条断言能抓住「total-mobs 没读」——它决定 WaveManager:109-112 的裁剪尺度"),

                // ---- 加分项：日志（否定式；正向存活证明见 waveWithoutCompositionsLogsWarning）
                () -> assertFalse(logs.anyContains(UNKNOWN_COMPOSITION_FRAGMENT),
                        "出厂模板的怪种全在 monsters 段，不该被判未知；出现该 warning 就是「判据用错」的直接信号。"
                                + " 实际日志：" + logs.lines()));
    }

    // ============================================================ 症状 2：磁盘新波次

    @Test
    @DisplayName("症状 2: 手写 waves.7（total-mobs + compositions）必须被建出来，且值全部取自磁盘")
    void handWrittenWaveIsBuiltFromDisk() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  \"7\":\n"
                + "    total-mobs: 9\n"
                + "    compositions:\n"
                + "      explode-beetle:\n"
                + "        count: 2\n"
                + "        weight: 3\n");

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        ConfigManager config = new ConfigManager(host);

        GameConfig.WaveConfig wave7 = config.getActiveConfig().getWaves().get(7);
        assertNotNull(wave7,
                "★ 磁盘上有 waves.7、构造器只预置 {1,5,10} ⇒ 旧实现在 wc == null 分支拿不到 carry-over，"
                        + " 直接 continue 把整条波次丢掉，管理员手写的波次永远不生效（症状 2）。"
                        + " 实际键集=" + config.getActiveConfig().getWaves().keySet());

        assertAll("手写 waves.7（total-mobs 9 / explode-beetle 2·3）",
                () -> assertEquals(9, wave7.getTotalMobs(), "total-mobs 必须读磁盘的 9"),
                () -> assertEquals(2, countOf(wave7, "explode-beetle"), "count 必须读磁盘的 2"),
                () -> assertEquals(3, weightOf(wave7, "explode-beetle"), "weight 必须读磁盘的 3"),
                () -> assertEquals(2, wave7.getTotalCount(), "getTotalCount() 应为 2"),
                () -> assertEquals(3, wave7.getTotalWeight(), "getTotalWeight() 应为 3"),
                () -> assertFalse(logs.anyContains(UNKNOWN_COMPOSITION_FRAGMENT),
                        "explode-beetle 是合法怪种，不得被判未知。实际日志：" + logs.lines()));
    }

    // ============================================================ F2 的正向半

    @Test
    @DisplayName("F2: 给出厂 wave 1 加一个 flying-dropper 后保存 → 新 ConfigManager 载入，怪种必须还在")
    void addMonsterToFactoryWaveSurvivesReload() {
        BareHost host = loadBareHost();
        ConfigManager first = new ConfigManager(host);

        GameConfig.WaveConfig wave1 = first.getActiveConfig().getWaves().get(1);
        assertNotNull(wave1, "前置条件：出厂模板应已载入 waves.1");
        // 模拟 GUI 的写路径（AddMonsterMenu 拿到的是活 Map，put 进去就是「加怪种」）
        wave1.getCompositions().put("flying-dropper", new GameConfig.MobComposition(3, 2));
        assertTrue(first.saveToFile(), "前置条件：落盘应成功");

        // 换一个全新实例重新载入：这才是「重启 / reloadconfig 之后」的真实路径
        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        ConfigManager second = new ConfigManager(host);

        GameConfig.WaveConfig reloaded = second.getActiveConfig().getWaves().get(1);
        assertNotNull(reloaded, "waves.1 必须还在");
        assertAll("reload 后 wave 1 应含 3 个怪种（beetle 6·5 / hunter 2·1 / dropper 3·2）",
                () -> assertEquals(Set.of("explode-beetle", "hook-claw-hunter", "flying-dropper"),
                        reloaded.getCompositions().keySet(),
                        "★ 旧实现只对「内存里已存在」的怪种赋值，新加的 flying-dropper 会因 comp == null 被"
                                + " 判成未知并丢弃（F2，开箱即坏：Add Monster 的唯一起步用法）。"
                                + " 实际键集=" + reloaded.getCompositions().keySet()),
                () -> assertEquals(3, countOf(reloaded, "flying-dropper"), "新加怪种的 count 必须落盘并可读回"),
                () -> assertEquals(2, weightOf(reloaded, "flying-dropper"), "新加怪种的 weight 必须落盘并可读回"),
                () -> assertEquals(11, reloaded.getTotalCount(), "getTotalCount() = 6+2+3 = 11"),
                () -> assertEquals(8, reloaded.getTotalMobs(), "total-mobs 仍是磁盘的 8"),
                () -> assertFalse(logs.anyContains(UNKNOWN_COMPOSITION_FRAGMENT),
                        "flying-dropper 是内置怪种，不得被判未知。实际日志：" + logs.lines()));
    }

    // ============================================================ 判据正确性

    @Test
    @DisplayName("判据: 原版可生成实体键（zombie）被接受；非 Mob 实体键（arrow）被拒绝并点名")
    void vanillaEntityTypeCompositionIsAccepted() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  \"1\":\n"
                + "    total-mobs: 5\n"
                + "    compositions:\n"
                + "      zombie:\n"
                + "        count: 2\n"
                + "        weight: 1\n"
                + "      arrow:\n"
                + "        count: 1\n"
                + "        weight: 1\n");

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        ConfigManager config = new ConfigManager(host);

        GameConfig.WaveConfig wave1 = config.getActiveConfig().getWaves().get(1);
        assertNotNull(wave1, "waves.1 应被建出来");

        assertAll("判据必须与 WaveManager#spawnCustomMob 的两级分派同源",
                () -> assertTrue(wave1.getCompositions().containsKey("zombie"),
                        "★ zombie 在运行时是**受支持**的：WaveManager:461-473 的 default 分支会"
                                + " EntityType.valueOf(\"ZOMBIE\") 并 spawnEntity。判据只认 monsters 段会把它"
                                + " 永久拒掉，而 warning 文案还会说「合法键就是 monsters 段那些键」——把一条写对的"
                                + " 配置说成拼写错误。实际键集=" + wave1.getCompositions().keySet()),
                () -> assertEquals(2, countOf(wave1, "zombie"), "被接受后 count 必须读到"),
                () -> assertFalse(wave1.getCompositions().containsKey("arrow"),
                        "arrow 是合法 EntityType 但不是 Mob 子类，spawnEntity 会抛异常（WaveManager:474-476）⇒"
                                + " 必须被拒，否则表现为「每只都刷失败 + 一条条 Unknown mob type 噪音」"),
                () -> assertTrue(logs.anyContains(UNKNOWN_COMPOSITION_FRAGMENT),
                        "★ 否定式断言的存活证明：这条串必须真的能被本实现打出来。"
                                + " 本仓库曾因「断言串与实际 warning 串互不包含」丢过一次守护。"
                                + " 实际日志：" + logs.lines()),
                () -> assertTrue(logs.anyContains("1.arrow"),
                        "warning 必须点名被拒的键，否则管理员找不到：" + logs.lines()));
    }

    // ============================================================ 值层默认值

    @Test
    @DisplayName("缺键语义: compositions 缺 weight 时默认 1（不是 0）—— 否则整波落进 spawnDefaultWave")
    void missingWeightDefaultsToOneNotZero() throws Exception {
        BareHost host = loadBareHost();
        // count 显式写 0：把「走哪条刷怪路径」完全交给 weight，这样 weight 的默认值才有判别力。
        // 若 fixture 里 count: 2，WaveManager:117 的 getTotalCount()>0 会先命中精确配比，weight 根本不参与。
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  \"8\":\n"
                + "    total-mobs: 5\n"
                + "    compositions:\n"
                + "      explode-beetle:\n"
                + "        count: 0\n");

        ConfigManager config = new ConfigManager(host);

        GameConfig.WaveConfig wave8 = config.getActiveConfig().getWaves().get(8);
        assertNotNull(wave8, "waves.8 应被建出来");
        assertEquals(1, weightOf(wave8, "explode-beetle"),
                "★ 缺 weight 必须回落 1。若回落 0：getTotalCount()==0 且 getTotalWeight()==0"
                        + " ⇒ WaveManager:117 与 :123 两条路径全部失配 ⇒ 整波换成 5+2×波数 只原版僵尸。"
                        + " -1 表示该键被判未知丢弃");
        assertTrue(wave8.getTotalCount() > 0 || wave8.getTotalWeight() > 0,
                "不变量：本波必须至少有一条可用的刷怪依据");
        assertEquals(0, countOf(wave8, "explode-beetle"), "缺省 count 应为 0（合法缺省，不告警）");
    }

    // ============================================================ W2：没有 compositions 的波次

    @Test
    @DisplayName("W2: 只写 total-mobs、没有 compositions 的波次必须打 warning（否则静默刷原版僵尸）")
    void waveWithoutCompositionsLogsWarning() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings()
                + "waves:\n"
                + "  \"7\":\n"
                + "    total-mobs: 20\n");

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        ConfigManager config = new ConfigManager(host);

        GameConfig.WaveConfig wave7 = config.getActiveConfig().getWaves().get(7);
        assertNotNull(wave7, "waves.7 必须被建出来（没写 compositions 不等于「这波不存在」）");
        assertEquals(20, wave7.getTotalMobs(), "total-mobs 仍要读磁盘的 20");
        assertEquals(0, wave7.getTotalCount(), "没有 compositions ⇒ count 之和为 0");
        assertTrue(logs.anyContains("no usable 'compositions' entries"),
                "★ W2（本轮唯一**新引入**的静默面）：`waves.\"7\": {total-mobs: 20}` 是最自然的「我只想设个总数」"
                        + " 写法，改造后它会变成一个「波次出现了但刷 5+2×7=19 只原版僵尸」的波次"
                        + "（WaveManager:123 的 totalWeight<=0 兜底），而控制台原先零输出。必须点名。"
                        + " 实际日志：" + logs.lines());
        assertTrue(logs.anyContains("7"),
                "warning 必须点名是哪个波次，否则管理员找不到：" + logs.lines());
    }
}
