package com.hilltopvillage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * round5 F5 的安全网：**{@code monsters-config.yml} 里插件不认识的怪物段必须有日志**。
 *
 * <p><b>被守的不是「支持新增怪种」</b>——那本来就做不到：{@code GameConfig} 的构造器把
 * {@code monsters} 键集固定成 3 个，{@code WaveManager#spawnCustomMob} 的 {@code switch}
 * 也只认这 3 个键（其余走 {@code EntityType.valueOf} 原版怪）。{@code loadMonstersConfig}
 * 遍历的是<b>内存</b>键集，所以磁盘上的第 4 段永远读不到。</p>
 *
 * <p><b>被守的是「零日志」这件事</b>：{@code YamlFiles#monstersConfigValidator} 只要求
 * 「每段含任一已知子键」，所以管理员照抄一段、改个名字、写全 20 个字段——文件完全合法、
 * 哨兵全过、加载循环根本不看它、控制台一句提示都没有。而同一个错误在
 * {@code game-settings.yml} 的 {@code monsters} 侧是会报的（{@code :1545-1547}），
 * 两份文件口径不一致才是真正的缺陷。</p>
 *
 * <p><b>断言纪律</b>：只断言「日志点名了那个段」，不断言任何行为变化（行为按设计不变），
 * 也不断言文案全文——文案会随措辞调整，稳定片段才可断言。</p>
 */
class MonstersConfigUnsupportedSectionWarningTest extends MockBukkitTestBase {

    /** 与新告警文案的稳定片段严格一致，且不会撞上 dc-002 那条「不生效键」告警。 */
    private static final String FRAGMENT = "with no built-in monster";

    private static File monstersFile(BareHost host) {
        return new File(host.getDataFolder(), "monsters-config.yml");
    }

    /** 最小但能过 {@code gameSettingsValidator}（≥3 个已知顶层键）的配置。 */
    private static String minimalSettings() {
        return "world-name: world\n"
                + "min-players: 2\n"
                + "max-players: 6\n"
                + "victory-waves: 20\n";
    }

    /**
     * 把出厂 monsters-config.yml 写进数据目录，并在其后追加一个「第 4 种怪」段。
     *
     * <p>经 {@code YamlConfiguration} 整份改写（而不是字符串拼接）：模板里带大量注释，
     * 拼接容易与缩进错位，而 {@code saveToString()} 会丢掉注释——本用例不关心注释。</p>
     */
    private static void writeTemplatePlusExtraSection(BareHost host, String section) throws Exception {
        host.saveResource("monsters-config.yml", false);
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString(MockTestPaths.read(monstersFile(host)));
        // 照抄一个真实怪种的完整字段集：这证明「写得完全正确」也进不来
        yaml.set(section + ".entity-type", "ZOMBIE");
        yaml.set(section + ".base-damage", 5.0);
        yaml.set(section + ".health", 45.0);
        yaml.set(section + ".speed", 0.25);
        yaml.set(section + ".display-egg", "ZOMBIE_SPAWN_EGG");
        yaml.set(section + ".main-hand", "IRON_SWORD");
        MockTestPaths.write(monstersFile(host), yaml.saveToString());
    }

    private static final class MockTestPaths {
        static String read(File f) throws Exception {
            return MockBukkitTestBase.TestPaths.read(f);
        }

        static void write(File f, String s) throws Exception {
            MockBukkitTestBase.TestPaths.write(f, s);
        }
    }

    // ============================================================ ① 缺陷签名

    @Test
    @DisplayName("新怪物段（字段写全、文件合法）必须产生点名它的 warning（旧实现零输出）")
    void extraMonsterSectionProducesWarning() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(
                new File(host.getDataFolder(), "game-settings.yml"), minimalSettings());
        writeTemplatePlusExtraSection(host, "shadow-stalker");

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        new ConfigManager(host);

        assertTrue(logs.anyContains(FRAGMENT),
                "★ monsters-config.yml 里没有任何内置怪物对应的段必须报出来 —— "
                        + "旧实现连文件合法都不救：validator 只要求「每段含任一已知子键」，"
                        + "加载循环又只遍历内存键集（构造器闭集 3 键），于是这条路径零日志。"
                        + "实际：" + logs.lines());
        assertTrue(logs.anyContains("shadow-stalker"),
                "warning 必须点名那个段，否则管理员只知道「有东西不对」：" + logs.lines());
    }

    @Test
    @DisplayName("行为不变: 新怪物段不会进 getMonsters()（本修复只补可观测性）")
    void extraMonsterSectionDoesNotChangeBehavior() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(
                new File(host.getDataFolder(), "game-settings.yml"), minimalSettings());
        writeTemplatePlusExtraSection(host, "shadow-stalker");

        ConfigManager config = new ConfigManager(host);

        assertEquals(3, config.getActiveConfig().getMonsters().size(),
                "键集仍必须是构造器闭集 3 键：新增怪种需要代码里绑定的 AI，"
                        + "把它塞进 map 会让 WaveManager 的 switch 落进原版怪兜底，"
                        + "而它连装备覆盖都拿不到（loadMonstersConfig 只遍历内存键）");
        assertFalse(config.getActiveConfig().getMonsters().containsKey("shadow-stalker"),
                "新段不得被偷偷注册进来");
    }

    // ============================================================ ② 反向对照：出厂模板安静

    /**
     * <b>反向对照</b>：出厂 monsters-config.yml 必须零告警。
     *
     * <p>守的是「不要过度收紧」——把判据写成「磁盘键数 != 3 就报」的实现会在这条红，
     * 且会在每个正常服务器上每次启动刷一条假警报喵。</p>
     */
    @Test
    @DisplayName("反向对照: 出厂模板不产生该告警（别把正常配置写成告警）")
    void shippedTemplateIsSilent() {
        BareHost host = loadBareHost();

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        new ConfigManager(host);

        assertFalse(logs.anyContains(FRAGMENT),
                "出厂 monsters-config.yml 的 3 个段全部有内置怪对应，不该触发该告警；"
                        + "实际：" + logs.lines());
    }

    // ============================================================ ③ 大小写

    /**
     * <b>反向对照 2</b>：磁盘上写 {@code Explode-Beetle} 不算「不支持」。
     *
     * <p>消费侧 {@code WaveManager} 与 {@code getMonsters().get(...)} 都先
     * {@code toLowerCase(Locale.ROOT)}，因此大写键是能用的；判据必须与消费侧一致，
     * 否则会对一个正常工作的段乱报警告。</p>
     */
    @Test
    @DisplayName("反向对照: 大小写不同的内置怪段不报警（判据与消费侧一致）")
    void differentlyCasedBuiltinSectionIsNotReported() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(
                new File(host.getDataFolder(), "game-settings.yml"), minimalSettings());
        host.saveResource("monsters-config.yml", false);
        // 把 explode-beetle 段整体改名成 Explode-Beetle
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(monstersFile(host)));
        yaml.set("Explode-Beetle", yaml.getConfigurationSection("explode-beetle"));
        yaml.set("explode-beetle", null);
        MockBukkitTestBase.TestPaths.write(monstersFile(host), yaml.saveToString());

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        new ConfigManager(host);

        assertFalse(logs.anyContains("Explode-Beetle"),
                "大写键在消费侧是有效的（toLowerCase 查找），不得报成「不支持」；"
                        + "实际：" + logs.lines());
    }

    // ============================================================ ④ 与 dc-002 不互相干扰

    @Test
    @DisplayName("两条告警互不吞没: 新段告警与「不生效键」告警各自独立触发")
    void bothWarningsFireIndependently() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(
                new File(host.getDataFolder(), "game-settings.yml"), minimalSettings());
        host.saveResource("monsters-config.yml", false);

        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(monstersFile(host)));
        // ① 不支持的段；② 内置怪种上把「不生效键」改成与生效值不同的值
        yaml.set("shadow-stalker.entity-type", "ZOMBIE");
        yaml.set("shadow-stalker.health", 45.0);
        yaml.set("explode-beetle.health", 999.0);
        MockBukkitTestBase.TestPaths.write(monstersFile(host), yaml.saveToString());

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        new ConfigManager(host);

        assertTrue(logs.anyContains(FRAGMENT),
                "新段告警应触发：" + logs.lines());
        assertTrue(logs.anyContains("never reads"),
                "dc-002「不生效键」告警也应独立触发，两条不能互相吞没：" + logs.lines());
    }

    @Test
    @DisplayName("顶层标量不是怪物段，不报警（validator 同款口径）")
    void topLevelScalarIsNotReported() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(
                new File(host.getDataFolder(), "game-settings.yml"), minimalSettings());
        host.saveResource("monsters-config.yml", false);
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(monstersFile(host)));
        yaml.set("some-note", "just a comment turned into a key");
        MockBukkitTestBase.TestPaths.write(monstersFile(host), yaml.saveToString());

        MockBukkitTestBase.LogCapture logs = attachLogCapture(host.getLogger());
        new ConfigManager(host);

        assertFalse(logs.anyContains("some-note"),
                "顶层标量不是怪物段（validator 也这么判），报出来是噪音：" + logs.lines());
        assertNotNull(monstersFile(host), "文件应仍在原位（未被隔离）");
    }
}
