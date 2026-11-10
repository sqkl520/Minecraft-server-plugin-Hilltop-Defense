package com.hilltopvillage.config;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.util.YamlFileGate;
import com.hilltopvillage.util.YamlFiles;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

public class ConfigManager {

    private final HilltopVillagePlugin plugin;
    private GameConfig activeConfig;
    private GameConfig lastSavedSnapshot;
    private File settingsFile;
    private YamlConfiguration settingsYaml;
    private File monstersConfigFile;
    private YamlConfiguration monstersConfigYaml;
    /**
     * display.yml 的已解析模型与原文。
     *
     * <p>{@code displayYaml} 只用于喂给 {@link DisplayConfigLoader#parse}，与 {@code displayConfig}（渲染端
     * 实际消费的不可变值对象）是两份东西：后者经过归一化与夹取，前者保留磁盘原样。</p>
     */
    private DisplayConfig displayConfig;
    private File displayFile;
    private YamlConfiguration displayYaml;
    /** game-settings.yml 的读写闸门（读盘不可用 → 本进程拒写）。 */
    private final YamlFileGate settingsGate;
    /** monsters-config.yml 的读写闸门（与 game-settings 独立：一个坏不牵连另一个）。 */
    private final YamlFileGate monstersGate;
    /**
     * display.yml 的读写闸门。
     *
     * <p>取「读」的一半语义（截断/损坏检测 + 隔离 + 一次 SEVERE），写的一半永远用不到——
     * 本插件不改写 display.yml（见 {@link #saveToFile()} 的说明），且该文件不属于任何闸门的写入方。</p>
     */
    private final YamlFileGate displayGate;

    /**
     * game-settings.yml 的「外部修改检测」时间戳（M-15）。
     *
     * <p>在 initSettingsFile / loadFromFile / saveToFile 成功时记录当时的 lastModified；
     * {@link #saveToFile()} 前若发现 mtime 变化，说明文件被外部（手改 / 其它进程）修改过，
     * 走 {@link #mergeExternalChanges()} 的合并路径，而不是拿内存旧快照静默全量回写。</p>
     */
    private long settingsLastModified;

    /**
     * monsters-config.yml 的「外部修改检测」时间戳（monsters-config-dc-001）。
     *
     * <p>与 {@link #settingsLastModified} 对称：在 {@link #initMonstersConfigFile()} /
     * {@link #loadMonstersConfig()} 读到可用配置时、以及 {@link #saveMonstersConfig()} 写盘成功后记录
     * 当时的 lastModified。{@link #saveMonstersConfig()} 写盘前若发现 mtime 变化，说明文件在
     * 载入之后被外部（手改 / 其它进程）动过：先走 {@link #adoptExternalMonsterChanges()} 把磁盘内容
     * 作为写回基线，再写内存值，而不是拿载入期的旧快照直接覆盖——否则手改会一行日志都没有地消失。</p>
     */
    private long monstersConfigLastModified;

    /**
     * 本次 {@link #loadFromFile()} 是否真的从磁盘取到了可用的 game-settings.yml。
     *
     * <p>dc-002 的「不生效键」告警拿 {@code activeConfig} 里的值当作「实际生效值」来比对；
     * 当 game-settings.yml 不可用、内存退回默认值时，那个「生效值」并不来自磁盘，
     * 比对结果也就没有意义（会把出厂默认值当成服主的配置去对账）。此时静默。</p>
     */
    private boolean settingsLoadedFromDisk;

    /**
     * 本次会话经 GUI 删除过的波次号（P2-33 记账）。
     *
     * <p>{@link #saveToFile()} 对「内存 waves 存在」的键逐个写值，但被删除的旧键会留在磁盘上
     * （刻意的「不清整段」策略），导致重启后已删除的波次复活。本集合只记录
     * {@link #removeWave(int)}（GUI 删除入口）删过的号，保存时精确清掉这些键——
     * 不碰管理员手写进文件、从未出现在内存里的其它键。</p>
     */
    private final Set<Integer> removedWaveKeys = new LinkedHashSet<>();

    /**
     * 载入期归一化是否让「内存值 ≠ 磁盘值」（slice-07-data-consistency-009）。
     *
     * <p>{@link #hasUnsavedChanges()} 只比对内存与 {@link #lastSavedSnapshot}，而快照是在归一化<b>之后</b>
     * 取的，于是 {@code normalizeWaveTimeout} / {@code normalizeStarterKitItems} 改了内存值之后
     * 脏标志仍读作「干净」——主菜单显示 GREEN_WOOL「已是最新」并隐藏保存按钮，
     * 归一化结果只能等管理员恰好做了别的编辑才顺带落盘。</p>
     *
     * <p>本标志只承载「归一化产物尚未落盘」这一条，成功保存后清除。
     * 它是 {@link #hasUnsavedChanges()} 的补充项，不改变「内存 vs 快照」原比对语义。</p>
     */
    private boolean loadTimeNormalizationPending;

    /** game-settings.yml 顶层已知键集合（P2-36：用于拼写错误汇总警告）。 */
    private static final Set<String> KNOWN_TOP_LEVEL_KEYS = Set.of(
            "world-name", "core", "min-players", "max-players", "victory-waves",
            "wave-interval-seconds", "wave-timeout-seconds", "defeat-countdown-seconds",
            "villager-health", "rejoin-timeout-seconds", "empty-arena-end-seconds", "map-restore", "scoreboard",
            "hammer", "fireball", "shop", "starter-kit", "nodes", "spawning",
            "monsters", "waves");

    /**
     * monsters-config.yml 里「会被写回、但本文件不读取」的键（monsters-config-dc-002）。
     *
     * <p>这份拆分是作者知情的设计，不是漂移：这些键的真实消费点在 game-settings.yml 的
     * {@code monsters.<key>} 段（见 {@link #loadFromFile()} 的三处读取与
     * {@link #mergeExternalChanges()} 的同名合并）。本文件里的同名值只在
     * {@link #saveMonstersConfig()} 被写回、从不被 {@link #loadMonstersConfig()} 读取。</p>
     *
     * <p>用途只有一个：操作者把它们改成「生效」的样子时，用一次性汇总 warning 告诉他改的地方不对。
     * <b>不改变读写集合</b>（改读它们会把两处的权威来源打对台）。</p>
     */
    private static final Set<String> MONSTERS_CONFIG_NON_EFFECTIVE_KEYS = Set.of(
            "display-name", "display-egg", "health", "speed", "explode-damage-multiplier",
            "hook-velocity", "cooldown-ticks", "stun-duration-ticks", "airdrop-mob-count");

