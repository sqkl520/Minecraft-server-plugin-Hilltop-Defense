package com.hilltopvillage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * round5 F4 的安全网：**{@code nodes.buff-effects} 的键集与「显式空」语义归磁盘**。
 *
 * <p><b>被守的缺陷</b>：{@code loadFromFile} 里有一对嵌套的 {@code isEmpty()} 守卫，
 * 于是「显式写 {@code []}」与「全部条目都写错」两种输入都被当成「没配」，
 * 内存保留构造器预置的 {@code RESISTANCE:0 / REGENERATION:0}，随后 {@code saveToFile()}
 * 把预置原样写回磁盘 —— 删除无效 + 值静默回退两症齐发。</p>
 *
 * <p><b>为什么这几条断言必须成对</b>：只断言「显式 {@code []} → 内存为空」会被
 * 「整个键缺失时也不预置」的实现通过，而那会打掉升级路径（老配置文件没有该键时
 * 应当继续拿到出厂增益）。所以每条修复用例都配一条反向对照（键缺失 → 保留预置），
 * 一个「无条件 clear」的实现会在对照半红。</p>
 *
 * <p><b>三条路径必须同改</b>：{@code loadFromFile}（新进程 / reload）、
 * {@code mergeExternalChanges}（载入后手改再点保存）、{@code NodeSystem#loadConfig}
 * （消费侧，旧实现会在空表时补一对默认值）。本类前两组断言直接盯 {@code ConfigManager}
 * 的两份守卫，第三组在 {@code NodeSystemTest} 侧覆盖。</p>
 */
class NodeBuffEffectsKeyAuthorityTest extends MockBukkitTestBase {

    private static File settingsFile(BareHost host) {
        return new File(host.getDataFolder(), "game-settings.yml");
    }

    private static String minimalSettings() {
        return "world-name: world\n"
                + "min-players: 2\n"
                + "max-players: 6\n"
                + "victory-waves: 20\n";
    }

    /** 出厂预置的两个效果（{@code GameConfig:209-210}）。 */
    private static Map<PotionEffectType, Integer> factoryPreset() {
        GameConfig factory = new GameConfig();
        return Map.copyOf(factory.getNodeBuffEffects());
    }

    /** 手改一个键再整份写回，并把 mtime 推到未来让「外部修改检测」真的介入。 */
    private static void handEdit(File file, String path, Object value) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(file));
        yaml.set(path, value);
        MockBukkitTestBase.TestPaths.write(file, yaml.saveToString());
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 5_000L));
    }

    private static List<String> diskBuffList(File file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(file));
        return yaml.getStringList("nodes.buff-effects");
    }

    // ============================================================ ① 显式空列表 = 清空

    /**
     * 缺陷签名：{@code buff-effects: []} 后内存仍是预置两条。
     *
     * <p>这条用例在修复前必须红，红在 {@code size() == 2}（不是 0）。</p>
     */
    @Test
    @DisplayName("loadFromFile: 显式 buff-effects: [] 必须清空内存（旧实现静默保留预置两条）")
    void explicitEmptyListClearsMemory() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(settingsFile(host),
                minimalSettings() + "nodes:\n  buff-effects: []\n");

        ConfigManager config = new ConfigManager(host);

        assertTrue(config.getActiveConfig().getNodeBuffEffects().isEmpty(),
                "★ 磁盘上显式写了空列表 = 「关闭全部节点增益」，内存的表必须为空。"
                        + " 修复前这里恒有构造器预置的 RESISTANCE:0 / REGENERATION:0，"
                        + " 接着任何一次 saveToFile() 都会把它们写回磁盘 —— 删除无效 + 值静默回退");
    }

    @Test
    @DisplayName("saveToFile: 显式空列表经保存后磁盘仍是空表，预置不会复活")
    void explicitEmptyListSurvivesSave() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(settingsFile(host),
                minimalSettings() + "nodes:\n  buff-effects: []\n");
        ConfigManager config = new ConfigManager(host);

        assertTrue(config.saveToFile(), "落盘应成功（两份文件都可写）");

        assertTrue(diskBuffList(settingsFile(host)).isEmpty(),
                "★ 保存后磁盘必须仍是空表。修复前 saveToFile 把内存里的预置两条写回，"
                        + " 于是「删掉增益」在重启 / 保存往返里永远无法成立");
        // 区分「写回空表」与「直接删键」：键必须仍在，否则下次启动会走「键缺失 → 出厂默认」，
        // 表现为「删了增益，重启又有了」——同一个症状换了一条路径复现。
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(settingsFile(host)));
        assertTrue(yaml.isSet("nodes.buff-effects"),
                "清空必须写成「空列表」而不是「删键」：键缺失的语义是「回出厂默认」，"
                        + "那样重启后预置增益会从另一条路径复活");
    }

    // ============================================================ ② 全部条目非法 = 清空 + 告警

    @Test
    @DisplayName("loadFromFile: 全部条目非法时清空内存，并补一条「结果为空表」的结论告警")
    void allInvalidEntriesClearMemoryWithSummaryWarning() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(settingsFile(host),
                minimalSettings()
                        + "nodes:\n"
                        + "  buff-effects:\n"
                        + "    - \"NOT_A_REAL_EFFECT:0\"\n"
                        + "    - \"also-bogus\"\n");

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        ConfigManager config = new ConfigManager(host);

        assertTrue(config.getActiveConfig().getNodeBuffEffects().isEmpty(),
                "每一对都无法解析 ⇒ 有效条目为零。旧实现在这里当「没配」而保留预置两条，"
                        + "于是「写错了效果名」被静默改成「还在给出厂增益」");
        assertTrue(logs.anyContains("All nodes.buff-effects entries were rejected"),
                "逐条 warning 之外必须有一句结论，否则「写错了」与「本来就想全关」"
                        + "在日志里不可区分；实际：" + logs.lines());
    }

    // ============================================================ ③ 反向对照：键缺失 = 出厂

    /**
     * <b>反向对照</b>：键根本不存在时必须保留构造器预置。
     *
     * <p>守的是「不要过度收紧」——把守卫改成「无条件 clear」的实现会在这里红：
     * 存量服务器的老 {@code game-settings.yml}（升级前没有这个键）会一夜之间失去全部节点增益，
     * 且没有任何提示。任何一份「升级前创建、还没有这个键」的配置文件都会命中这条路径喵。</p>
     */
    @Test
    @DisplayName("反向对照: nodes.buff-effects 键缺失时保留出厂预置（不能改成无条件清空）")
    void missingKeyKeepsFactoryPreset() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimalSettings() + "nodes:\n  base-health: 120.0\n");

        ConfigManager config = new ConfigManager(host);

        assertEquals(factoryPreset(), config.getActiveConfig().getNodeBuffEffects(),
                "★ 键缺失 = 没配过 = 用代码默认值。若这里为空，说明修复被做成了「无条件清空」，"
                        + "所有升级前的老配置都会静默失去节点增益");
    }

    /**
     * <b>反向对照 2</b>：合法条目部分有效时只保留有效的那几条（不连坐）。
     */
    @Test
    @DisplayName("反向对照: 部分条目非法时保留有效条目，且不清成空表")
    void partiallyInvalidEntriesKeepValidOnes() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(settingsFile(host),
                minimalSettings()
                        + "nodes:\n"
                        + "  buff-effects:\n"
                        + "    - \"SPEED:1\"\n"
                        + "    - \"NOT_A_REAL_EFFECT:0\"\n");

        ConfigManager config = new ConfigManager(host);

        Map<PotionEffectType, Integer> buffs = config.getActiveConfig().getNodeBuffEffects();
        assertEquals(1, buffs.size(),
                "SPEED 有效、另一条非法 ⇒ 恰剩 1 条。连坐清空与「保留预置」都是错的");
        assertEquals(1, buffs.get(PotionEffectType.getByName("SPEED")),
                "有效条目的 amplifier 必须原样保留");
    }

    // ============================================================ ④ 形态错：标量

    @Test
    @DisplayName("loadFromFile: buff-effects 写成标量时保留内存值并告警（不当成显式清空）")
    void scalarFormKeepsMemoryAndWarns() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(settingsFile(host),
                minimalSettings() + "nodes:\n  buff-effects: \"RESISTANCE:0\"\n");

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        ConfigManager config = new ConfigManager(host);

        assertEquals(factoryPreset(), config.getActiveConfig().getNodeBuffEffects(),
                "★ 标量是形态错，不是「清空意图」：getStringList 对它会静默返回空表，"
                        + "若按显式清空处理，一次手滑的引号就会静默关掉全部节点增益");
        assertTrue(logs.anyContains("must be a list"),
                "形态错必须说出来，否则管理员只看到「改了没效果」：" + logs.lines());
    }

    // ============================================================ ⑤ 外部修改合并（第二份守卫）

    /**
     * {@code mergeExternalChanges} 是 {@code loadFromFile} 那段守卫的<b>复制品</b>，
     * 修一处漏两处时这条会红。
     *
     * <p>场景：插件载入 → 管理员在游戏外把 {@code buff-effects} 改成 {@code []} →
     * 回到 GUI 点保存。旧实现在合并时同样跳过（{@code !parsedBuffs.isEmpty()}），
     * 于是内存旧值覆盖手改，磁盘被写回两条预置。</p>
     */
    @Test
    @DisplayName("mergeExternalChanges: 载入后手改成空表，下一次保存必须尊重它（第二份守卫）")
    void externalEditToEmptyIsRespectedOnSave() throws Exception {
        BareHost host = loadBareHost();
        // 先用出厂模板起步：磁盘上确实是 RESISTANCE:0 / REGENERATION:0 两条
        host.saveResource("game-settings.yml", false);
        ConfigManager config = new ConfigManager(host);
        assertEquals(2, config.getActiveConfig().getNodeBuffEffects().size(),
                "前置条件：出厂模板应给出 2 条节点增益");

        // 载入之后手改成显式空表（并让 mtime 变化，触发外部修改合并）
        handEdit(settingsFile(host), "nodes.buff-effects", List.of());

        assertTrue(config.saveToFile(), "落盘应成功");
        assertTrue(config.getActiveConfig().getNodeBuffEffects().isEmpty(),
                "★ 合并路径必须与加载路径同样尊重显式空表。只改 loadFromFile 时这里仍是 2 条");
        assertTrue(diskBuffList(settingsFile(host)).isEmpty(),
                "磁盘也不得被内存旧值覆盖回两条 —— 那正是「手改被静默吞掉」");
    }
}
