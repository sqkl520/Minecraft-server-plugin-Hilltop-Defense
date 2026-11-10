package com.hilltopvillage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.YamlFiles;

/**
 * round5 F6 的安全网：**{@code spawning.spawn-radius-spread} 必须进
 * {@code mergeExternalChanges} 白名单**，否则「载入后手改 → 下次 GUI 保存」会把磁盘值静默吞掉。
 *
 * <h3>缺陷原貌</h3>
 *
 * <p>{@code ConfigManager:365-371} 的类注释立了不变式：凡是 {@code saveToFile()} 会写、
 * 既没有 GUI/命令编辑入口、又有运行期消费者的键，都必须在合并白名单里。
 * {@code spawn-radius-spread} 写字（{@code saveToFile}）、读字（{@code loadFromFile}，缺键保留
 * 代码默认 3.0）、有消费者（{@code WaveManager:769}：{@code radius-max} 未配置时用它做生成点扩散），
 * 全库没有任何 setter 调用点（{@code GameRulesMenu} 只暴露 min/max/两个 cap），
 * 却漏在白名单外——于是「载入 → 手改 → 点保存」的合并阶段跳过它，紧接着的全量回写用内存旧值
 * 覆盖磁盘，手改连一条日志都没有。</p>
 *
 * <h3>为什么这几条要成组</h3>
 *
 * <ul>
 *   <li>{@link #externalEditToSpawnSpreadIsRespectedOnSave()} 是<b>判别性用例</b>：修复前必须红
 *       （红在「内存仍是 3.0」与「磁盘被写回 3.0」）。</li>
 *   <li>{@link #externalEditToGuiEditableKeyIsOverriddenByMemory()} 是<b>反向对照</b>：
 *       {@code spawn-radius-min} 有 GUI 入口，白名单<u>不该</u>含它，磁盘手改必须被内存压回去。
 *       一个「干脆把整段都合并」的过度修复会让这条红 —— 白名单语义（无入口者磁盘权威、
 *       有入口者游戏内权威）必须两侧都在。</li>
 *   <li>{@link #sessionEditStillWinsOverDiskValue()} 钉住基线守卫：白名单键一旦在本会话被改过，
 *       磁盘值不得覆盖它（否则将来给该键加编辑入口时会立刻变成「保存吞掉刚改的值」）。</li>
 *   <li>{@link #absentKeyKeepsCodeDefault()} 钉住「键缺失不回出厂以外的东西」：
 *       手改别的键触发合并时，磁盘上没有 spread 键必须保留内存值，不能变成 0。</li>
 * </ul>
 *
 * <h3>夹具</h3>
 *
 * <p>{@code mergeExternalChanges} 是 private，且只在 {@code saveToFile()} 检测到
 * {@code game-settings.yml} 的 mtime 与上次读/写不一致时触发（{@code ConfigManager:749}）。
 * 因此用例一律走真实入口：改盘 → 把 mtime 推到未来 → {@code saveToFile()}，
 * 不碰反射，也不复制生产逻辑。</p>
 */
class SpawnSpreadMergeWhitelistTest extends MockBukkitTestBase {

    /** 出厂代码默认（{@code GameConfig:134}）；出厂模板刻意不写该键。 */
    private static final double CODE_DEFAULT_SPREAD = 3.0;

    private static File settingsFile(BareHost host) {
        return new File(host.getDataFolder(), "game-settings.yml");
    }