    public ConfigManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.activeConfig = new GameConfig();
        this.lastSavedSnapshot = activeConfig.snapshot();
        this.settingsFile = new File(plugin.getDataFolder(), "game-settings.yml");
        this.monstersConfigFile = new File(plugin.getDataFolder(), "monsters-config.yml");
        this.displayFile = new File(plugin.getDataFolder(), "display.yml");
        this.settingsGate = new YamlFileGate(settingsFile, YamlFiles.gameSettingsValidator(), plugin.getLogger());
        this.monstersGate = new YamlFileGate(monstersConfigFile, YamlFiles.monstersConfigValidator(), plugin.getLogger());
        this.displayGate = new YamlFileGate(displayFile, YamlFiles.displayValidator(), plugin.getLogger());
        // display.yml 兜底先就位：loadFromFile() 内可能因异常路径提前返回，渲染端任何时刻读到的
        // displayConfig 都必须非 null（侧边栏空白与「插件坏了」在玩家眼里不可区分）
        this.displayConfig = DisplayConfig.defaults();
        initSettingsFile();
        initMonstersConfigFile();
        initDisplayFile();
        loadFromFile();
    }

    /**
     * 初始化游戏设置配置文件 (game-settings.yml)。
     * 首次运行时从 jar 中复制默认文件。
     */
    private void initSettingsFile() {
        if (!settingsFile.exists()) {
            plugin.saveResource("game-settings.yml", false);
        }
        YamlFiles.Loaded loaded = settingsGate.load();
        if (!loaded.usable()) {
            plugin.getLogger().severe("game-settings.yml is unusable; running with defaults and NOT writing it this run");
            settingsYaml = new YamlConfiguration();
            return;
        }
        settingsYaml = loaded.config();
        settingsLastModified = settingsFile.lastModified();
    }

    /**
     * 初始化怪物配置文件 (monsters-config.yml)。
     * 首次运行时从 jar 中复制默认文件。此文件包含所有怪物类型的完整属性。
     */
    private void initMonstersConfigFile() {
        if (!monstersConfigFile.exists()) {
            plugin.saveResource("monsters-config.yml", false);
        }
        YamlFiles.Loaded loaded = monstersGate.load();
        if (!loaded.usable()) {
            plugin.getLogger().severe("monsters-config.yml is unusable; monster model overrides are disabled "
                    + "and the file will NOT be written this run");
            monstersConfigYaml = new YamlConfiguration();
            return;
        }
        monstersConfigYaml = loaded.config();
        // monsters-config-dc-001：记录本次读盘时的 mtime，供 saveMonstersConfig 检测外部修改
        monstersConfigLastModified = monstersConfigFile.lastModified();
    }

    /**
     * 初始化展示配置文件 (display.yml)。
     *
     * <p>首次运行时从 jar 中复制默认文件（验收标准：删掉 display.yml 后重启，插件重新生成它）。
     * 与另外两份不同，本文件<b>只读</b>——不可用时不进「只读降级」，而是直接用
     * {@link DisplayConfig#defaults()} 顶上，渲染端永远有东西可画。</p>
     */
    private void initDisplayFile() {
        if (!displayFile.exists()) {
            plugin.saveResource("display.yml", false);
        }
        YamlFiles.Loaded loaded = displayGate.load();
        if (!loaded.usable()) {
            plugin.getLogger().severe("display.yml is unusable; falling back to the built-in default "
                    + "scoreboard and tab layout for this run");
            displayYaml = new YamlConfiguration();
            displayConfig = DisplayConfig.defaults();
            return;
        }
        displayYaml = loaded.config();
    }

    public GameConfig getActiveConfig() {
        return activeConfig;
    }

    /**
     * game-settings.yml 的读写闸门。
     *
     * <p>仅供 {@code /hilltop reloadconfig} 解封：管理员把文件修好后无需重启服务器
     * （{@link YamlFileGate#reset()} 只清缓存态，下一次读盘仍会重新判定）。</p>
     */
    public YamlFileGate getSettingsGate() {
        return settingsGate;
    }

    /**
     * monsters-config.yml 的读写闸门（与 game-settings 各一份，解封也各自独立）。
     *
     * <p>仅供 {@code /hilltop reloadconfig} 解封，语义同 {@link #getSettingsGate()}。</p>
     */
    public YamlFileGate getMonstersGate() {
        return monstersGate;
    }

    /**
     * 当前生效的展示配置（{@code display.yml} 解析结果，永远不会是 {@code null}）。
     *
     * <p>渲染端（{@code ArenaScoreboard} / {@code TabListManager}）每帧读这份不可变对象；
     * 文件不可用时它就是 {@link DisplayConfig#defaults()}——异常路径不得让侧边栏变空白。</p>
     */
    public DisplayConfig getDisplayConfig() {
        return displayConfig;
    }

    /**
     * display.yml 的读写闸门。
     *
     * <p>仅供 {@code /hilltop reloadconfig} 解封：语义同 {@link #getSettingsGate()}。
     * 本文件没有写入方，这里的「写」一侧永远用不到。</p>
     */
    public YamlFileGate getDisplayGate() {
        return displayGate;
    }

    /**
     * 侧边栏的<b>有效</b>开关：{@code game-settings.yml: scoreboard.enabled} <b>与</b>
     * {@code display.yml: scoreboard.enabled} 同时为 true 才显示。
     *
     * <p>取「与」而不是「或」是为了向后兼容：老服务器的 game-settings.yml 里那个开关一直存在，
     * 服主用它关掉侧边栏的预期必须继续成立；而新文件又需要能独立关掉布局。两个都不改语义，
     * 任一为 false 即视为关闭，零迁移。</p>
     */
    public boolean isScoreboardEffectivelyEnabled() {
        return activeConfig.isScoreboardEnabled() && displayConfig.scoreboard().enabled();
    }

    /**
     * 重新读取并解析 display.yml（{@code /hilltop reloadconfig} 的热更新入口）。
     *
     * <p>先 {@link YamlFileGate#reset()} 再读：管理员把坏文件修好后，闸门的「本进程只读」闩必须由
     * 显式命令打开（不会自动解封，见 {@link YamlFileGate} 类注释）。读盘失败时回退
     * {@link DisplayConfig#defaults()}，绝不留下空白配置。</p>
     */
    public void reloadDisplay() {
        displayGate.reset();
        YamlFiles.Loaded loaded = displayGate.load();
        if (!loaded.usable()) {
            plugin.getLogger().severe("display.yml is unusable; falling back to the built-in default "
                    + "scoreboard and tab layout");
            displayYaml = new YamlConfiguration();
            displayConfig = DisplayConfig.defaults();
            return;
        }
        displayYaml = loaded.config();
        loadDisplayConfig();
    }

    /**
     * 把已读入的 {@code displayYaml} 解析成 {@link DisplayConfig}。
     *
     * <p>解析器本身不打日志（纯函数，可单测），每条 warning 在这里统一加 {@code display.yml:} 前缀后
     * 以 WARN 输出——前缀让控制台一眼看出是哪份文件配错了。</p>
     */
    private void loadDisplayConfig() {
        if (displayYaml == null || displayGate.isReadOnly()) {
            displayConfig = DisplayConfig.defaults();
            return;
        }
        DisplayConfigLoader.Result result = DisplayConfigLoader.parse(displayYaml);
        for (String warning : result.warnings()) {
            // 加载器自身已带 "display.yml " 前缀（它是纯函数、刻意自带上下文），
            // 这里不再拼一次，否则控制台会出现 "display.yml: display.yml ..." 的双前缀。
            plugin.getLogger().warning(warning);
        }
        displayConfig = result.config();
    }

    /**
     * 是否存在尚未落盘的改动（主菜单据此显示保存按钮）。
     *
     * <p><b>语义边界（slice-07-data-consistency-009）：本方法不承诺「磁盘 == 内存」。</b>
     * 它回答的是「有没有该保存的东西」，因此额外计入 {@link #loadTimeNormalizationPending}：
     * 载入期的 upgrade 归一化（wave-timeout / starter-kit）改了内存值但磁盘仍是旧值，
     * 这种情形同样需要保存按钮可见。反过来说，返回 {@code false} 只代表「本进程没改过什么」，
     * 不代表文件内容与内存逐键一致。</p>
     */
    public boolean hasUnsavedChanges() {
        if (loadTimeNormalizationPending) {
            return true;
        }
        GameConfig current = activeConfig.snapshot();
        return !current.equals(lastSavedSnapshot);
    }

    /**
     * GUI 删除波次（P2-33）：内存移除 + 删除记账。
     *
     * <p>只删内存是不够的：{@link #saveToFile()} 对内存里存在的键逐个写值，
     * 被删除的旧键会留在磁盘上（「不整段清空」策略的代价），重启后又会被
     * {@link #loadFromFile()} 读回来（波次复活）。本方法把删除动作记账，
     * 保存时精确清掉这些键——只清 GUI 删过的，不碰管理员手写的键。</p>
     *
     * <p>若之后又新建同号波次，保存时按「内存中存在则不清」跳过，不会误删新波次。</p>
     */
    public void removeWave(int waveNumber) {
        activeConfig.getWaves().remove(waveNumber);
        removedWaveKeys.add(waveNumber);
    }

    /** 解析 nodes.buff-effects 字符串列表（loadFromFile 与外部修改合并共用）。 */
    private Map<PotionEffectType, Integer> parseBuffEffects(List<String> buffEffectList) {
        Map<PotionEffectType, Integer> parsedBuffs = new LinkedHashMap<>();
        for (String raw : buffEffectList) {
            if (raw == null || raw.isBlank()) continue;
            String[] parts = raw.split(":", 2);
            if (parts.length != 2) {
                plugin.getLogger().warning("Invalid nodes.buff-effects entry (expected \"EFFECT:AMPLIFIER\"): " + raw);
                continue;
            }
            PotionEffectType effectType = PotionEffectType.getByName(parts[0].trim().toUpperCase(Locale.ROOT));
            if (effectType == null) {
                plugin.getLogger().warning("Unknown potion effect: " + parts[0].trim() + ", skipping");
                continue;
            }
            try {
                parsedBuffs.put(effectType, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Invalid amplifier in nodes.buff-effects entry: " + raw);
            }
        }
        return parsedBuffs;
    }

    /**
     * 保存前的「外部修改合并」（M-15）。
     *
     * <p>当检测到 game-settings.yml 的 mtime 与上次读/写不一致（被外部手改）时调用：
     * 重读磁盘，对一组<b>没有 GUI/命令编辑入口</b>的键，把磁盘值合并回内存，
     * 使随后 {@link #saveToFile()} 的全量回写不再用陈旧内存值覆盖手改值。</p>
     *
     * <p>语义边界：上列「外部可编辑键」是逐键白名单——它们在本会话中不可能被游戏内改过，
     * 磁盘值总是更新的一方；有 GUI 编辑入口的键不在白名单（游戏内为权威）；
     * 若磁盘键缺失或类型损坏则跳过该键（保留内存值）。</p>
     *
     * <p><b>覆盖完整性（slice-07-data-consistency-003）：</b>凡是 {@link #saveToFile()} 会写、
     * 但既没有 GUI/命令编辑入口、又有运行期消费者的键，都必须在这里——否则手改在下次保存时
     * 被内存旧值静默覆盖。本轮补齐 {@code monsters.<key>.display-name}（{@code WaveManager}
     * 生成怪名）、{@code monsters.<key>.display-egg}、{@code hammer.item-name} /
     * {@code fireball.item-name}（物品显示名）、{@code hammer.use-itemsadder}。
     * 有 GUI 入口的键（health/speed/hook-velocity/cooldown-ticks/... 及全部 hammer.tier.*、
     * fireball.* 数值、waves.*）刻意排除：游戏内为权威。</p>
     *
     * @return 本次合并所依据的那一份磁盘读取结果；{@code null} = 磁盘不可用（未合并，内存值保留）。
     *         调用方<b>必须</b>把这份结果直接用作写回基线，不得再读一次盘
     *         （slice-07-data-consistency-008：两次读盘之间若外部再次落盘，第二次读会成为写回基线，
     *         使第一次读的合并结果对该键静默失效）。
     */
    private YamlFiles.Loaded mergeExternalChanges() {
        YamlFiles.Loaded loaded = settingsGate.load();
        if (!loaded.usable()) {
            // 磁盘不可用时跳过合并（保留内存值），并明确说明本次不会回写
            plugin.getLogger().warning("game-settings.yml is unusable; skipping the external-change merge "
                    + "(in-memory values are kept and nothing will be written)");
            return null;
        }
        YamlConfiguration disk = loaded.config();
        GameConfig baseline = lastSavedSnapshot;

        // 防御性护栏：仅当「当前值仍等于会话基线值」（本会话未修改该键）时才用磁盘值覆盖。
        // 白名单里的键正常都没有编辑入口，该判断等价于恒真；未来若给某键加了编辑入口，
        // 会话内改动仍会胜出，不会被磁盘旧值吞掉。
        if (hasNumber(disk, "wave-timeout-seconds")
                && activeConfig.getWaveTimeoutSeconds() == baseline.getWaveTimeoutSeconds()) {
            activeConfig.setWaveTimeoutSeconds(normalizeWaveTimeout(disk.getInt("wave-timeout-seconds")));
        }
        if (hasNumber(disk, "defeat-countdown-seconds")
                && activeConfig.getDefeatCountdownSeconds() == baseline.getDefeatCountdownSeconds()) {
            activeConfig.setDefeatCountdownSeconds(disk.getInt("defeat-countdown-seconds"));
        }
        if (hasNumber(disk, "villager-health")
                && Double.compare(activeConfig.getVillagerHealth(), baseline.getVillagerHealth()) == 0) {
            activeConfig.setVillagerHealth(disk.getDouble("villager-health"));
        }
        if (hasNumber(disk, "rejoin-timeout-seconds")
                && activeConfig.getRejoinTimeoutSeconds() == baseline.getRejoinTimeoutSeconds()) {
            activeConfig.setRejoinTimeoutSeconds(disk.getInt("rejoin-timeout-seconds"));
        }
        if (hasNumber(disk, "empty-arena-end-seconds")
                && activeConfig.getEmptyArenaEndSeconds() == baseline.getEmptyArenaEndSeconds()) {
            activeConfig.setEmptyArenaEndSeconds(disk.getInt("empty-arena-end-seconds"));
        }
        // fix5 M-04：合并后两个阈值键均已就绪，检查交叉告警（仅告警、不改值）
        warnIfEmptyArenaThresholdCollision();
        if (hasBoolean(disk, "map-restore.enabled")
                && activeConfig.isMapRestoreEnabled() == baseline.isMapRestoreEnabled()) {
            activeConfig.setMapRestoreEnabled(disk.getBoolean("map-restore.enabled"));
        }
        if (hasBoolean(disk, "scoreboard.enabled")
                && activeConfig.isScoreboardEnabled() == baseline.isScoreboardEnabled()) {
            activeConfig.setScoreboardEnabled(disk.getBoolean("scoreboard.enabled"));
        }
        if (hasNumber(disk, "nodes.search-radius")
                && activeConfig.getNodeSearchRadius() == baseline.getNodeSearchRadius()) {
            activeConfig.setNodeSearchRadius(disk.getInt("nodes.search-radius"));
        }
        if (disk.isString("nodes.repair-cost-name")
                && Objects.equals(activeConfig.getRepairCostName(), baseline.getRepairCostName())) {
            activeConfig.setRepairCostName(disk.getString("nodes.repair-cost-name"));
        }
        if (hasNumber(disk, "nodes.repair-cost-amount")
                && activeConfig.getRepairCostAmount() == baseline.getRepairCostAmount()) {
            activeConfig.setRepairCostAmount(disk.getInt("nodes.repair-cost-amount"));
        }
        if (disk.isList("nodes.buff-effects")
                && Objects.equals(activeConfig.getNodeBuffEffects(), baseline.getNodeBuffEffects())) {
            // round5 F4：与 loadFromFile 的同一处守卫必须同改（两份复制品，改一处等于没修）——
            // 磁盘显式 `[]` 是「关闭全部增益」的有效意图，必须真的清空内存；旧实现在这里
            // `!parsedBuffs.isEmpty()` 就直接跳过，于是手改空表在下次保存时被内存旧值覆盖回去。
            List<String> diskBuffList = disk.getStringList("nodes.buff-effects");
            Map<PotionEffectType, Integer> parsedBuffs = parseBuffEffects(diskBuffList);
            activeConfig.getNodeBuffEffects().clear();
            activeConfig.getNodeBuffEffects().putAll(parsedBuffs);
            if (!diskBuffList.isEmpty() && parsedBuffs.isEmpty()) {
                plugin.getLogger().warning("External edit left nodes.buff-effects with no usable entry; "
                        + "nodes will apply no potion effects");
            }
        }
        if (hasNumber(disk, "nodes.buff-refresh-ticks")
                && activeConfig.getNodeBuffRefreshTicks() == baseline.getNodeBuffRefreshTicks()) {
            activeConfig.setNodeBuffRefreshTicks(disk.getInt("nodes.buff-refresh-ticks"));
        }
        if (hasNumber(disk, "nodes.buff-stack-cap")
                && activeConfig.getNodeBuffStackCap() == baseline.getNodeBuffStackCap()) {
            activeConfig.setNodeBuffStackCap(disk.getInt("nodes.buff-stack-cap"));
        }
        if (hasNumber(disk, "nodes.buff-duration-ticks")
                && activeConfig.getNodeBuffDurationTicks() == baseline.getNodeBuffDurationTicks()) {
            activeConfig.setNodeBuffDurationTicks(disk.getInt("nodes.buff-duration-ticks"));
        }
        if (hasNumber(disk, "spawning.spawn-y-offset")
                && activeConfig.getSpawnYOffset() == baseline.getSpawnYOffset()) {
            activeConfig.setSpawnYOffset(disk.getInt("spawning.spawn-y-offset"));
        }
        if (hasNumber(disk, "spawning.airdrop-max-per-dropper")
                && activeConfig.getAirdropMaxPerDropper() == baseline.getAirdropMaxPerDropper()) {
            activeConfig.setAirdropMaxPerDropper(disk.getInt("spawning.airdrop-max-per-dropper"));
        }
        // round5 F6：spawning.spawn-radius-spread 是「写有、读有、有运行期消费者、无编辑入口」的键
        // （saveToFile 写、loadFromFile 读、WaveManager 在 radius-max 未配置时用它做生成点扩散），
        // 却漏在白名单外——载入后手改它，下一次 GUI 保存会被内存旧值静默覆盖。
        // 这是 slice-07-data-consistency-003 那批「补齐白名单」的遗留漏点，补法与上方 spawn-y-offset 同款。
        if (hasNumber(disk, "spawning.spawn-radius-spread")
                && Double.compare(activeConfig.getSpawnRadiusSpread(), baseline.getSpawnRadiusSpread()) == 0) {
            activeConfig.setSpawnRadiusSpread(disk.getDouble("spawning.spawn-radius-spread"));
        }
        if (hasNumber(disk, "shop.currency-drop-chance")
                && Double.compare(activeConfig.getCurrencyDropChance(), baseline.getCurrencyDropChance()) == 0) {
            activeConfig.setCurrencyDropChance(disk.getDouble("shop.currency-drop-chance"));
        }
        if (hasNumber(disk, "shop.currency-drop-min")
                && activeConfig.getCurrencyDropMin() == baseline.getCurrencyDropMin()) {
            activeConfig.setCurrencyDropMin(disk.getInt("shop.currency-drop-min"));
        }
        if (hasNumber(disk, "shop.currency-drop-max")
                && activeConfig.getCurrencyDropMax() == baseline.getCurrencyDropMax()) {
            activeConfig.setCurrencyDropMax(disk.getInt("shop.currency-drop-max"));
        }
        if (hasBoolean(disk, "starter-kit.enabled")
                && activeConfig.isStarterKitEnabled() == baseline.isStarterKitEnabled()) {
            activeConfig.setStarterKitEnabled(disk.getBoolean("starter-kit.enabled"));
        }
        if (disk.isList("starter-kit.items")
                && Objects.equals(activeConfig.getStarterKitItems(), baseline.getStarterKitItems())) {
            // 与 loadFromFile 同语义：键存在即以磁盘为准（含显式空列表 = 清空套装）
            activeConfig.setStarterKitItems(normalizeStarterKitItems(parseStarterKitItems(disk.getMapList("starter-kit.items"))));
        }
        // slice-07-data-consistency-003：以下四类键「有消费者、无 GUI/命令入口」，
        // 只能靠手改 game-settings.yml 配置；不在白名单就会被全量回写静默吞掉。
        // 基线守卫同上：本会话内未改过（仍等于快照值）才让磁盘值胜出。
        if (disk.isString("hammer.item-name")
                && Objects.equals(activeConfig.getHammerItemName(), baseline.getHammerItemName())) {
            activeConfig.setHammerItemName(disk.getString("hammer.item-name"));
        }
        if (disk.isString("fireball.item-name")
                && Objects.equals(activeConfig.getFireballItemName(), baseline.getFireballItemName())) {
            activeConfig.setFireballItemName(disk.getString("fireball.item-name"));
        }
        if (hasBoolean(disk, "hammer.use-itemsadder")
                && activeConfig.isHammerUseItemsAdder() == baseline.isHammerUseItemsAdder()) {
            activeConfig.setHammerUseItemsAdder(disk.getBoolean("hammer.use-itemsadder"));
        }
        // E2 按怪种掉落（monsters.<key>.drop-min/drop-max）+ 显示标识（display-name/display-egg）：
        // 均无 GUI 编辑入口 → 白名单合并，手改后经 GUI 保存不被内存旧值吞掉。
        // 逐个怪种按基线守卫比对（模式同上）。
        for (Map.Entry<String, GameConfig.MonsterConfig> entry : activeConfig.getMonsters().entrySet()) {
            GameConfig.MonsterConfig mc = entry.getValue();
            GameConfig.MonsterConfig baseMc = baseline.getMonsters().get(entry.getKey());
            if (mc == null || baseMc == null) continue;
            String mpath = "monsters." + entry.getKey() + ".";
            if (hasNumber(disk, mpath + "drop-min") && mc.getDropMin() == baseMc.getDropMin()) {
                mc.setDropMin(disk.getInt(mpath + "drop-min"));
            }
            if (hasNumber(disk, mpath + "drop-max") && mc.getDropMax() == baseMc.getDropMax()) {
                mc.setDropMax(disk.getInt(mpath + "drop-max"));
            }
            // slice-07-data-consistency-003：display-name 被 WaveManager:564-565 消费、
            // MonsterSettingsMenu 无编辑入口（只有 IA 模型 id / 装备 / 数值），只可能手改。
            if (disk.isString(mpath + "display-name")
                    && Objects.equals(mc.getDisplayName(), baseMc.getDisplayName())) {
                mc.setDisplayName(disk.getString(mpath + "display-name"));
            }
            // display-egg 同理：只在 loadFromFile 读盘（漏键保留默认），GUI 用的是硬编码图标表。
            String eggName = disk.getString(mpath + "display-egg");
            if (eggName != null && mc.getDisplayEgg() == baseMc.getDisplayEgg()) {
                Material eggMat = Material.getMaterial(eggName);
                if (eggMat != null) {
                    mc.setDisplayEgg(eggMat);
                } else {
                    plugin.getLogger().warning("Unknown material: " + eggName + ", keeping the in-memory display-egg");
                }
            }
        }
        return loaded;
    }

    /** 键存在且为数值（int/double 皆可）。 */
    private static boolean hasNumber(YamlConfiguration yaml, String path) {
        return yaml.get(path) instanceof Number;
    }

    /** 键存在且为布尔。 */
    private static boolean hasBoolean(YamlConfiguration yaml, String path) {
        return yaml.get(path) instanceof Boolean;
    }

    /**
     * 读一个波次相关的非负整数（{@code total-mobs} / compositions 的 {@code count} / {@code weight}）。
     *
     * <p><b>为什么不能裸用 {@code getInt}</b>：缺键时 {@code getInt} 返回 0，于是「没写」与
     * 「写了 0」在内存里不可区分 —— 对 {@code total-mobs} 就是把该波静默变成 0 只
     * （{@code WaveManager} 算出 {@code remaining = 0}，整波空转）；对 {@code weight} 是把该
     * 怪种从权重回退路径里静默剔除。同一坑的第二种触发方式更隐蔽：磁盘写 {@code count: "9"}
     * （带引号的数字被 YAML 读成 String）时裸 {@code getInt} 同样返回 0。本方法把三种非法形态
     * （缺键 / 非数值 / 负数）全部显式化喵。</p>
     *
     * @param section       波次或 compositions 条目的 section；null 视为缺键
     * @param key           子键名
     * @param fallback      缺键 / 非数值时的回落值
     * @param reportMissing true = 缺键也记一条问题（total-mobs 用）；false = 缺键是合法缺省（count/weight 用）
     * @param label         问题条目里的可读定位（如 {@code 7.total-mobs}）
     * @param problems      汇总收集器（调用方循环结束后一次性 warning）
     */
    private int readWaveInt(ConfigurationSection section, String key, int fallback,
                           boolean reportMissing, String label, Set<String> problems) {
        if (section == null || !section.contains(key)) {
            if (reportMissing) {
                problems.add(label + " (missing -> default " + fallback + ")");
            }
            return fallback;
        }
        Object raw = section.get(key);
        if (!(raw instanceof Number number)) {
            problems.add(label + " (" + raw + " is not a number -> default " + fallback + ")");
            return fallback;
        }
        int value = number.intValue();
        if (value < 0) {
            problems.add(label + " (" + value + " is negative -> 0)");
            return 0;
        }
        return value;
    }

    /**
     * 按磁盘内容构造一个 compositions 条目。
     *
     * <p><b>判据必须是 {@link #isSpawnableMobKey}，不能是「该波内存里有没有这个怪种」</b>：
     * 后者在「波次刚由磁盘新建」时对每一个合法怪种都成立（空 map），会把模板里的怪种全部判成
     * 未知、既丢内容又打一条误导性 warning —— 这正是 F2 的可观测性那一半喵。</p>
     *
     * @return null = 该条目应被丢弃（未知怪种，或不是 count/weight 映射）
     */
    private GameConfig.MobComposition readComposition(String waveLabel, ConfigurationSection compsSection,
                                                     String mobKey, Set<String> unknownMobs,
                                                     Set<String> valueProblems) {
        if (!isSpawnableMobKey(mobKey)) {
            unknownMobs.add(waveLabel + "." + mobKey);
            return null;
        }
        ConfigurationSection values = compsSection.getConfigurationSection(mobKey);
        if (values == null) {
            valueProblems.add(waveLabel + "." + mobKey + " (expected a 'count/weight' mapping)");
            return null;
        }
        int count = readWaveInt(values, "count", 0, false, waveLabel + "." + mobKey + ".count", valueProblems);
        int weight = readWaveInt(values, "weight", 1, false, waveLabel + "." + mobKey + ".weight", valueProblems);
        return new GameConfig.MobComposition(count, weight);
    }

    /**
     * 该 compositions 键在运行期是否真的能刷出东西。
     *
     * <p><b>判据必须与消费侧对齐</b>：{@code WaveManager#spawnCustomMob} 是两级分派 ——
     * 三个内置键（{@code explode-beetle} / {@code hook-claw-hunter} / {@code flying-dropper}）
     * 走专用 AI 分支，其余键一律 {@code EntityType.valueOf(...)} 后 {@code world.spawnEntity(...)}
     * 生成原版怪。所以「monsters 段有该键」只是第一级；只判 monsters 段会把运行期本来能刷的
     * 原版怪键（如 {@code zombie}）判成未知并静默丢弃，而 warning 文案还会说「合法键就是
     * monsters 段那些键」—— 把一条其实写对了的配置说成拼写错误喵。</p>
     *
     * <p><b>第二级复用 {@link #parseSpawnableEntityType}</b>（本类既有方法，零新增校验代码）：
     * 它已经含 {@code Mob.class.isAssignableFrom} 守卫，因此 {@code arrow} / {@code item} /
     * {@code player} 这类「名字合法但 {@code spawnEntity} 会抛异常」的类型会被拒掉，
     * 与 {@code loadMonstersConfig} 里 {@code entity-type} 的校验同款喵。</p>
     */
    private boolean isSpawnableMobKey(String key) {
        if (key == null || key.isBlank()) return false;
        String normalized = key.trim();
        // 内置怪种：WaveManager 的 switch 与 monsters.get 都先 toLowerCase(Locale.ROOT)，
        // 因此这里也必须大小写不敏感，否则 on-disk 的 "Explode-Beetle" 能跑却被判未知。
        if (activeConfig.getMonsters().containsKey(normalized.toLowerCase(Locale.ROOT))) return true;
        return parseSpawnableEntityType(normalized) != null;
    }

    /**
     * 解析 starter-kit.items 列表（loadFromFile 与外部修改合并共用）。
     * 非法条目记 warning 后跳过。
     */
    private List<GameConfig.StarterKitItem> parseStarterKitItems(List<Map<?, ?>> maps) {
        List<GameConfig.StarterKitItem> parsedKit = new ArrayList<>();
        for (Map<?, ?> map : maps) {
            Object materialRaw = map.get("material");
            if (!(materialRaw instanceof String materialName) || materialName.isBlank()) {
                plugin.getLogger().warning("Invalid starter-kit item (missing material), skipping");
                continue;
            }
            Material material = Material.getMaterial(materialName.trim().toUpperCase(Locale.ROOT));
            if (material == null || !material.isItem()) {
                plugin.getLogger().warning("Unknown starter-kit material: " + materialName + ", skipping");
                continue;
            }
            Object amountRaw = map.get("amount");
            int amount = (amountRaw instanceof Number number) ? number.intValue() : 1;
            parsedKit.add(new GameConfig.StarterKitItem(material, Math.max(1, amount)));
        }
        return parsedKit;
    }

    /**
     * starter-kit 升级归一化（装备线轮）：磁盘列表恰好等于旧默认
     * （石剑/面包/火把，见 {@link GameConfig#isLegacyDefaultStarterKit}）时
     * 升级为新默认（追加皮革四件套，D-2 决策）。
     *
     * <p>与 {@code normalizeWaveTimeout} 同款先例：配置文件不会被版本升级覆盖，
     * 且 starter-kit.items 键存在时磁盘值整体优先——不做归一化的话，存量服务器
     * 升级后永远不会收到皮革套。服主改过列表（任意一项不同）则原样保留。
     * 归一化只改内存值，随下一次 {@link #saveToFile()} 落盘。</p>
     */
    private List<GameConfig.StarterKitItem> normalizeStarterKitItems(List<GameConfig.StarterKitItem> parsed) {
        if (GameConfig.isLegacyDefaultStarterKit(parsed)) {
            plugin.getLogger().warning("Legacy default starter-kit detected (stone sword / bread / torches), "
                    + "upgraded to the new default (leather armor set added)");
            return GameConfig.defaultStarterKitItems();
        }
        return parsed;
    }

    /**
     * fix4 反馈12：波次超时保险值的升级归一化。
     *
     * <p>旧版本（fix3 及更早）的默认值是 120；本版本起默认禁用（0）。存量服务器的
     * {@code game-settings.yml} 磁盘上仍是 120，若直接沿用会把「已改为禁用」的新默认
     * 又打开回去。此方法把恰好等于旧默认值 {@link GameConfig#LEGACY_WAVE_TIMEOUT_SECONDS}
     * 的存量值归一化为 0（禁用）；其它值（含 <=0 与其它正数）原样保留——
     * 管理员显式设定过的值不会被吞。</p>
     *
     * <p>代价：某服主若恰好在旧版本手写过 120，也会被一并视为「旧默认」而归零；
     * 这是有意的取舍（作者反馈明确要求默认禁用，且 120 正是旧默认值本身）。</p>
     */
    private static int normalizeWaveTimeout(int raw) {
        return raw == GameConfig.LEGACY_WAVE_TIMEOUT_SECONDS ? 0 : raw;
    }

    /**
     * fix5 M-04：空置自动结束与重连超时的阈值交叉提示。
     *
     * <p>两者都对「全员离线」场景计时。代码层已让「有重连窗口时空置出口让位」（见
     * {@code ArenaInstance} 空置任务的 {@code hasPendingRejoinForArena} 守卫），
     * 此处仅提示管理员把两个阈值错开（empty-arena-end-seconds &gt; rejoin-timeout-seconds），
     * 使两条出口的先后顺序按意图可预期。</p>
     *
     * <p>等级为 {@code info}：这是提示性建议而非故障，出厂配置本身即可正常工作。
     * 旧出厂默认组合（empty=60 / rejoin=60，fix5 及更早的模板）不提示——存量服务器
     * 什么都没配错，不该每次加载都收到一条提示；出厂默认已改为 120 / 60，不再触发。</p>
     */
    private void warnIfEmptyArenaThresholdCollision() {
        int empty = activeConfig.getEmptyArenaEndSeconds();
        int rejoin = activeConfig.getRejoinTimeoutSeconds();
        boolean legacyFactoryPair = empty == LEGACY_DEFAULT_EMPTY_ARENA_END_SECONDS
                && rejoin == LEGACY_DEFAULT_REJOIN_TIMEOUT_SECONDS;
        if (empty > 0 && rejoin > 0 && empty <= rejoin && !legacyFactoryPair) {
            plugin.getLogger().info("empty-arena-end-seconds (" + empty
                    + ") is not greater than rejoin-timeout-seconds (" + rejoin
                    + "): with everyone offline the two timers overlap; set the empty-arena threshold higher "
                    + "than the rejoin timeout");
        }
    }

    /** 旧出厂模板（fix5 及更早）的 empty-arena-end-seconds 默认值，仅用于识别「未配置过」的组合。 */
    private static final int LEGACY_DEFAULT_EMPTY_ARENA_END_SECONDS = 60;
    /** 旧出厂模板（与上一条配套）的 rejoin-timeout-seconds 默认值；现行默认仍为 60。 */
    private static final int LEGACY_DEFAULT_REJOIN_TIMEOUT_SECONDS = 60;

    /**
     * 把内存配置写入 game-settings.yml（并顺带写 monsters-config.yml）。
     *
     * <p><b>刻意不写 display.yml：</b>该文件是纯手写布局，插件没有任何编辑入口，也没有行编辑器
     * 能在保留注释的前提下改某一行——一旦回写，{@code YamlConfiguration#saveToString()} 会把
     * 头部说明与逐行注释全部抹掉，而注释正是这份模板的唯一文档。所以它只有读的一方
     * （{@link #initDisplayFile()} / {@link #reloadDisplay()}），任何保存路径都不得碰它。</p>
     *
     * @return true = 两份文件都写成功；false = 至少一份失败（只读降级或 IO 错误，已打日志）
     */
    public boolean saveToFile() {
        // 只读降级：读盘已判不可用时绝不回写（否则空/半截配置会被固化）
        if (!settingsGate.writable()) {
            return false;
        }

        // M-15：save 前检测外部修改。文件 mtime 与上次读/写记录不一致 → 有人在游戏外
        // 手改过文件：① 对「无 GUI 编辑入口」的键做磁盘值合并（见 mergeExternalChanges）；
        // ② 把内存 settingsYaml 重定位到磁盘最新内容，使本方法不写回的键
        //（手写未知键、airdrop-mob-type 等只读键）保留外部改动而不是被旧内存副本覆盖。
        //
        // slice-07-data-consistency-008：合并所依据的那一次读盘直接用作写回基线，此处不再二次读盘。
        // 原先 mergeExternalChanges 内部读一次、这里再读一次，两次之间若外部再次落盘，
        // 第二次读会成为写回基线，使第一次读合并来的白名单键值静默失效。
        if (settingsFile.lastModified() != settingsLastModified) {
            YamlFiles.Loaded merged = mergeExternalChanges();
            if (merged == null || settingsGate.isReadOnly()) {
                plugin.getLogger().warning("Refusing to save game-settings.yml (unusable after the external-change check)");
                return false;
            }
            settingsYaml = merged.config();
            plugin.getLogger().info("game-settings.yml was modified externally; external changes merged and saved "
                    + "(keys changed via the GUI in this session still win)");
        }

        settingsYaml.set("world-name", activeConfig.getWorldName());
        settingsYaml.set("core.x", activeConfig.getCoreX());
        settingsYaml.set("core.y", activeConfig.getCoreY());
        settingsYaml.set("core.z", activeConfig.getCoreZ());
        settingsYaml.set("min-players", activeConfig.getMinPlayers());
        settingsYaml.set("max-players", activeConfig.getMaxPlayers());
        settingsYaml.set("victory-waves", activeConfig.getVictoryWaves());
        settingsYaml.set("wave-interval-seconds", activeConfig.getWaveIntervalSeconds());
        settingsYaml.set("wave-timeout-seconds", activeConfig.getWaveTimeoutSeconds());
        settingsYaml.set("defeat-countdown-seconds", activeConfig.getDefeatCountdownSeconds());
        settingsYaml.set("villager-health", activeConfig.getVillagerHealth());
        settingsYaml.set("rejoin-timeout-seconds", activeConfig.getRejoinTimeoutSeconds());
        settingsYaml.set("empty-arena-end-seconds", activeConfig.getEmptyArenaEndSeconds());
        settingsYaml.set("map-restore.enabled", activeConfig.isMapRestoreEnabled());
        settingsYaml.set("scoreboard.enabled", activeConfig.isScoreboardEnabled());

        settingsYaml.set("hammer.material", activeConfig.getHammerMaterial().name());
        settingsYaml.set("hammer.itemsadder-id", activeConfig.getHammerItemsAdderId());
        settingsYaml.set("hammer.custom-model-data", activeConfig.getHammerCustomModelData());
        settingsYaml.set("hammer.use-itemsadder", activeConfig.isHammerUseItemsAdder());
        settingsYaml.set("hammer.base-damage", activeConfig.getHammerBaseDamage());
        settingsYaml.set("hammer.effect-radius", activeConfig.getHammerEffectRadius());
        settingsYaml.set("hammer.aftershock-ticks", activeConfig.getHammerAftershockTicks());
        settingsYaml.set("hammer.smash-timeout-ticks", activeConfig.getHammerSmashTimeoutTicks());
        settingsYaml.set("hammer.visual-display-ticks", activeConfig.getHammerVisualDisplayTicks());
        settingsYaml.set("hammer.cooldown-seconds", activeConfig.getHammerCooldownSeconds());
        settingsYaml.set("hammer.max-uses", activeConfig.getHammerMaxUses());
        // tier.low.charge 已删除（fall < medium 即 low，阈值无用）；磁盘残留键在此清掉，避免复活
        settingsYaml.set("hammer.tier.low.charge", null);
        settingsYaml.set("hammer.tier.low.damage", activeConfig.getHammerLowDamage());
        settingsYaml.set("hammer.tier.low.radius", activeConfig.getHammerLowRadius());
        settingsYaml.set("hammer.tier.medium.charge", activeConfig.getHammerMediumCharge());
        settingsYaml.set("hammer.tier.medium.damage", activeConfig.getHammerMediumDamage());
        settingsYaml.set("hammer.tier.medium.radius", activeConfig.getHammerMediumRadius());
        settingsYaml.set("hammer.tier.high.charge", activeConfig.getHammerHighCharge());
        settingsYaml.set("hammer.tier.high.damage", activeConfig.getHammerHighDamage());
        settingsYaml.set("hammer.tier.high.radius", activeConfig.getHammerHighRadius());
        // 旧版兼容字段
        // fix-round1 pattern-8：这五个倍率/阈值键自 v1.1 起**没有任何玩法消费者**（伤害与范围
        // 由 tier 三档直接决定），但旧实现仍在每次保存时把它们写回磁盘——等于每一轮
        // /hilltop config 保存都在主动维护一批死键，与同方法上方 tier.low.charge 的双向墓碑
        // 做法正好相反。这里改为同款墓碑：保存时置 null，加载时忽略（见 loadFromFile）。
        tombstoneDeprecatedHammerDamageKeys();
        // slice-07-data-consistency-007：hammer.item-name / fireball.item-name 是**运行期可选逃生舱**——
        // 出厂 templates 刻意不声明它们（正常情况走 messages.yml 的 item.hammer-name / item.fireball-name），
        // 但两个键都有活跃读者（HammerListener#hammerName / FireballListener#fireballName）与载入侧读取
        //（本文件 loadFromFile 的 contains 守卫）。因此保留写入：一旦服主手加该键，本全量回写不得把它抹掉。
        // 副作用：保存后文件里会出现这两个键，属**已知的单向 schema 漂移**，不是漏写模板。
        settingsYaml.set("hammer.item-name", activeConfig.getHammerItemName());

        settingsYaml.set("nodes.base-health", activeConfig.getNodeBaseHealth());
        settingsYaml.set("nodes.buff-radius", activeConfig.getNodeBuffRadius());
        settingsYaml.set("nodes.self-destruct-multiplier", activeConfig.getNodeSelfDestructMultiplier());
        settingsYaml.set("nodes.search-radius", activeConfig.getNodeSearchRadius());
        settingsYaml.set("nodes.repair-cost-name", activeConfig.getRepairCostName());
        settingsYaml.set("nodes.repair-cost-item", activeConfig.getRepairCostItem().name());
        settingsYaml.set("nodes.repair-cost-amount", activeConfig.getRepairCostAmount());
        settingsYaml.set("nodes.repair-amount-per-cost", activeConfig.getRepairAmountPerCost());

        // 节点增益效果：格式 "效果名:等级"（等级为 amplifier，0 = I 级）
        List<String> buffEffectList = new ArrayList<>();
        for (Map.Entry<PotionEffectType, Integer> buff : activeConfig.getNodeBuffEffects().entrySet()) {
            // PotionEffectType -> NamespacedKey -> String（如 "resistance"），再转大写写入配置
            String effectName = buff.getKey().getKey().getKey();
            buffEffectList.add(effectName.toUpperCase(Locale.ROOT) + ":" + buff.getValue());
        }
        settingsYaml.set("nodes.buff-effects", buffEffectList);
        settingsYaml.set("nodes.buff-refresh-ticks", activeConfig.getNodeBuffRefreshTicks());
        settingsYaml.set("nodes.buff-stack-cap", activeConfig.getNodeBuffStackCap());
        settingsYaml.set("nodes.buff-duration-ticks", activeConfig.getNodeBuffDurationTicks());

        settingsYaml.set("spawning.spawn-radius-min", activeConfig.getSpawnRadiusMin());
        settingsYaml.set("spawning.spawn-radius-max", activeConfig.getSpawnRadiusMax());
        // fix-round1 pattern-4b：spawn-radius-spread 此前是「代码里有、YAML 里没有」的活配置
        // （WaveManager:756 用它做生成点随机扩散，值恒为代码默认 3.0，服主无法调整也无法发现）。
        // 补上读写两端后它才真正可配；旧文件缺该键时保持代码默认，行为不变。
        settingsYaml.set("spawning.spawn-radius-spread", activeConfig.getSpawnRadiusSpread());
        settingsYaml.set("spawning.global-mob-cap", activeConfig.getGlobalMobCap());
        settingsYaml.set("spawning.mob-cap-per-player", activeConfig.getMobCapPerPlayer());
        settingsYaml.set("spawning.spawn-y-offset", activeConfig.getSpawnYOffset());
        settingsYaml.set("spawning.airdrop-max-per-dropper", activeConfig.getAirdropMaxPerDropper());

        // ---- 烈焰蛋配置 ----
        settingsYaml.set("fireball.material", activeConfig.getFireballMaterial().name());
        settingsYaml.set("fireball.speed", activeConfig.getFireballSpeed());
        settingsYaml.set("fireball.damage", activeConfig.getFireballDamage());
        settingsYaml.set("fireball.explosion-radius", activeConfig.getFireballExplosionRadius());
        settingsYaml.set("fireball.cooldown-ticks", activeConfig.getFireballCooldownTicks());
        settingsYaml.set("fireball.max-travel-ticks", activeConfig.getFireballMaxTravelTicks());
        settingsYaml.set("fireball.knockback", activeConfig.getFireballKnockback());
        settingsYaml.set("fireball.proximity-fuse", activeConfig.getFireballProximityFuse());
        // 与 hammer.item-name 同款（slice-07-data-consistency-007）：出厂模板不声明，但键被
        // FireballListener#fireballName 消费、且载入侧按 contains 读取，保留写入以尊重手加配置。
        settingsYaml.set("fireball.item-name", activeConfig.getFireballItemName());

        // ---- 村民商店货币掉落（fix4：每杀必掉、数量浮动） ----
        settingsYaml.set("shop.currency-drop-chance", activeConfig.getCurrencyDropChance());
        settingsYaml.set("shop.currency-drop-min", activeConfig.getCurrencyDropMin());
        settingsYaml.set("shop.currency-drop-max", activeConfig.getCurrencyDropMax());

        // ---- 开局初始套装 (starter-kit, STORY-1.2) ----
        settingsYaml.set("starter-kit.enabled", activeConfig.isStarterKitEnabled());
        List<Map<String, Object>> starterKitList = new ArrayList<>();
        for (GameConfig.StarterKitItem item : activeConfig.getStarterKitItems()) {
            if (item == null || item.getMaterial() == null) continue;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("material", item.getMaterial().name());
            map.put("amount", Math.max(1, item.getAmount()));
            starterKitList.add(map);
        }
        settingsYaml.set("starter-kit.items", starterKitList);

        // 不整段清空 sections，以保留管理员手写的键与注释；代价是已删除的怪物/波次可能残留旧键
        for (Map.Entry<String, GameConfig.MonsterConfig> entry : activeConfig.getMonsters().entrySet()) {
            String key = entry.getKey();
            GameConfig.MonsterConfig mc = entry.getValue();
            String path = "monsters." + key + ".";
            settingsYaml.set(path + "display-egg", mc.getDisplayEgg().name());
            settingsYaml.set(path + "health", mc.getHealth());
            settingsYaml.set(path + "speed", mc.getSpeed());
            settingsYaml.set(path + "hook-velocity", mc.getHookVelocity());
            settingsYaml.set(path + "display-name", mc.getDisplayName());
            settingsYaml.set(path + "explode-damage-multiplier", mc.getExplodeDamageMultiplier());
            if ("explode-beetle".equals(key)) {
                settingsYaml.set(path + "entity-explosion", mc.isEntityExplosion());
            }
            settingsYaml.set(path + "cooldown-ticks", mc.getCooldownTicks());
            settingsYaml.set(path + "stun-duration-ticks", mc.getStunDurationTicks());
            settingsYaml.set(path + "airdrop-mob-count", mc.getAirdropMobCount());
            // E2 按怪种掉落：读有写有完整接线（-1 = 显式未配置走全局兜底，原样写回保往返一致）
            settingsYaml.set(path + "drop-min", mc.getDropMin());
            settingsYaml.set(path + "drop-max", mc.getDropMax());
        }

        settingsYaml.set("waves.default.total-mobs", activeConfig.getDefaultWave().getTotalMobs());
        for (Map.Entry<String, GameConfig.MobComposition> comp : activeConfig.getDefaultWave().getCompositions().entrySet()) {
            settingsYaml.set("waves.default.compositions." + comp.getKey() + ".count", comp.getValue().getCount());
            settingsYaml.set("waves.default.compositions." + comp.getKey() + ".weight", comp.getValue().getWeight());
        }
        for (Map.Entry<Integer, GameConfig.WaveConfig> entry : activeConfig.getWaves().entrySet()) {
            String wavePath = "waves." + entry.getKey() + ".";
            settingsYaml.set(wavePath + "total-mobs", entry.getValue().getTotalMobs());
            for (Map.Entry<String, GameConfig.MobComposition> comp : entry.getValue().getCompositions().entrySet()) {
                settingsYaml.set(wavePath + "compositions." + comp.getKey() + ".count", comp.getValue().getCount());
                settingsYaml.set(wavePath + "compositions." + comp.getKey() + ".weight", comp.getValue().getWeight());
            }
        }

        // P2-33：清掉本次会话经 GUI（removeWave）删除过的波次键——只清记账过的，
        // 不碰管理员手写进文件、内存里从未存在过的其它键。
        // 若同号波次被重新创建（内存中又存在），跳过清除，避免误删新波次。
        for (Integer removedWave : removedWaveKeys) {
            if (!activeConfig.getWaves().containsKey(removedWave)) {
                settingsYaml.set("waves." + removedWave, null);
            }
        }

        if (!settingsGate.write(settingsYaml)) {
            plugin.getLogger().severe("Failed to save game-settings.yml"
                    + (settingsGate.isReadOnly() ? " (read-only this run: the file was unreadable at load time)" : ""));
            return false;
        }
        // round5 N2：墓碑的唯一职责就是上面这一次 game-settings.yml 写盘里把 waves.<n> 清掉，
        // 写成功即已兑现，因此在这里清空 —— 不能像旧实现那样留到 monsters 写成功之后再清。
        // 旧位置制造出一个「磁盘已删、内存已删、记账仍存活」的中间态：F-001 的 monsters 门控
        // 让它跨过了一次完整保存，而 loadFromFile（P2 之后的 clear() + 按磁盘重建）/ revertAll
        // 会在不维护记账的情况下整份替换内存。判据读的是「当前内存」（下面的
        // !activeConfig.getWaves().containsKey(removedWave)），一旦内存里重新长出该波次号，
        // 判据就反向翻转：跳过清除，同时常规全量写回把该波次重新写进磁盘 —— 删除被永久撤销。
        // 注意：清空墓碑不影响 F-001 的本意。F-001 要保住的是「快照不推进 ⇒ 保存按钮继续亮 ⇒
        // 未落盘的改动会重试」，那条只依赖 lastSavedSnapshot（见下方 else 分支），与墓碑无关。
        removedWaveKeys.clear();
        boolean monstersSaved = saveMonstersConfig();
        if (monstersSaved) {
            lastSavedSnapshot = activeConfig.snapshot();
            // 归一化产物已随本次 saveToFile 落盘（game-settings.yml 在 :740 已写成功，否则此处不会到达）
            loadTimeNormalizationPending = false;
        } else {
            // 不能再承诺「下次保存会重试波次删除」—— 删除已在上面这次 settings 写盘里落定，
            // 本分支只保留「未保存」状态，重试的是内存里其余那些还没落盘的改动。
            plugin.getLogger().warning("monsters-config.yml was NOT saved; keeping the unsaved-changes state "
                    + "so the next save retries it (game-settings.yml itself was written successfully)");
        }
        settingsLastModified = settingsFile.lastModified();
        return monstersSaved;
    }

    /**
     * 将怪物完整配置保存到 monsters-config.yml。
     *
     * @return true = 已落盘；false = 只读降级或 IO 失败（已打日志）
     */
    private boolean saveMonstersConfig() {
        if (!monstersGate.writable()) {
            return false;
        }
        // monsters-config-dc-001：写前检测外部修改，与 game-settings.yml 的 M-15 对称。
        // 载入之后若文件 mtime 变化，说明有人手改过：先用磁盘最新内容充当写回基线
        // （写集合之外的键、注释因此得以保留），再写内存值——否则手改会被载入期旧快照
        // 静默覆盖，且一行日志都没有。
        if (monstersConfigFile.lastModified() != monstersConfigLastModified) {
            YamlFiles.Loaded adopted = adoptExternalMonsterChanges();
            if (adopted == null || monstersGate.isReadOnly()) {
                plugin.getLogger().warning("Refusing to save monsters-config.yml (unusable after the external-change "
                        + "check); the file on disk is left untouched");
                return false;
            }
            monstersConfigYaml = adopted.config();
        }
        // 不整段清空，以保留管理员手写的键与注释；代价是已删除的怪物可能残留旧键
        for (Map.Entry<String, GameConfig.MonsterConfig> entry : activeConfig.getMonsters().entrySet()) {
            String key = entry.getKey();
            GameConfig.MonsterConfig mc = entry.getValue();
            String path = key + ".";
            monstersConfigYaml.set(path + "entity-type",
                    mc.getEntityType() != null ? mc.getEntityType().name() : "");
            monstersConfigYaml.set(path + "display-name", mc.getDisplayName());
            if (mc.getDisplayEgg() != null) monstersConfigYaml.set(path + "display-egg", mc.getDisplayEgg().name());
            monstersConfigYaml.set(path + "health", mc.getHealth());
            monstersConfigYaml.set(path + "speed", mc.getSpeed());
            monstersConfigYaml.set(path + "base-damage", mc.getBaseDamage());
            monstersConfigYaml.set(path + "itemsadder-id", mc.getItemsAdderId() != null ? mc.getItemsAdderId() : "");
            monstersConfigYaml.set(path + "custom-model-data", mc.getCustomModelData());
            monstersConfigYaml.set(path + "main-hand", mc.getMainHandItem() != null ? mc.getMainHandItem().name() : "");
            monstersConfigYaml.set(path + "off-hand", mc.getOffHandItem() != null ? mc.getOffHandItem().name() : "");
            monstersConfigYaml.set(path + "helmet", mc.getHelmet() != null ? mc.getHelmet().name() : "");
            monstersConfigYaml.set(path + "chestplate", mc.getChestplate() != null ? mc.getChestplate().name() : "");
            monstersConfigYaml.set(path + "leggings", mc.getLeggings() != null ? mc.getLeggings().name() : "");
            monstersConfigYaml.set(path + "boots", mc.getBoots() != null ? mc.getBoots().name() : "");
            monstersConfigYaml.set(path + "explode-damage-multiplier", mc.getExplodeDamageMultiplier());
            monstersConfigYaml.set(path + "hook-velocity", mc.getHookVelocity());
            monstersConfigYaml.set(path + "cooldown-ticks", mc.getCooldownTicks());
            monstersConfigYaml.set(path + "stun-duration-ticks", mc.getStunDurationTicks());
            monstersConfigYaml.set(path + "airdrop-mob-count", mc.getAirdropMobCount());
        }
        if (!monstersGate.write(monstersConfigYaml)) {
            plugin.getLogger().severe("Failed to save monsters-config.yml"
                    + (monstersGate.isReadOnly() ? " (read-only this run: the file was unreadable at load time)" : ""));
            return false;
        }
        // 写盘成功 → 记录新的 mtime 作为下一次「外部修改检测」的基线
        monstersConfigLastModified = monstersConfigFile.lastModified();
        return true;
    }

    /**
     * monsters-config.yml 的「外部修改采纳」（monsters-config-dc-001，与
     * {@link #mergeExternalChanges()} 对称的怪物侧版本）。
     *
     * <p>检测到 mtime 与上次读/写记录不一致时调用：重读磁盘，把这份<b>磁盘内容本身</b>作为
     * {@link #saveMonstersConfig()} 的写回基线。与 settings 侧的关键差别在于「合并」的含义：</p>
     *
     * <ul>
     *   <li>settings 侧的白名单合并是「内存值 ← 磁盘值」（那些键没有游戏内编辑入口）。</li>
     *   <li>怪物侧写集合里的 19 个键<b>全部</b>有编辑入口（{@code MonsterSettingsMenu} /
     *       {@code WaveSettingsMenu}：数值、IA id、材质循环），所以本会话内经 GUI 改过的值必须胜出；
     *       为此逐个键套用与 {@link #mergeExternalChanges()} 相同的<b>基线守卫</b>：只有「内存值仍等于
     *       上次落盘快照」时才让磁盘值胜出——那恰好等价于「本会话没动过这个键，所以磁盘上的变化一定
     *       来自手改」。见 {@link #mergeExternalMonsterValues(YamlConfiguration)}。</li>
     *   <li>写集合<b>之外</b>的键（外部新加的怪种段、{@code entity-explosion}、
     *       {@code drop-min/drop-max}、任何手写自定义键）以及全部注释：旧实现以载入期的
     *       {@code monstersConfigYaml} 为基线，这些内容在覆盖写中一并消失。采纳磁盘内容作基线后，
     *       它们原样保留。</li>
     * </ul>
     *
     * @return 本次采纳所依据的那一份磁盘读取结果（调用方<b>必须</b>直接用作写回基线，不得再读一次盘，
     *         理由同 {@link #mergeExternalChanges()} 的 slice-07-data-consistency-008）；
     *         {@code null} = 磁盘不可用（未采纳，什么都不写）
     */
    private YamlFiles.Loaded adoptExternalMonsterChanges() {
        YamlFiles.Loaded loaded = monstersGate.load();
        if (!loaded.usable()) {
            plugin.getLogger().warning("monsters-config.yml is unusable after being modified externally; skipping the "
                    + "merge and leaving the file on disk untouched");
            return null;
        }
        YamlConfiguration disk = loaded.config();
        if (loaded.status() == YamlFiles.Status.ABSENT) {
            // 载入之后文件被删掉：既有的「写集合内以内存为权威」语义会把它按内存内容重建，
            // 但写集合之外的键（手写自定义键、注释）随文件一起没了。明确说一声，别静默重建。
            plugin.getLogger().warning("monsters-config.yml disappeared after it was read; it will be recreated from "
                    + "the in-memory configuration (anything that only existed in the file is gone)");
        }
        int merged = mergeExternalMonsterValues(disk);
        Set<String> externallyAdded = new LinkedHashSet<>();
        for (String diskKey : disk.getKeys(false)) {
            if (!activeConfig.getMonsters().containsKey(diskKey)) {
                externallyAdded.add(diskKey);
            }
        }
        plugin.getLogger().info("monsters-config.yml was modified externally; the on-disk content is kept as the "
                + "write baseline so keys outside the save set and all comments survive, and "
                + merged + " hand-edited value(s) were merged into memory"
                + (externallyAdded.isEmpty() ? "" : "; sections with no in-memory monster (kept verbatim): "
                + String.join(", ", externallyAdded)));
        return loaded;
    }

    /**
     * 外部手改的<b>写集合内</b>键值回灌内存（monsters-config-dc-001 的合并部分）。
     *
     * <p>与 {@link #mergeExternalChanges()} 同一条基线守卫：仅当内存值仍等于会话基线
     * （{@code lastSavedSnapshot}）——即本会话<b>没有</b>通过 GUI 改过该值——才让磁盘值胜出。
     * 否则游戏内改动优先（GUI 是权威）。这样「管理员改了文件」与「管理员在 GUI 里改了值」两种
     * 情况都不会互相吞掉。</p>
     *
     * <p>类型/合法性校验与 {@link #loadMonstersConfig()} 一致：非法 entity-type、非物品 Material 一律
     * 跳过该键并打 warning，保留内存值——不做比读盘更宽松的接受。</p>
     *
     * @return 实际回灌的键值个数（用于日志）
     */
    private int mergeExternalMonsterValues(YamlConfiguration disk) {
        if (lastSavedSnapshot == null) {
            return 0;
        }
        int merged = 0;
        for (Map.Entry<String, GameConfig.MonsterConfig> entry : activeConfig.getMonsters().entrySet()) {
            String key = entry.getKey();
            GameConfig.MonsterConfig mc = entry.getValue();
            GameConfig.MonsterConfig baseMc = lastSavedSnapshot.getMonsters().get(key);
            if (mc == null || baseMc == null) continue;
            String path = key + ".";

            String entityType = disk.getString(path + "entity-type");
            if (entityType != null && !entityType.isBlank() && mc.getEntityType() == baseMc.getEntityType()) {
                EntityType parsed = parseSpawnableEntityType(entityType);
                if (parsed == null) {
                    plugin.getLogger().warning("External edit ignored: " + entityType + " for monster " + key
                            + " is not a spawnable mob; keeping the in-memory entity-type");
                } else if (parsed != mc.getEntityType()) {
                    mc.setEntityType(parsed);
                    merged++;
                }
            }
            if (hasNumber(disk, path + "base-damage") && mc.getBaseDamage() == baseMc.getBaseDamage()) {
                double value = disk.getDouble(path + "base-damage");
                if (value != mc.getBaseDamage()) {
                    mc.setBaseDamage(value);
                    merged++;
                }
            }
            if (disk.isString(path + "itemsadder-id")
                    && Objects.equals(mc.getItemsAdderId(), baseMc.getItemsAdderId())) {
                String value = disk.getString(path + "itemsadder-id");
                if (!Objects.equals(value, mc.getItemsAdderId())) {
                    mc.setItemsAdderId(value);
                    merged++;
                }
            }
            if (hasNumber(disk, path + "custom-model-data")
                    && mc.getCustomModelData() == baseMc.getCustomModelData()) {
                int value = disk.getInt(path + "custom-model-data");
                if (value != mc.getCustomModelData()) {
                    mc.setCustomModelData(value);
                    merged++;
                }
            }
            // 装备六部位：与读盘侧同一口径（空串 = none，未知/非物品材质跳过并告警）
            merged += mergeEquipmentValue(disk, path, "main-hand",
                    mc.getMainHandItem(), baseMc.getMainHandItem(), mc::setMainHandItem, key);
            merged += mergeEquipmentValue(disk, path, "off-hand",
                    mc.getOffHandItem(), baseMc.getOffHandItem(), mc::setOffHandItem, key);
            merged += mergeEquipmentValue(disk, path, "helmet",
                    mc.getHelmet(), baseMc.getHelmet(), mc::setHelmet, key);
            merged += mergeEquipmentValue(disk, path, "chestplate",
                    mc.getChestplate(), baseMc.getChestplate(), mc::setChestplate, key);
            merged += mergeEquipmentValue(disk, path, "leggings",
                    mc.getLeggings(), baseMc.getLeggings(), mc::setLeggings, key);
            merged += mergeEquipmentValue(disk, path, "boots",
                    mc.getBoots(), baseMc.getBoots(), mc::setBoots, key);
        }
        return merged;
    }

    /**
     * 单个装备位的外部手改合并。
     *
     * <p>三个分支与读盘侧口径对齐：空串 = 显式清空（{@code none}）、合法物品材质 = 采用、
     * 未知/非物品材质 = 忽略并告警（保留内存值）。基线守卫由调用方语义保证：
     * {@code current == base} 即本会话未通过 GUI 改过这一位，磁盘上的差异必然来自手改。</p>
     *
     * @return 1 = 已合并；0 = 未改动（键不存在、材质非法、或本会话已改过）
     */
    private int mergeEquipmentValue(YamlConfiguration disk, String path, String field,
                                    Material current, Material base,
                                    Consumer<Material> setter, String monsterKey) {
        if (current != base || !disk.contains(path + field)) {
            return 0;
        }
        String raw = disk.getString(path + field);
        if (raw == null) {
            return 0;
        }
        if (raw.isBlank()) {
            // 空串 = none：手改把它清空是有意义的动作，不能当「没写」忽略
            if (current == null) {
                return 0;
            }
            setter.accept(null);
            return 1;
        }
        Material parsed = parseItemMaterial(raw);
        if (parsed == null) {
            plugin.getLogger().warning("External edit ignored: " + raw + " for monster " + monsterKey
                    + "." + field + " is not an item material; keeping the in-memory value");
            return 0;
        }
        if (parsed == current) {
            return 0;
        }
        setter.accept(parsed);
        return 1;
    }

    /** entity-type 解析：合法名称且实体类是 {@link Mob} 子类才返回，否则 null。 */
    private static EntityType parseSpawnableEntityType(String raw) {
        try {
            EntityType parsed = EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            Class<?> entityClass = parsed.getEntityClass();
            return entityClass != null && Mob.class.isAssignableFrom(entityClass) ? parsed : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** 材质解析：空/未知/非物品一律 null（装备位写空串表示 none，不能当成「改成 null」）。 */
    private static Material parseItemMaterial(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Material material = Material.getMaterial(raw.trim());
        return material != null && material.isItem() ? material : null;
    }

    /**
     * monsters-config.yml 里存在、但内存没有对应怪物的段（键集外的新怪种）一次性汇总告警（round5 F5）。
     *
     * <p><b>为什么不支持新增</b>：{@code WaveManager#spawnCustomMob} 是两级分派——三个内置键走
     * 专用 AI，其余键回落 {@code EntityType.valueOf} 原版怪；而<b>这段配置本身只覆盖装备与模型</b>，
     * 没有自定义 AI 可绑。所以「新增第 4 个怪种段」既进不了 {@code getMonsters()}（
     * {@link #loadMonstersConfig()} 只遍历内存键集），也就拿不到装备覆盖。真要让管理员的意图生效，
     * 得先在 {@code waves.*.compositions} 里用原版实体名（{@code parseSpawnableEntityType} 那条路径），
     * 那是运行时本来就能刷的形态。</p>
     *
     * <p><b>与 {@link #adoptExternalMonsterChanges()} 的分工</b>：那边只在「文件载入后被外部改过、
     * 且恰好触发一次保存」时才提到多余段，且只是一条 info；重启后手加的段永远拿不到任何反馈。
     * 本方法在每次读盘后都跑，覆盖那条路径。</p>
     *
     * <p>只报「顶层是段」的键：顶层标量不是怪物段，报出来是噪音（{@code YamlFiles} 的 validator 同款口径）。
     * 键比较对大小写不敏感，与消费侧 {@code toLowerCase(Locale.ROOT)} 的查找一致——否则会对一个
     * 本来能用的段乱报警告。</p>
     */
    private void warnUnsupportedMonsterSections() {
        Set<String> unsupported = new LinkedHashSet<>();
        for (String diskKey : monstersConfigYaml.getKeys(false)) {
            if (activeConfig.getMonsters().containsKey(diskKey)) continue;
            if (activeConfig.getMonsters().containsKey(diskKey.toLowerCase(Locale.ROOT))) continue;
            if (monstersConfigYaml.getConfigurationSection(diskKey) == null) continue;
            unsupported.add(diskKey);
        }
        if (!unsupported.isEmpty()) {
            plugin.getLogger().warning("monsters-config.yml has monster section(s) with no built-in monster "
                    + "(custom monster types are not supported: each one needs a dedicated AI bound in code, "
                    + "so this section is ignored): " + String.join(", ", unsupported)
                    + " — to spawn a vanilla mob, use its EntityType name in waves.<n>.compositions instead");
        }
    }

    /**
     * monsters-config.yml 的「不生效键」一次性汇总 warning（monsters-config-dc-002）。
     *
     * <p>本文件的写集合比读集合多 9 个键（{@link #MONSTERS_CONFIG_NON_EFFECTIVE_KEYS}）：它仍会把它们
     * 写回磁盘以保持文件自洽，但从不读取——真正的消费点是 {@code game-settings.yml} 的同名键。
     * 这份拆分是作者知情的设计，**读集合不因此改变**；本方法只负责让「改错了地方」的操作者
     * 在 reload 时看到一行明确的提示，而不是对着一个永远不生效的值排障。</p>
     *
     * <p><b>判定口径：</b>不能只看「值非空/非零」——出厂模板本身就带 {@code health: 40.0} 之类的值，
     * 那样每个服每次 reload 都会收到一条无信息量的警告。只有「本文件的值与
     * {@code game-settings.yml} 侧的实际生效值<b>不一致</b>」才说明操作者改的是这个不生效的副本。
     * 两侧一致（出厂状态）或键不存在的怪种一律静默。</p>
     */
    private void warnNonEffectiveMonsterKeys() {
        if (!settingsLoadedFromDisk) {
            // game-settings.yml 不可用 → 内存里是默认值，不是「服主的生效配置」，对账无意义
            return;
        }
        Set<String> divergent = new LinkedHashSet<>();
        for (String monsterKey : monstersConfigYaml.getKeys(false)) {
            ConfigurationSection section = monstersConfigYaml.getConfigurationSection(monsterKey);
            GameConfig.MonsterConfig effective = activeConfig.getMonsters().get(monsterKey);
            if (section == null || effective == null) continue;
            for (String field : MONSTERS_CONFIG_NON_EFFECTIVE_KEYS) {
                if (!section.contains(field)) continue;
                if (differsFromEffective(section.get(field), field, effective)) {
                    divergent.add(monsterKey + "." + field);
                }
            }
        }
        if (!divergent.isEmpty()) {
            plugin.getLogger().warning("monsters-config.yml has values that differ from the effective configuration for "
                    + "keys this file never reads (they will not take effect; edit the monsters section of "
                    + "game-settings.yml instead): " + String.join(", ", divergent));
        }
    }

    /** 单键比对：monsters-config.yml 的磁盘值 vs game-settings.yml 侧的实际生效值。类型不符视为一致（不报）。 */
    private static boolean differsFromEffective(Object diskValue, String field, GameConfig.MonsterConfig effective) {
        return switch (field) {
            case "display-name" -> diskValue instanceof String text
                    && !text.equals(effective.getDisplayName());
            case "display-egg" -> diskValue instanceof String text && !text.isBlank()
                    && (effective.getDisplayEgg() == null || !text.equals(effective.getDisplayEgg().name()));
            case "health" -> diskValue instanceof Number number
                    && number.doubleValue() != effective.getHealth();
            case "speed" -> diskValue instanceof Number number
                    && number.doubleValue() != effective.getSpeed();
            case "explode-damage-multiplier" -> diskValue instanceof Number number
                    && number.doubleValue() != effective.getExplodeDamageMultiplier();
            case "hook-velocity" -> diskValue instanceof Number number
                    && number.doubleValue() != effective.getHookVelocity();
            case "cooldown-ticks" -> diskValue instanceof Number number
                    && number.intValue() != effective.getCooldownTicks();
            case "stun-duration-ticks" -> diskValue instanceof Number number
                    && number.intValue() != effective.getStunDurationTicks();
            case "airdrop-mob-count" -> diskValue instanceof Number number
                    && number.intValue() != effective.getAirdropMobCount();
            default -> false;
        };
    }

    public void loadFromFile() {
        // F3 守卫 C：defaultWave 是全库唯一「被无 null 保护地解引用」的配置字段，因此必须在**本方法
        // 最前面**归一化，而不是放进下面的 waves 段：
        //   ① snapshot() 的 defaultWave.copy()（GameConfig:645）—— 本方法末尾的 lastSavedSnapshot
        //      与降级早退分支都会走；null 会让整次载入以 NPE 收场，而不是「default 配比不对」这种可玩状态；
        //   ② getWaveConfig 的 getOrDefault(waveNumber, defaultWave)（GameConfig:829）—— null 会被当作
        //      **合法映射值**返回给 WaveManager，随后解引用，炸在刷怪任务的 BukkitRunnable 里。
        // 为什么不能放在 waves 段里判空：本方法中段会执行 activeConfig = new GameConfig()，而构造器必预置
        // defaultWave ⇒ 走到 waves 段时它必然非 null，那里的判空是恒假的死代码，而真正暴露 null 的
        // 「文件不可用 → 保留内存值并提前 return」分支在它之前就已经 snapshot() 过了。
        // 正常路径下这条分支不可达（构造器必预置；重建点也会重建），故打 SEVERE 并标注 unreachable。
        // 恢复用的是全新的构造器模板（出厂语义），不是「就地 new 一个空 WaveConfig」—— 空 WaveConfig 会让
        // getTotalCount()/getTotalWeight() 双双为 0，等价于把大半波次推给 spawnDefaultWave 的原版僵尸兜底。
        if (activeConfig.getDefaultWave() == null) {
            plugin.getLogger().severe("internal state error: GameConfig.defaultWave was null; "
                    + "restoring the built-in default wave template (10 mobs / 3 monster types) "
                    + "so the wave load can continue (this should be unreachable)");
            activeConfig.setDefaultWave(new GameConfig().getDefaultWave());
        }
        // display.yml 与 game-settings 完全独立（自己一份 gate、自己的兜底），且本方法有多个提前
        // return 分支——放在最前面，保证任何路径下展示布局都已就位
        loadDisplayConfig();
        YamlFiles.Loaded loaded = settingsGate.load();
        // isReadOnly 一并判断：隔离后文件不存在 → load() 返回 ABSENT（usable），照走会把空配置当权威再迁移写回
        if (!loaded.usable() || settingsGate.isReadOnly()) {
            // 不可用时不套用磁盘值（保留内存/默认值），也不做任何迁移写回
            plugin.getLogger().severe("game-settings.yml is unusable; keeping the in-memory values "
                    + "(nothing will be read from or written to the file this run)");
            settingsLoadedFromDisk = false;
            loadMonstersConfig();
            lastSavedSnapshot = activeConfig.snapshot();
            return;
        }
        settingsYaml = loaded.config();
        settingsLoadedFromDisk = true;

        // F-002：用磁盘重建内存，而不是「磁盘值覆盖内存」。旧实现从不清空 activeConfig，
        // 所有赋值都是 if (contains) 形式，于是「磁盘删掉的键」与「键存在且值未变」在内存里
        // 不可区分——删键后 reload 静默无效，紧接着的任何一次 saveToFile() 会把旧值和旧键
        // 原样写回磁盘（键复活）。此处换一个全新对象，缺键即自然回落到 GameConfig 的代码默认值。
        // 放在上面「文件不可用 → 保留内存值并提前返回」的分支之后：那条分支的语义是
        // 「读不到就不动内存」，不能因为重建而把管理员的未保存修改清掉。
        activeConfig = new GameConfig();
        // slice-07-data-consistency-009：重建后归一化会重新计算，脏标志的归一化项随之重算。
        // （放在重建点而非方法入口：上方「文件不可用 → 保留内存值」分支必须保留既有待落盘状态。）
        loadTimeNormalizationPending = false;

        if (settingsYaml.contains("world-name")) activeConfig.setWorldName(settingsYaml.getString("world-name"));
        if (settingsYaml.contains("core.x")) activeConfig.setCoreX(settingsYaml.getDouble("core.x"));
        if (settingsYaml.contains("core.y")) activeConfig.setCoreY(settingsYaml.getDouble("core.y"));
        if (settingsYaml.contains("core.z")) activeConfig.setCoreZ(settingsYaml.getDouble("core.z"));
        if (settingsYaml.contains("min-players")) activeConfig.setMinPlayers(settingsYaml.getInt("min-players"));
        if (settingsYaml.contains("max-players")) activeConfig.setMaxPlayers(settingsYaml.getInt("max-players"));
        if (settingsYaml.contains("victory-waves")) activeConfig.setVictoryWaves(settingsYaml.getInt("victory-waves"));
        if (settingsYaml.contains("wave-interval-seconds")) activeConfig.setWaveIntervalSeconds(settingsYaml.getInt("wave-interval-seconds"));
        if (settingsYaml.contains("wave-timeout-seconds")) {
            int rawTimeout = settingsYaml.getInt("wave-timeout-seconds");
            int normalizedTimeout = normalizeWaveTimeout(rawTimeout);
            // slice-07-data-consistency-009：归一化改了内存值 → 内存已与磁盘不一致，
            // 若不在快照前登记，下面的 lastSavedSnapshot 会把归一化结果当成「已落盘」。
            if (normalizedTimeout != rawTimeout) {
                loadTimeNormalizationPending = true;
            }
            activeConfig.setWaveTimeoutSeconds(normalizedTimeout);
        }
        if (settingsYaml.contains("defeat-countdown-seconds")) activeConfig.setDefeatCountdownSeconds(settingsYaml.getInt("defeat-countdown-seconds"));
        if (settingsYaml.contains("villager-health")) activeConfig.setVillagerHealth(settingsYaml.getDouble("villager-health"));
        if (settingsYaml.contains("rejoin-timeout-seconds")) activeConfig.setRejoinTimeoutSeconds(settingsYaml.getInt("rejoin-timeout-seconds"));
        if (settingsYaml.contains("empty-arena-end-seconds")) activeConfig.setEmptyArenaEndSeconds(settingsYaml.getInt("empty-arena-end-seconds"));
        // fix5 M-04：两个阈值键均已就绪，检查交叉告警（仅告警、不改值）
        warnIfEmptyArenaThresholdCollision();
        if (settingsYaml.contains("map-restore.enabled")) activeConfig.setMapRestoreEnabled(settingsYaml.getBoolean("map-restore.enabled"));
        if (settingsYaml.contains("scoreboard.enabled")) activeConfig.setScoreboardEnabled(settingsYaml.getBoolean("scoreboard.enabled"));

        String hammerMat = settingsYaml.getString("hammer.material");
        if (hammerMat != null) {
            Material hammerMatResult = Material.getMaterial(hammerMat);
            // fix6 Y2-05：非 item 材质放行会让 createVanillaHammer 的 new ItemStack 抛异常
            if (hammerMatResult != null && hammerMatResult.isItem()) {
                activeConfig.setHammerMaterial(hammerMatResult);
            } else {
                plugin.getLogger().warning("Invalid hammer material: " + hammerMat + ", keeping default");
            }
        }
        if (settingsYaml.contains("hammer.itemsadder-id")) activeConfig.setHammerItemsAdderId(settingsYaml.getString("hammer.itemsadder-id"));
        if (settingsYaml.contains("hammer.custom-model-data")) activeConfig.setHammerCustomModelData(settingsYaml.getInt("hammer.custom-model-data"));
        if (settingsYaml.contains("hammer.use-itemsadder")) activeConfig.setHammerUseItemsAdder(settingsYaml.getBoolean("hammer.use-itemsadder"));
        if (settingsYaml.contains("hammer.base-damage")) activeConfig.setHammerBaseDamage(settingsYaml.getDouble("hammer.base-damage"));
        if (settingsYaml.contains("hammer.effect-radius")) activeConfig.setHammerEffectRadius(settingsYaml.getDouble("hammer.effect-radius"));
        if (settingsYaml.contains("hammer.aftershock-ticks")) activeConfig.setHammerAftershockTicks(settingsYaml.getInt("hammer.aftershock-ticks"));
        if (settingsYaml.contains("hammer.smash-timeout-ticks")) activeConfig.setHammerSmashTimeoutTicks(settingsYaml.getInt("hammer.smash-timeout-ticks"));
        if (settingsYaml.contains("hammer.visual-display-ticks")) activeConfig.setHammerVisualDisplayTicks(settingsYaml.getInt("hammer.visual-display-ticks"));
        // fix-round1 pattern-8：hammer.damage-*-threshold / damage-*-multiplier 已废弃（v1.1 起由
        // tier 三档直接定伤害/范围，无任何消费者）。加载侧不再读入——保留 if (contains) 反而是
        // 「读进来再写回去」的复活回路。磁盘残留键由 tombstoneDeprecatedHammerDamageKeys()
        // 在下一次保存时清掉（与 tier.low.charge 的双向墓碑一致）。
        if (settingsYaml.contains("hammer.item-name")) activeConfig.setHammerItemName(settingsYaml.getString("hammer.item-name"));
        if (settingsYaml.contains("hammer.cooldown-seconds")) activeConfig.setHammerCooldownSeconds(settingsYaml.getDouble("hammer.cooldown-seconds"));
        if (settingsYaml.contains("hammer.max-uses")) activeConfig.setHammerMaxUses(settingsYaml.getInt("hammer.max-uses"));
        // tier.low.charge 已删除：load 时忽略磁盘残留键，不再读入
        if (settingsYaml.contains("hammer.tier.low.damage")) activeConfig.setHammerLowDamage(settingsYaml.getDouble("hammer.tier.low.damage"));
        if (settingsYaml.contains("hammer.tier.low.radius")) activeConfig.setHammerLowRadius(settingsYaml.getDouble("hammer.tier.low.radius"));
        if (settingsYaml.contains("hammer.tier.medium.charge")) activeConfig.setHammerMediumCharge(settingsYaml.getDouble("hammer.tier.medium.charge"));
        if (settingsYaml.contains("hammer.tier.medium.damage")) activeConfig.setHammerMediumDamage(settingsYaml.getDouble("hammer.tier.medium.damage"));
        if (settingsYaml.contains("hammer.tier.medium.radius")) activeConfig.setHammerMediumRadius(settingsYaml.getDouble("hammer.tier.medium.radius"));
        if (settingsYaml.contains("hammer.tier.high.charge")) activeConfig.setHammerHighCharge(settingsYaml.getDouble("hammer.tier.high.charge"));
        if (settingsYaml.contains("hammer.tier.high.damage")) activeConfig.setHammerHighDamage(settingsYaml.getDouble("hammer.tier.high.damage"));
        if (settingsYaml.contains("hammer.tier.high.radius")) activeConfig.setHammerHighRadius(settingsYaml.getDouble("hammer.tier.high.radius"));

        // 节点方块固定为重生锚（见 NodeSystem#loadConfig），不再从 YAML 读取
        if (settingsYaml.contains("nodes.base-health")) activeConfig.setNodeBaseHealth(settingsYaml.getDouble("nodes.base-health"));
        if (settingsYaml.contains("nodes.buff-radius")) activeConfig.setNodeBuffRadius(settingsYaml.getDouble("nodes.buff-radius"));
        if (settingsYaml.contains("nodes.self-destruct-multiplier")) activeConfig.setNodeSelfDestructMultiplier(settingsYaml.getDouble("nodes.self-destruct-multiplier"));
        if (settingsYaml.contains("nodes.search-radius")) activeConfig.setNodeSearchRadius(settingsYaml.getInt("nodes.search-radius"));
        if (settingsYaml.contains("nodes.repair-cost-name")) activeConfig.setRepairCostName(settingsYaml.getString("nodes.repair-cost-name"));
        String repairCostItemStr = settingsYaml.getString("nodes.repair-cost-item");
        if (repairCostItemStr != null) {
            Material rcMat = Material.getMaterial(repairCostItemStr);
            // fix6 Y2-02：isItem 校验与命令侧同一判据——手改 yml 写 water 等非物品材质同样会
            // 让商店 REPAIR_ITEM 发货链抛异常吃币（先扣款后抛、无退款）。
            if (rcMat != null && rcMat.isItem()) {
                activeConfig.setRepairCostItem(rcMat);
            } else {
                plugin.getLogger().warning("Invalid repair-cost-item material '" + repairCostItemStr
                        + "' (unknown or not a carryable item), keeping default");
            }
        }
        if (settingsYaml.contains("nodes.repair-cost-amount")) activeConfig.setRepairCostAmount(settingsYaml.getInt("nodes.repair-cost-amount"));
        if (settingsYaml.contains("nodes.repair-amount-per-cost")) activeConfig.setRepairAmountPerCost(settingsYaml.getDouble("nodes.repair-amount-per-cost"));

        // 旧键迁移：早期版本使用 nodes.repair-item / nodes.repair-amount，现统一为
        // nodes.repair-cost-item / nodes.repair-amount-per-cost。仅在未部署新键时迁移。
        // 旧键值读取完毕后会立即从 YAML 中删除并落盘，使第二次启动起不再残留旧键、不再重复打印警告。
        boolean hasLegacyRepairItem = settingsYaml.contains("nodes.repair-item");
        boolean hasLegacyRepairAmount = settingsYaml.contains("nodes.repair-amount");
        if (hasLegacyRepairItem || hasLegacyRepairAmount) {
            // 1) 修复物品：仅在未部署新键时迁移
            if (hasLegacyRepairItem && !settingsYaml.contains("nodes.repair-cost-item")) {
                String legacyRepairItem = settingsYaml.getString("nodes.repair-item");
                Material legacyRepairMat = legacyRepairItem == null ? null : Material.getMaterial(legacyRepairItem);
                // fix6 Y2-03：旧键值同样要求 isItem——非物品材质沿用会让发货链抛异常吃币
                if (legacyRepairMat != null && legacyRepairMat.isItem()) {
                    activeConfig.setRepairCostItem(legacyRepairMat);
                } else if (legacyRepairItem != null) {
                    // fix6 Y2-03：旧键值非法（未知或非可携带物品）不采用，下方写回的新键为当前默认值
                    plugin.getLogger().warning("Invalid legacy repair-item material '" + legacyRepairItem
                            + "' (unknown or not a carryable item), keeping default");
                }
                plugin.getLogger().warning("Legacy config key nodes.repair-item detected, migrated automatically to nodes.repair-cost-item");
                // 新键原本缺失，补写入迁移后的值，避免删除旧键后配置丢失
                settingsYaml.set("nodes.repair-cost-item", activeConfig.getRepairCostItem().name());
            } else if (hasLegacyRepairItem) {
                plugin.getLogger().warning("Deprecated config key nodes.repair-item detected; nodes.repair-cost-item is in use and the legacy key will be ignored");
            }

            // 2) 单次修复量：同样仅在未部署新键时迁移
            if (hasLegacyRepairAmount && !settingsYaml.contains("nodes.repair-amount-per-cost")) {
                activeConfig.setRepairAmountPerCost(settingsYaml.getDouble("nodes.repair-amount"));
                settingsYaml.set("nodes.repair-amount-per-cost", activeConfig.getRepairAmountPerCost());
            }

            // 3) 旧键值已读取完毕（或确认被忽略），从 YAML 中删除旧键并写回磁盘，
            //    使第二次启动起不再残留旧键、不再重复打印警告。
            //    已存在的新键 nodes.repair-cost-item / nodes.repair-amount-per-cost 不受影响。
            settingsYaml.set("nodes.repair-item", null);
            settingsYaml.set("nodes.repair-amount", null);
            if (settingsGate.write(settingsYaml)) {
                // M-15：本次写盘更新 mtime 记录，避免随后的保存误判为「外部修改」
                settingsLastModified = settingsFile.lastModified();
            } else {
                // 迁移状态不回滚：下次启动会重试（幂等）
                plugin.getLogger().severe("Failed to remove legacy keys from game-settings.yml "
                        + "(they will be retried on the next start)");
            }
        }

        // 节点增益效果：字符串列表，格式 "效果名:等级"（等级为 amplifier，0 = I 级）
        // round5 F4：判据由 isEmpty() 改为 isSet() —— 键缺失 = 出厂默认，键存在 = 磁盘权威（含 []）。
        // 旧实现是「双重 isEmpty」：`buff-effects: []`（想关闭全部增益）被当成没配，全部条目非法时
        // 同样被当成没配，两种情况下内存都保留构造器预置的 RESISTANCE:0 / REGENERATION:0，紧接着的
        // saveToFile 又把预置写回磁盘 —— 删除无效 + 值静默回退。同一条原则的正确参照是
        // starter-kit.items 的 isSet 判据（下方 :1523）与 DisplayConfigLoader 的
        // 「键缺失 = 出厂 / 显式空 = 空」。消费侧 NodeSystem#loadConfig 也不再为空表补默认值，
        // 三处同改才闭环（只改这里会让「磁盘空表 + 实际仍给增益」的分歧更难查）。
        if (settingsYaml.isSet("nodes.buff-effects")) {
            if (!settingsYaml.isList("nodes.buff-effects")) {
                // 标量 / 段：getStringList 会静默返回空表，进而被误当成「显式清空」。
                // 与上面两类错误相反，这种形态错必须保留内存值并说出来。
                plugin.getLogger().warning("game-settings.yml 'nodes.buff-effects' must be a list of "
                        + "\"EFFECT:AMPLIFIER\" entries (found a scalar/section); keeping the current buff table");
            } else {
                List<String> buffEffectList = settingsYaml.getStringList("nodes.buff-effects");
                Map<PotionEffectType, Integer> parsedBuffs = parseBuffEffects(buffEffectList);
                activeConfig.getNodeBuffEffects().clear();
                activeConfig.getNodeBuffEffects().putAll(parsedBuffs);
                if (!buffEffectList.isEmpty() && parsedBuffs.isEmpty()) {
                    // 每条非法项已由 parseBuffEffects 各打一条；这里补的是一句「结果是空表」的结论，
                    // 否则「写错了」与「本来就想关掉全部增益」在日志里不可区分。
                    plugin.getLogger().warning("All nodes.buff-effects entries were rejected; "
                            + "nodes will apply no potion effects");
                }
            }
        }
        if (settingsYaml.contains("nodes.buff-refresh-ticks")) activeConfig.setNodeBuffRefreshTicks(settingsYaml.getInt("nodes.buff-refresh-ticks"));
        if (settingsYaml.contains("nodes.buff-stack-cap")) activeConfig.setNodeBuffStackCap(settingsYaml.getInt("nodes.buff-stack-cap"));
        if (settingsYaml.contains("nodes.buff-duration-ticks")) activeConfig.setNodeBuffDurationTicks(settingsYaml.getInt("nodes.buff-duration-ticks"));

        if (settingsYaml.contains("spawning.spawn-radius-min")) activeConfig.setSpawnRadiusMin(settingsYaml.getInt("spawning.spawn-radius-min"));
        if (settingsYaml.contains("spawning.spawn-radius-max")) activeConfig.setSpawnRadiusMax(settingsYaml.getInt("spawning.spawn-radius-max"));
        // fix-round1 pattern-4b：见 saveToFile 对应注释；缺键保留代码默认 3.0
        if (settingsYaml.contains("spawning.spawn-radius-spread"))
            activeConfig.setSpawnRadiusSpread(settingsYaml.getDouble("spawning.spawn-radius-spread"));
        if (settingsYaml.contains("spawning.global-mob-cap")) activeConfig.setGlobalMobCap(settingsYaml.getInt("spawning.global-mob-cap"));
        if (settingsYaml.contains("spawning.mob-cap-per-player")) activeConfig.setMobCapPerPlayer(settingsYaml.getInt("spawning.mob-cap-per-player"));
        if (settingsYaml.contains("spawning.spawn-y-offset")) activeConfig.setSpawnYOffset(settingsYaml.getInt("spawning.spawn-y-offset"));
        if (settingsYaml.contains("spawning.airdrop-max-per-dropper")) activeConfig.setAirdropMaxPerDropper(settingsYaml.getInt("spawning.airdrop-max-per-dropper"));

        String fireballMat = settingsYaml.getString("fireball.material");
        if (fireballMat != null) {
            Material fireballMatResult = Material.getMaterial(fireballMat);
            // fix6 Y2-05：非 item 材质放行会让 createFireball 的 new ItemStack 抛异常
            if (fireballMatResult != null && fireballMatResult.isItem()) {
                activeConfig.setFireballMaterial(fireballMatResult);
            } else {
                plugin.getLogger().warning("Invalid fireball material: " + fireballMat + ", keeping default");
            }
        }
        if (settingsYaml.contains("fireball.speed")) activeConfig.setFireballSpeed(settingsYaml.getDouble("fireball.speed"));
        if (settingsYaml.contains("fireball.damage")) activeConfig.setFireballDamage(settingsYaml.getDouble("fireball.damage"));
        if (settingsYaml.contains("fireball.explosion-radius")) activeConfig.setFireballExplosionRadius(settingsYaml.getDouble("fireball.explosion-radius"));
        if (settingsYaml.contains("fireball.cooldown-ticks")) activeConfig.setFireballCooldownTicks(settingsYaml.getInt("fireball.cooldown-ticks"));
        if (settingsYaml.contains("fireball.max-travel-ticks")) activeConfig.setFireballMaxTravelTicks(settingsYaml.getInt("fireball.max-travel-ticks"));
        if (settingsYaml.contains("fireball.knockback")) activeConfig.setFireballKnockback(settingsYaml.getDouble("fireball.knockback"));
        if (settingsYaml.contains("fireball.proximity-fuse")) activeConfig.setFireballProximityFuse(settingsYaml.getDouble("fireball.proximity-fuse"));
        if (settingsYaml.contains("fireball.item-name")) activeConfig.setFireballItemName(settingsYaml.getString("fireball.item-name"));

        // ---- 村民商店货币掉落（fix4 反馈6：每杀必掉、数量浮动） ----
        if (settingsYaml.contains("shop.currency-drop-chance")) activeConfig.setCurrencyDropChance(settingsYaml.getDouble("shop.currency-drop-chance"));
        if (settingsYaml.contains("shop.currency-drop-min")) activeConfig.setCurrencyDropMin(settingsYaml.getInt("shop.currency-drop-min"));
        if (settingsYaml.contains("shop.currency-drop-max")) activeConfig.setCurrencyDropMax(settingsYaml.getInt("shop.currency-drop-max"));

        // 旧格式迁移：旧键 shop.currency-drop-amount（概率 × 固定数量）→ 新键 min/max（区间浮动）。
        // 仅在「旧键存在且新键缺失」时做值迁移（与 nodes.repair-item 迁移同款，只发生一次）：
        //  - 仍是旧默认对（chance 0.4 + amount 1，即服主未改过）→ 整体升级为新默认
        //    （chance 1.0 必掉 + min 1 / max 2 浮动）——fix4 反馈6 的核心诉求；
        //  - 服主改过任一值 → 保守保形：min = max = 旧 amount（每次击杀掉固定数量不变），
        //    chance 保留（概率语义原样生效）。
        // 迁移后写回新键并删除旧键 + 落盘，使第二次启动起不再残留旧键（仿 nodes.repair-item 迁移）。
        // 旧键与 min 并存（手改过的新文件混写）时不做值迁移，仅把旧键从磁盘清掉防混淆。
        if (settingsYaml.contains("shop.currency-drop-amount")) {
            if (!settingsYaml.contains("shop.currency-drop-min")) {
                // 旧世界的「生效概率」：文件写了 chance 用文件值；缺 chance 键时旧代码的默认是 0.4。
                // 不能读 activeConfig.getCurrencyDropChance()——此刻它可能已是新默认 1.0（文件缺键时），
                // 会让「只写了 amount 的旧文件」被误判为非旧默认对。
                double legacyChance = settingsYaml.contains("shop.currency-drop-chance")
                        ? settingsYaml.getDouble("shop.currency-drop-chance")
                        : 0.4;
                int legacyAmount = settingsYaml.getInt("shop.currency-drop-amount");
                boolean oldDefaultPair = Double.compare(legacyChance, 0.4) == 0 && legacyAmount == 1;
                if (oldDefaultPair) {
                    activeConfig.setCurrencyDropChance(1.0);
                    activeConfig.setCurrencyDropMin(1);
                    activeConfig.setCurrencyDropMax(2);
                    plugin.getLogger().warning("Legacy config key shop.currency-drop-amount detected "
                            + "(old default: 0.4 chance x 1 item), upgraded to the new default: "
                            + "1-2 items per kill (shop.currency-drop-chance/min/max)");
                } else {
                    int qty = Math.max(1, legacyAmount);
                    // 保形迁移：概率与固定数量都原样保留
                    activeConfig.setCurrencyDropChance(legacyChance);
                    activeConfig.setCurrencyDropMin(qty);
                    activeConfig.setCurrencyDropMax(qty);
                    plugin.getLogger().warning("Legacy config key shop.currency-drop-amount detected, migrated to "
                            + "shop.currency-drop-min / -max (chance " + legacyChance + " kept, "
                            + qty + " item(s) per drop unchanged)");
                }
                settingsYaml.set("shop.currency-drop-chance", activeConfig.getCurrencyDropChance());
                settingsYaml.set("shop.currency-drop-min", activeConfig.getCurrencyDropMin());
                settingsYaml.set("shop.currency-drop-max", activeConfig.getCurrencyDropMax());
            } else {
                plugin.getLogger().warning("Deprecated config key shop.currency-drop-amount detected "
                        + "(the new shop.currency-drop-min/-max keys already exist); the legacy key will be ignored and removed");
            }
            settingsYaml.set("shop.currency-drop-amount", null);
            if (settingsGate.write(settingsYaml)) {
                // M-15：本次写盘更新 mtime 记录，避免随后的保存误判为「外部修改」
                settingsLastModified = settingsFile.lastModified();
            } else {
                // 迁移状态不回滚：下次启动会重试（幂等）
                plugin.getLogger().severe("Failed to migrate legacy currency-drop keys in game-settings.yml "
                        + "(they will be retried on the next start)");
            }
        }

        // ---- 开局初始套装 (starter-kit, STORY-1.2) ----
        // 缺键 = 旧配置文件：保持 GameConfig 字段默认值（enabled=true + 石剑/面包/火把），不覆盖
        if (settingsYaml.contains("starter-kit.enabled")) {
            activeConfig.setStarterKitEnabled(settingsYaml.getBoolean("starter-kit.enabled"));
        }
        // getMapList 不存在时返回空列表；仅当键存在时才整体替换默认列表，
        // 否则旧配置文件（无 starter-kit 节）会被清空成空套装
        if (settingsYaml.isSet("starter-kit.items")) {
            List<GameConfig.StarterKitItem> parsedKit = parseStarterKitItems(settingsYaml.getMapList("starter-kit.items"));
            List<GameConfig.StarterKitItem> normalizedKit = normalizeStarterKitItems(parsedKit);
            // slice-07-data-consistency-009：同上——归一化后的值与磁盘不同，必须计入脏标志，
            // 否则主菜单会显示「已是最新」并隐藏保存按钮。
            if (!normalizedKit.equals(parsedKit)) {
                loadTimeNormalizationPending = true;
            }
            activeConfig.setStarterKitItems(normalizedKit);
        }

        ConfigurationSection monsterSection = settingsYaml.getConfigurationSection("monsters");
        if (monsterSection != null) {
            // P2-36：monsters 段的未知 key 原先静默 continue（L10-15），排障困难；
            // 这里把「文件里存在但代码不认识」的怪物键一次性汇总 warning。
            Set<String> unknownMonsters = new LinkedHashSet<>();
            for (String key : monsterSection.getKeys(false)) {
                if (activeConfig.getMonsters().get(key) == null) {
                    unknownMonsters.add(key);
                }
            }
            if (!unknownMonsters.isEmpty()) {
                plugin.getLogger().warning("game-settings.yml has unrecognized monster keys under monsters "
                        + "(typo? these entries will not take effect): " + String.join(", ", unknownMonsters));
            }
            for (String key : monsterSection.getKeys(false)) {
                GameConfig.MonsterConfig mc = activeConfig.getMonsters().get(key);
                if (mc == null) continue;
                String name = settingsYaml.getString("monsters." + key + ".display-egg");
                if (name != null) {
                    Material eggMat = Material.getMaterial(name);
                    if (eggMat != null) {
                        mc.setDisplayEgg(eggMat);
                    } else {
                        plugin.getLogger().warning("Unknown material: " + name + ", keeping default");
                    }
                }
                if (settingsYaml.contains("monsters." + key + ".health")) mc.setHealth(settingsYaml.getDouble("monsters." + key + ".health"));
                if (settingsYaml.contains("monsters." + key + ".speed")) mc.setSpeed(settingsYaml.getDouble("monsters." + key + ".speed"));
                if (settingsYaml.contains("monsters." + key + ".hook-velocity")) mc.setHookVelocity(settingsYaml.getDouble("monsters." + key + ".hook-velocity"));
                if (settingsYaml.contains("monsters." + key + ".display-name")) mc.setDisplayName(settingsYaml.getString("monsters." + key + ".display-name"));
                if (settingsYaml.contains("monsters." + key + ".explode-damage-multiplier")) mc.setExplodeDamageMultiplier(settingsYaml.getDouble("monsters." + key + ".explode-damage-multiplier"));
                if (settingsYaml.contains("monsters." + key + ".entity-explosion")) mc.setEntityExplosion(settingsYaml.getBoolean("monsters." + key + ".entity-explosion"));
                if (settingsYaml.contains("monsters." + key + ".cooldown-ticks")) mc.setCooldownTicks(settingsYaml.getInt("monsters." + key + ".cooldown-ticks"));
                if (settingsYaml.contains("monsters." + key + ".stun-duration-ticks")) mc.setStunDurationTicks(settingsYaml.getInt("monsters." + key + ".stun-duration-ticks"));
                if (settingsYaml.contains("monsters." + key + ".airdrop-mob-count")) mc.setAirdropMobCount(settingsYaml.getInt("monsters." + key + ".airdrop-mob-count"));
                if (settingsYaml.contains("monsters." + key + ".airdrop-mob-type")) mc.setAirdropMobType(settingsYaml.getString("monsters." + key + ".airdrop-mob-type"));
                // E2 按怪种掉落（可选键）：缺键保留 GameConfig 构造默认（特殊怪 = 作者掉落表值，其余 = -1 走全局兜底）
                if (settingsYaml.contains("monsters." + key + ".drop-min")) mc.setDropMin(settingsYaml.getInt("monsters." + key + ".drop-min"));
                if (settingsYaml.contains("monsters." + key + ".drop-max")) mc.setDropMax(settingsYaml.getInt("monsters." + key + ".drop-max"));
            }
        }

        ConfigurationSection waveSection = settingsYaml.getConfigurationSection("waves");
        if (waveSection != null) {
            // fix-round1 pattern-5：waves 段的未知键原先完全静默 —— 非数字波次键（手写 "wave5:"、
            // 或 YAML 键被写成别的东西）落进 catch (NumberFormatException ignored) {} 里零输出，
            // compositions 下的未知怪种键则是 comp == null → continue。玩家改完文件看不到任何反馈，
            // 只能靠「波次没变」猜。这里按上面 monsters 段的同款形状，把
            // 「文件里存在但代码不认识」的键一次性汇总 warning，不逐键打日志。
            Set<String> unknownWaves = new LinkedHashSet<>();
            Set<String> unknownCompositionMobs = new LinkedHashSet<>();
            Set<String> numericProblems = new LinkedHashSet<>();
            Set<String> emptyCompositionWaves = new LinkedHashSet<>();
            boolean emptyDefaultCompositions = false;   // F3 守卫 B：子段存在但解析后为空
            for (String waveKey : waveSection.getKeys(false)) {
                if ("default".equals(waveKey)) continue;
                try {
                    Integer.parseInt(waveKey.trim());
                } catch (NumberFormatException ignored) {
                    // 键不是波次号：记下来，循环结束后统一报告（旧实现在此处静默吞掉整条）
                    unknownWaves.add(waveKey);
                }
            }
            if (!unknownWaves.isEmpty()) {
                plugin.getLogger().warning("game-settings.yml has unrecognized entries under waves "
                        + "(wave keys must be numbers or 'default'; typo? these entries will not take effect): "
                        + String.join(", ", unknownWaves));
            }
            // ---- F3：waves.default（模板波次）的键集对齐 ----
            // 不变式：defaultWave.compositions 的键集 ≡ 磁盘 waves.default.compositions 段的可用键集。
            // 与数字波次（下面的 readComposition 调用）同一套判据、同一套缺键默认（count=0 / weight=1），
            // 因此「写在 waves.1 里能生效的怪种」写在 default 里也一定生效。旧实现只对「内存里已存在」
            // 的怪种赋值（comp == null → 记 unknown → continue），后果是：磁盘新增的怪种不生效且被打成
            // 拼写错误，磁盘删掉的怪种被构造器预置复活。
            //
            // 三条守卫（缺一条就会造出比原缺陷更重的事故）：
            //   A. 子段不存在（含裸头 `compositions:` / 列表 / 标量 / null，探针实测都返回 null）
            //      → 绝不清空，保留构造器预置的 3 条。否则 default 变空 ⇒ getTotalCount()==0 且
            //        getTotalWeight()==0 ⇒ WaveManager 落进 spawnDefaultWave 的「5+2×波数 只原版僵尸」兜底，
            //        而胜利波数是 20、模板只定义 1/5/10，也就是 16/20 波全部变成僵尸波。
            //   B. 子段存在但解析后为空（`compositions: {}`，或全部键都是不可刷怪种/非映射条目）
            //      → 同样不清空，并单独告警。「空」不表达任何用户意图：本游戏里没有「不刷怪」这个
            //        可配置状态，真要停掉未配置波次请写 waves.default.total-mobs: 0。
            //   C. defaultWave 为 null → 已在本方法最前面归一化，这里**不重复判空**：走到本行时它必然
            //      非 null（重建点会重建），重复判空只会掩盖「守卫 C 被误挪进 waves 段」这个位置错误。
            GameConfig.WaveConfig defaultWave = activeConfig.getDefaultWave();
            ConfigurationSection defaultSection = waveSection.getConfigurationSection("default");
            if (defaultSection == null && settingsYaml.isSet("waves.default")) {
                // `waves.default: 5` / `waves.default: []` 这类形态错：行为同「缺失」（保留模板），
                // 但必须说出来，否则管理员只看到「改了没效果」。
                plugin.getLogger().warning("game-settings.yml 'waves.default' must be a section "
                        + "(found a scalar/list); keeping the built-in default wave template");
            }
            // total-mobs 与 compositions 的守卫是**互相独立**的：只写 total-mobs 是合法用法
            // （只想调未配置波次的上限），不能被「没有 compositions」连坐。
            int defaultTotalMobs = readWaveInt(defaultSection, "total-mobs", defaultWave.getTotalMobs(),
                    false, "default.total-mobs", numericProblems);
            ConfigurationSection defaultComps = settingsYaml.getConfigurationSection("waves.default.compositions");
            if (defaultComps == null && settingsYaml.isSet("waves.default.compositions")) {
                // `compositions: []` / `compositions: abc` 这类形态错：section==null 但 isSet==true。
                // 行为按守卫 A（保留模板），但必须说出来 —— 否则管理员看到的是「改了没效果」。
                plugin.getLogger().warning("game-settings.yml 'waves.default.compositions' must be a section of "
                        + "per-monster 'count/weight' mappings (found a scalar/list); "
                        + "keeping the built-in default wave composition");
            }
            Map<String, GameConfig.MobComposition> alignedDefaultComps = null;   // null = 不动配比
            if (defaultComps != null) {
                // 先建新 Map，解析全部完成后才换入：解析期任何一步抛异常都不会留下半张配比表，
                // 也不会出现「已 clear、正在填」的中间态。
                Map<String, GameConfig.MobComposition> parsedDefaultComps = new LinkedHashMap<>();
                for (String mobKey : defaultComps.getKeys(false)) {
                    GameConfig.MobComposition parsedComp = readComposition("default", defaultComps, mobKey,
                            unknownCompositionMobs, numericProblems);
                    if (parsedComp != null) {
                        parsedDefaultComps.put(mobKey, parsedComp);
                    }
                }
                if (parsedDefaultComps.isEmpty()) {
                    emptyDefaultCompositions = true;      // 守卫 B：保持出厂预置，只告警
                } else {
                    alignedDefaultComps = parsedDefaultComps;
                }
            }
            if (alignedDefaultComps != null) {
                // 一次字段写换掉整个 WaveConfig：total-mobs 与 compositions 同时生效，
                // 不存在「新配比 + 旧上限」的中间组合。这里绝不能写 getDefaultWave().setTotalMobs
                // 之后再去 clear() 活 Map —— 那会把守卫 A/B 刚保住的内容抹掉。
                activeConfig.setDefaultWave(new GameConfig.WaveConfig(defaultTotalMobs, alignedDefaultComps));
            } else if (defaultTotalMobs != defaultWave.getTotalMobs()) {
                // 配比保留（守卫 A/B），只有上限变了：单 int 原地赋值，无需换对象。
                defaultWave.setTotalMobs(defaultTotalMobs);
            }
            // P1（F2 + 症状 2）：数字波次一律「按磁盘内容新建」，不再「就地更新内存里已有的对象」。
            // 旧实现有两条丢数据的路径：
            //   ① wc == null（磁盘有该号、内存没有，例如新进程里管理员自建的波次）→ carry-over 拿不到
            //      → continue，整条波次被丢弃（reload 后新波次静默消失）；
            //   ② wc != null（构造器预置的 1/5/10）→ compositions 只对「内存里已有该怪种」的键赋值，
            //      于是 GUI 新加的怪种（给第 1 波加 flying-dropper）在 reload 后被静默丢掉（F2）。
            // P2（症状 1）：键集同样归磁盘 —— 构造器预置的 {1,5,10} 与本会话残留的波次号不再继承，
            // 重建结果严格等于磁盘上出现的数字键集。
            //
            // 三条硬边界（改动任意一条都会造出比原缺陷更严重的事故）：
            //   ① 清空只在 if (waveSection != null) 之内：上面「文件不可用 / 只读 → 保留内存值并提前
            //      return」那条分支的语义是「读不到就不动内存」，那里的 1/5/10 必须原样留着。
            //   ② waveSection == null（整段 waves 被手删 / 文件截断）同样保留构造器预置 ——
            //      「段缺失」= 恢复出厂三波，「段存在但没有数字键」= 真空，两者语义不同。
            //   ③ 只清 getWaves()，绝不碰 getDefaultWave()：snapshot() 对 defaultWave 没有 null 保护，
            //      置 null 会让 hasUnsavedChanges() 与 saveToFile() 同时 NPE。
            //
            // 形状：先在局部 map 里建好，再整体换掉 —— 解析期抛异常时内存保持进场时的状态，
            // 不会留下「已清空 / 半张表」的中间态（半张表 + 快照未推进 ⇒ 保存按钮亮着 ⇒ 一按就固化）。
            Map<Integer, GameConfig.WaveConfig> rebuiltWaves = new LinkedHashMap<>();
            for (String waveKey : waveSection.getKeys(false)) {
                if ("default".equals(waveKey)) continue;
                final int waveNum;
                try {
                    waveNum = Integer.parseInt(waveKey.trim());
                } catch (NumberFormatException ignored) {
                    continue;   // 已在上面汇总进 unknownWaves，此处保持循环继续
                }
                ConfigurationSection waveValues = waveSection.getConfigurationSection(waveKey);
                // total-mobs 缺键 → 回落 waves.default 的上限（显式写 0 才是「本波不刷」）。
                // 绝不用裸 getInt：缺键它返回 0，会把「没写」静默变成「0 只」= 本波空转。
                int totalMobs = readWaveInt(waveValues, "total-mobs",
                        activeConfig.getDefaultWave().getTotalMobs(), true,
                        waveKey + ".total-mobs", numericProblems);
                Map<String, GameConfig.MobComposition> compositions = new LinkedHashMap<>();
                // 路径拼接沿用原实现的写法（settingsYaml + 全路径），不改成 waveValues 上的相对路径：
                // 全路径版本是现网已跑通的形状，换形状属于无收益的风险。
                ConfigurationSection comps = settingsYaml.getConfigurationSection("waves." + waveKey + ".compositions");
                if (comps != null) {
                    for (String mobKey : comps.getKeys(false)) {
                        GameConfig.MobComposition parsed = readComposition(waveKey, comps, mobKey,
                                unknownCompositionMobs, numericProblems);
                        if (parsed != null) {
                            compositions.put(mobKey, parsed);
                        }
                    }
                }
                if (compositions.isEmpty()) {
                    // W2（终极红队）：磁盘上只有 total-mobs、没有 compositions（或全部条目被丢弃）时，
                    // getTotalCount() 与 getTotalWeight() 都是 0 ⇒ WaveManager:123 的 totalWeight <= 0
                    // 命中 spawnDefaultWave ⇒ 本波改刷 5+2×波数 只原版僵尸。玩法可见、磁盘上「看起来写了」、
                    // 而控制台原先零输出 —— 必须点名，否则管理员查不出原因。
                    emptyCompositionWaves.add(waveKey);
                }
                rebuiltWaves.put(waveNum, new GameConfig.WaveConfig(totalMobs, compositions));
            }
            // 全部解析成功后才动活 Map（把中间态窗口压到两条相邻语句）。
            activeConfig.getWaves().clear();
            activeConfig.getWaves().putAll(rebuiltWaves);
            if (activeConfig.getWaves().isEmpty()) {
                // G4：'waves' 段存在但没有任何数字键（只剩 default，或整段是 `waves: {}`）。
                // 清空让这条路径第一次变得可达，而它在控制台原先零输出：管理员会看到「波次配置好像没生效」，
                // 实际每一波都在用 default 模板。段缺失（waveSection == null）不走这里，那条是刻意的出厂兜底。
                plugin.getLogger().warning("game-settings.yml 'waves' section has no numbered wave entries; "
                        + "every wave will fall back to the 'waves.default' composition");
            }
            if (!numericProblems.isEmpty()) {
                plugin.getLogger().warning("game-settings.yml has wave values that are missing or invalid "
                        + "(missing values fall back to the defaults shown; negative values are clamped to 0): "
                        + String.join(", ", numericProblems));
            }
            if (!emptyCompositionWaves.isEmpty()) {
                plugin.getLogger().warning("game-settings.yml waves with no usable 'compositions' entries will fall "
                        + "back to the legacy weight / default spawn path: "
                        + String.join(", ", emptyCompositionWaves));
            }
            if (emptyDefaultCompositions) {
                plugin.getLogger().warning("game-settings.yml 'waves.default.compositions' has no usable entries; "
                        + "keeping the built-in default wave composition ("
                        + activeConfig.getDefaultWave().getCompositions().keySet() + ") — an empty default would "
                        + "drop every wave without its own 'compositions' onto the vanilla-zombie fallback. "
                        + "To stop unconfigured waves on purpose, set 'waves.default.total-mobs: 0' instead");
            }
            if (!unknownCompositionMobs.isEmpty()) {
                plugin.getLogger().warning("game-settings.yml has monster keys under waves.*.compositions that are "
                        + "neither a monsters-section key nor a spawnable vanilla entity type "
                        + "(typo? these entries will not spawn): "
                        + String.join(", ", unknownCompositionMobs));
            }
        }

        loadMonstersConfig();

        lastSavedSnapshot = activeConfig.snapshot();
        // M-15：记录本次读取时的时间戳，供 saveToFile 检测外部修改
        settingsLastModified = settingsFile.lastModified();

        // P2-36：未知顶层键一次性汇总 warning（拼写错误 如 victory-wave 会被静默保默认，
        // 排障困难）。只在加载时扫一遍，不影响任何热路径。
        warnUnknownTopLevelKeys();
    }

    /**
     * 清掉 game-settings.yml 里已废弃的 hammer 伤害键（保存侧墓碑）。
     *
     * <p>与上方 {@code hammer.tier.low.charge} 同款做法：删除一个键时不能只从出厂模板里删，
     * 因为 {@link #saveToFile()} 是全量回写，磁盘残留键会被原样保留（乃至被内存值重新写回），
     * 于是在「保存 → 重载」的往返里复活。两侧都要处理才闭环：保存时置 {@code null}，
     * 加载时不读（见 {@link #loadFromFile()} 的对应注释）。</p>
     *
     * <p>若服主手写过这些键，会在下一次保存时消失——这是刻意的，它们的值从来不被任何玩法读取，
     * 留着只会让人误以为改得动。tier 段（low/medium/high 的 charge/damage/radius）才是现行入口。</p>
     */
    private void tombstoneDeprecatedHammerDamageKeys() {
        settingsYaml.set("hammer.damage-low-threshold", null);
        settingsYaml.set("hammer.damage-medium-threshold", null);
        settingsYaml.set("hammer.damage-low-multiplier", null);
        settingsYaml.set("hammer.damage-medium-multiplier", null);
        settingsYaml.set("hammer.damage-high-multiplier", null);
    }

    /** game-settings.yml 顶层未知键汇总警告（P2-36）。 */
    private void warnUnknownTopLevelKeys() {
        Set<String> unknown = new LinkedHashSet<>();
        for (String key : settingsYaml.getKeys(false)) {
            if (!KNOWN_TOP_LEVEL_KEYS.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            plugin.getLogger().warning("game-settings.yml has unrecognized top-level keys "
                    + "(typo? these keys will not take effect): " + String.join(", ", unknown));
        }
    }

    /**
     * 从 monsters-config.yml 加载所有怪物的完整配置（模型、装备、伤害等扩展字段）。
     * game-settings.yml 中的基础字段优先加载以实现向后兼容。
     */
    private void loadMonstersConfig() {
        YamlFiles.Loaded loaded = monstersGate.load();
        if (!loaded.usable() || monstersGate.isReadOnly()) {
            plugin.getLogger().severe("monsters-config.yml is unusable; keeping monster defaults "
                    + "(nothing will be read from or written to the file this run)");
            return;
        }
        monstersConfigYaml = loaded.config();
        // monsters-config-dc-001：记录本次读盘时的 mtime，供 saveMonstersConfig 检测外部修改
        monstersConfigLastModified = monstersConfigFile.lastModified();

        for (String key : activeConfig.getMonsters().keySet()) {
            GameConfig.MonsterConfig mc = activeConfig.getMonsters().get(key);
            if (mc == null) continue;

            String entityTypeName = monstersConfigYaml.getString(key + ".entity-type");
            if (entityTypeName != null && !entityTypeName.isEmpty()) {
                try {
                    EntityType parsedType = EntityType.valueOf(entityTypeName.trim().toUpperCase(Locale.ROOT));
                    // 合法名称不代表可生成：PLAYER / ITEM / ARROW 等类型无法 spawnEntity，会中断刷怪任务。
                    // 此处只接受实体类是 Mob 子类的类型，不满足则保留默认值。
                    Class<?> entityClass = parsedType.getEntityClass();
                    if (entityClass == null || !Mob.class.isAssignableFrom(entityClass)) {
                        plugin.getLogger().warning("Entity type " + parsedType.name() + " for monster " + key
                                + " is not a spawnable mob, keeping default (" + mc.getEntityType() + ")");
                    } else {
                        mc.setEntityType(parsedType);
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Unknown entity type: " + entityTypeName
                            + " for monster " + key + ", keeping default (" + mc.getEntityType() + ")");
                }
            }
            if (monstersConfigYaml.contains(key + ".base-damage")) {
                mc.setBaseDamage(monstersConfigYaml.getDouble(key + ".base-damage"));
            }
            if (monstersConfigYaml.contains(key + ".itemsadder-id")) {
                mc.setItemsAdderId(monstersConfigYaml.getString(key + ".itemsadder-id"));
            }
            if (monstersConfigYaml.contains(key + ".custom-model-data")) {
                mc.setCustomModelData(monstersConfigYaml.getInt(key + ".custom-model-data"));
            }
            // fix-round1 pattern-6：装备六部位与 starter-kit / hammer / node-repair / fireball 同样的
            // isItem() 硬化。此前只判 non-null，Material.WATER 之类的非物品材质会一路走到
            // WaveManager.applyMonsterEquipment 的 new ItemStack(mat) 上抛异常（该处 catch 只兜
            // IllegalArgumentException 且报的是 "Unknown mob type"，排查方向被带偏）。
            // 解析边界一次性拦住，消费者无需再做六次 null 检查。
            String mainHand = monstersConfigYaml.getString(key + ".main-hand");
            if (mainHand != null && !mainHand.isEmpty()) {
                Material mainHandMat = Material.getMaterial(mainHand);
                if (mainHandMat != null && mainHandMat.isItem()) { mc.setMainHandItem(mainHandMat); }
                else { plugin.getLogger().warning("Unknown or non-item material: " + mainHand
                        + " for monster " + key + ".main-hand, keeping default"); }
            }
            String offHand = monstersConfigYaml.getString(key + ".off-hand");
            if (offHand != null && !offHand.isEmpty()) {
                Material offHandMat = Material.getMaterial(offHand);
                if (offHandMat != null && offHandMat.isItem()) { mc.setOffHandItem(offHandMat); }
                else { plugin.getLogger().warning("Unknown or non-item material: " + offHand
                        + " for monster " + key + ".off-hand, keeping default"); }
            }
            String helmet = monstersConfigYaml.getString(key + ".helmet");
            if (helmet != null && !helmet.isEmpty()) {
                Material helmetMat = Material.getMaterial(helmet);
                if (helmetMat != null && helmetMat.isItem()) { mc.setHelmet(helmetMat); }
                else { plugin.getLogger().warning("Unknown or non-item material: " + helmet
                        + " for monster " + key + ".helmet, keeping default"); }
            }
            String chestplate = monstersConfigYaml.getString(key + ".chestplate");
            if (chestplate != null && !chestplate.isEmpty()) {
                Material chestplateMat = Material.getMaterial(chestplate);
                if (chestplateMat != null && chestplateMat.isItem()) { mc.setChestplate(chestplateMat); }
                else { plugin.getLogger().warning("Unknown or non-item material: " + chestplate
                        + " for monster " + key + ".chestplate, keeping default"); }
            }
            String leggings = monstersConfigYaml.getString(key + ".leggings");
            if (leggings != null && !leggings.isEmpty()) {
                Material leggingsMat = Material.getMaterial(leggings);
                if (leggingsMat != null && leggingsMat.isItem()) { mc.setLeggings(leggingsMat); }
                else { plugin.getLogger().warning("Unknown or non-item material: " + leggings
                        + " for monster " + key + ".leggings, keeping default"); }
            }
            String boots = monstersConfigYaml.getString(key + ".boots");
            if (boots != null && !boots.isEmpty()) {
                Material bootsMat = Material.getMaterial(boots);
                if (bootsMat != null && bootsMat.isItem()) { mc.setBoots(bootsMat); }
                else { plugin.getLogger().warning("Unknown or non-item material: " + boots
                        + " for monster " + key + ".boots, keeping default"); }
            }
        }
        // round5 F5：遍历的是**内存**键集（构造器闭集 3 键），因此 monsters-config.yml 里手写的
        // 第 4 个怪物段永远不会被读到 —— 而 YamlFiles#monstersConfigValidator 只要求「每段含任一
        // 已知子键」，写得再正确也能过哨兵，于是这条路径原先**零日志**：管理员加了怪种、文件完全
        // 合法、控制台一句话都没有。与 game-settings.yml 侧 :1545-1547 的汇总 warning 口径不一致。
        // 这里只补可观测性，不改变行为：新增怪种仍不生效（AI Goal 与刷怪分派都是代码闭集，
        // 见 WaveManager#spawnCustomMob 的 switch）。
        warnUnsupportedMonsterSections();
        // monsters-config-dc-002：写而不读的 9 个键若被改成本文件之外的生效值，一次性提示操作者改错了地方
        warnNonEffectiveMonsterKeys();
    }

    /**
     * 放弃本会话的未保存改动：把 {@link #activeConfig} 换回上次落盘快照的一份新副本。
     *
     * <p><b>实现约束（slice-07-data-consistency-004）：本方法是「换对象」，不是「改内容」。</b>
     * 调用后 {@code activeConfig} 指向一个<b>全新实例</b>，原先那个对象的引用仍然有效、
     * 且仍带着被放弃的改动。因此：</p>
     *
     * <ul>
     *   <li>消费方<b>必须</b>在每次使用时重新取 {@code configManager.getActiveConfig()}，
     *       不得把它缓存进字段或在多次回调之间长期持有——否则回滚后读到的仍是旧对象，
     *       表现为「点了回滚但没有效果」。</li>
     *   <li>当前全库符合该约束：{@code GameConfig} 类型的字段只有本类的
     *       {@code activeConfig} / {@code lastSavedSnapshot}，其余消费点（{@code GameManager}、
     *       {@code WaveManager}、{@code HammerListener}、{@code FireballListener}、
     *       {@code ArenaInstance} 等）全部是方法内局部变量。</li>
     *   <li>若今后要给某个菜单/监听器加 {@code GameConfig} 字段，须改为持有
     *       {@code ConfigManager} 并在使用点取值。</li>
     * </ul>
     *
     * <p>另一处相关语义：本方法不会清除 {@code loadTimeNormalizationPending}——
     * 回滚到的是上次落盘的快照，若载入期归一化尚未落盘，内存又回到了「与磁盘不同」的状态，
     * 脏标志理应继续为真。</p>
     */
    public void revertAll() {
        if (lastSavedSnapshot != null) {
            this.activeConfig = lastSavedSnapshot.snapshot();
        }
    }
}