    /** 手改一个键再整份写回，并把 mtime 推到未来让「外部修改检测」真的介入。 */
    private static void handEdit(File file, String path, Object value) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(file));
        yaml.set(path, value);
        MockBukkitTestBase.TestPaths.write(file, yaml.saveToString());
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 5_000L));
    }

    private static double diskDouble(File file, String path) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(file));
        return yaml.getDouble(path);
    }

    private static int diskInt(File file, String path) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(file));
        return yaml.getInt(path);
    }

    // ============================================================ ① 判别性：手改必须落进内存

    /**
     * 缺陷签名：载入后手改 spread → 点保存 → 内存仍是 3.0、磁盘被写回 3.0。
     *
     * <p>修复前红在两条断言上（内存与磁盘各一处）；修复后两条都绿。</p>
     */
    @Test
    @DisplayName("mergeExternalChanges: 载入后手改 spawn-radius-spread，下一次保存必须尊重它")
    void externalEditToSpawnSpreadIsRespectedOnSave() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = new ConfigManager(host);
        assertEquals(CODE_DEFAULT_SPREAD, config.getActiveConfig().getSpawnRadiusSpread(), 1e-9,
                "前置条件：出厂模板不含该键 ⇒ 加载后取代码默认 3.0");

        handEdit(settingsFile(host), "spawning.spawn-radius-spread", 7.5);

        assertTrue(config.saveToFile(), "落盘应成功（两份文件都可写）");

        assertEquals(7.5, config.getActiveConfig().getSpawnRadiusSpread(), 1e-9,
                "★ 无编辑入口的键，磁盘是权威：合并阶段必须把它读进内存。"
                        + " 修复前这里是 3.0 —— 白名单漏项使手改永远进不了内存");
        assertEquals(7.5, diskDouble(settingsFile(host), "spawning.spawn-radius-spread"), 1e-9,
                "★ 磁盘也不得被内存旧值覆盖回 3.0 —— 那正是「手改被静默吞掉」的可见后果");
    }

    // ============================================================ ② 反向对照：有 GUI 入口的键不合并

    /**
     * {@code spawn-radius-min} 在 {@code GameRulesMenu} 有编辑入口，白名单<u>刻意</u>不含它：
     * 游戏内为权威，磁盘手改在保存时被内存值压回去。
     *
     * <p>这条挡的是「把整段 spawning 都塞进白名单」的过度修复。</p>
     */
    @Test
    @DisplayName("对照：有 GUI 入口的 spawn-radius-min 手改必须被内存压回（白名单不许放宽）")
    void externalEditToGuiEditableKeyIsOverriddenByMemory() throws Exception {
        BareHost host = loadBareHost();
        host.saveResource("game-settings.yml", false);
        ConfigManager config = new ConfigManager(host);
        int loadedMin = config.getActiveConfig().getSpawnRadiusMin();
        assertNotEquals(99, loadedMin, "前置条件：出厂值不应恰好是 99");

        handEdit(settingsFile(host), "spawning.spawn-radius-min", 99);

        assertTrue(config.saveToFile(), "落盘应成功");
        assertEquals(loadedMin, config.getActiveConfig().getSpawnRadiusMin(),
                "有 GUI 入口的键不进白名单：合并阶段必须跳过它，内存值原样保留");
        assertEquals(loadedMin, diskInt(settingsFile(host), "spawning.spawn-radius-min"),
                "磁盘被全量回写覆盖成内存值 —— 这是「游戏内为权威」的既定语义，不是缺陷");
    }

    // ============================================================ ③ 基线守卫：会话内改动胜出

    /**
     * 白名单键的合并带基线守卫：只有「内存值仍等于上次落盘快照」时才让磁盘胜出。
     *
     * <p>当前 spread 没有编辑入口，守卫等价恒真；但一旦将来加了入口，缺了守卫的合并会立刻
     * 变成「玩家刚调的值被旧磁盘值吞掉」。这条把它钉住。</p>
     */
    @Test
    @DisplayName("对照：本会话内改过的值必须胜过磁盘（基线守卫不得被新加的合并破坏）")
    void sessionEditStillWinsOverDiskValue() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = new ConfigManager(host);
        config.getActiveConfig().setSpawnRadiusSpread(9.25);

        handEdit(settingsFile(host), "spawning.spawn-radius-spread", 7.5);

        assertTrue(config.saveToFile(), "落盘应成功");
        assertEquals(9.25, config.getActiveConfig().getSpawnRadiusSpread(), 1e-9,
                "会话内改过的值必须保留：合并只对「本会话没动过」的键让磁盘胜出");
        assertEquals(9.25, diskDouble(settingsFile(host), "spawning.spawn-radius-spread"), 1e-9,
                "磁盘应写成会话内的值");
    }

    // ============================================================ ④ 缺键不回退为 0

    /**
     * 磁盘上没有该键（出厂模板即如此）时，合并必须跳过、保留内存值 —— 不能读成 0。
     *
     * <p>这一条用「手改别的键」把合并路径叫起来，而 spread 键在磁盘上仍然缺失。</p>
     */
    @Test
    @DisplayName("对照：磁盘缺该键时合并跳过，内存保留代码默认（不得读成 0）")
    void absentKeyKeepsCodeDefault() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = new ConfigManager(host);

        // 用 180 而不是 120：120 恰是 LEGACY_WAVE_TIMEOUT_SECONDS，会被 normalizeWaveTimeout 归零
        handEdit(settingsFile(host), "wave-timeout-seconds", 180);

        assertTrue(config.saveToFile(), "落盘应成功");
        assertEquals(180, config.getActiveConfig().getWaveTimeoutSeconds(),
                "前置条件：合并路径确实被触发（白名单里的旧键生效）");
        assertEquals(CODE_DEFAULT_SPREAD, config.getActiveConfig().getSpawnRadiusSpread(), 1e-9,
                "磁盘缺键 ⇒ 保留内存值 3.0；若变成 0，「没配」会退化成「零扩散」");
    }

    // ============================================================ ⑤ F10：两份已知顶层键清单

    /**
     * F10：{@code ConfigManager.KNOWN_TOP_LEVEL_KEYS} 与
     * {@code YamlFiles.GAME_SETTINGS_KNOWN_TOP_LEVEL_KEYS} 是两份手工镜像的清单，
     * 注释自认「改动时两处必须同步」。
     *
     * <p><b>可行性结论：可行。</b>两个字段都是 {@code private static final}，但测试与生产代码同在
     * 一个 unnamed module、classpath 上运行，{@code getDeclaredField + setAccessible} 不受
     * 模块层限制；本仓库已有同款先例（{@code ArenaResetServiceTest} 读私有 {@code inFlight}、
     * {@code AirdropSpawnGoalTest} 读私有计数器），因此不需要为测试放宽字段可见性。</p>
     *
     * <p>若将来把某一份改成别的类型或改名，本用例会在 {@code getDeclaredField} 处抛
     * {@link NoSuchFieldException} 而红 —— 那正是想被提醒的时刻（哨兵失效比清单漂移更危险）。</p>
     */
    @Test
    @DisplayName("F10：两份「已知顶层键」清单必须逐项相等（反射比对哨兵）")
    void knownTopLevelKeyListsStayInSync() throws Exception {
        Set<?> configSide = readStaticSet(ConfigManager.class, "KNOWN_TOP_LEVEL_KEYS");
        Set<?> yamlSide = readStaticSet(YamlFiles.class, "GAME_SETTINGS_KNOWN_TOP_LEVEL_KEYS");

        assertNotNull(configSide, "ConfigManager.KNOWN_TOP_LEVEL_KEYS 必须可反射读取");
        assertTrue(configSide.size() >= 3,
                "清单不能为空/极小：它同时是 gameSettingsValidator 的最低命中数来源");
        assertEquals(configSide, yamlSide,
                "两份已知顶层键清单漂移了。新顶层段必须同时加进 ConfigManager 与本镜像，"
                        + "否则：① 合法新段被判拼写错误并告警；② 校验器的命中面缺一块");
    }

    @SuppressWarnings("unchecked")
    private static Set<?> readStaticSet(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(null);
        assertTrue(value instanceof Set, owner.getSimpleName() + "." + fieldName + " 应为 Set");
        return (Set<?>) value;
    }
}
