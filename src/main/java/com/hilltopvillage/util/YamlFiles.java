package com.hilltopvillage.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 配置文件安全读写。
 *
 * <p>解决两个独立问题：① 裸 {@code FileConfiguration} 的写盘方法（{@code File} 重载）直接开文件流截断目标再写；② {@code YamlConfiguration.loadConfiguration(File)}
 * 内部吞掉 {@code InvalidConfigurationException} 返回空配置，调用方 try/catch 永不触发。
 * 而截断产物（0 字节、截在列表/字符串中间）往往还能<b>成功解析</b>，所以读取侧必须叠加
 * 语义哨兵，不能只看「有没有抛异常」。</p>
 *
 * <p><b>为什么读盘不用 {@code loadConfiguration(File)}：</b>它把解析失败降级成「空配置 + SEVERE 日志」，
 * 调用方无法区分「文件损坏」与「文件本来就空」。本类改用 {@code Files.readString} +
 * {@link YamlConfiguration#loadFromString(String)}，让解析异常真的抛出来。</p>
 *
 * <p><b>为什么写盘不用 {@code save(File)}：</b>它先截断目标文件再写，进程在中间退出就留下半截文件。
 * 本类写「同目录唯一临时名 + {@code ATOMIC_MOVE}」，失败时目标文件保持原样。</p>
 *
 * <p>本类不持有任何 Bukkit 单例/插件实例，{@link Logger} 由调用方传入，因此可直接单测。</p>
 */
public final class YamlFiles {

    private YamlFiles() {
    }

    /**
     * 读盘结果。{@code config == null} 表示「文件存在但不可用」，调用方必须进入只读降级。
     *
     * @param config  解析并通过哨兵的配置；不可用时为 {@code null}
     * @param status  读盘结论
     * @param detail  不可用的原因（写进日志/供调用方展示）；正常时为 {@code null}
     */
    public record Loaded(YamlConfiguration config, Status status, String detail) {

        /** 是否可以安全使用这份配置（含「文件不存在 → 空的可写配置」这一合法情形）。 */
        public boolean usable() {
            return config != null;
        }
    }

    public enum Status {
        /** 文件不存在（首次运行）→ config 是可写的空配置，不是错误 */
        ABSENT,
        /** 文件存在，解析通过且语义哨兵通过 */
        OK,
        /** 解析失败 → 已隔离为 {@code *.corrupt} */
        PARSE_FAILED,
        /** 解析成功但语义哨兵不通过 → 已隔离为 {@code *.corrupt} */
        SUSPECT,
        /** 隔离动作本身失败（文件被占用/只读）→ 磁盘原件保留 */
        QUARANTINE_FAILED
    }

    /**
     * 读盘 + 校验 + 失败隔离。
     *
     * <p>隔离用 {@link Files#move} 而不是 {@code File.renameTo}：{@code renameTo} 跨文件系统会静默返回
     * false，{@code Files.move} 会抛异常，才能如实记录「隔离失败、原件保留」。</p>
     *
     * @param file      目标文件
     * @param validator 语义哨兵：返回 null = 通过；返回字符串 = 「为什么可疑」（写进日志）。
     *                  没有哨兵时传 {@code null}（只读文件、纯解析校验场景）
     * @param logger    日志出口（Bukkit 侧传 {@code plugin.getLogger()}）
     * @return 读盘结果；{@code usable() == false} 时调用方必须拒绝写盘
     */
    public static Loaded load(File file, Function<YamlConfiguration, String> validator, Logger logger) {
        if (!file.exists()) {
            return new Loaded(new YamlConfiguration(), Status.ABSENT, null);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        String raw;
        try {
            raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            // 空文件 / 只有空白 = 截断写最典型产物。文件已存在 ⇒ 不可能是首次运行
            if (raw.isBlank()) {
                return quarantine(file, Status.SUSPECT, "file is empty or has no YAML document", logger);
            }
            yaml.loadFromString(raw);                       // 解析错误会真的抛出来
        } catch (Exception parseFailure) {
            return quarantine(file, Status.PARSE_FAILED, parseFailure.toString(), logger);
        }
        String reason = validator == null ? null : validator.apply(yaml);
        if (reason != null) {
            return quarantine(file, Status.SUSPECT, reason, logger);
        }
        return new Loaded(yaml, Status.OK, null);
    }

    /**
     * 原子写：唯一临时名（同目录）+ ATOMIC_MOVE + REPLACE_EXISTING。
     *
     * <p>单用 REPLACE_EXISTING 在 Windows 上是「先删再 rename」，非原子；ATOMIC_MOVE 才走
     * MoveFileEx(MOVEFILE_REPLACE_EXISTING)。tmp 与目标同目录 ⇒ 不会跨分区，
     * 万一抛 AtomicMoveNotSupportedException 再降级为普通 move。</p>
     *
     * <p>失败时抛 {@link IOException}（<b>不</b>静默吞掉）：调用方必须如实告诉管理员「没保存成功」。</p>
     */
    public static void write(File file, YamlConfiguration yaml) throws IOException {
        File target = file.getAbsoluteFile();
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create directory " + parent);
        }
        File tmp = new File(parent, target.getName() + "." + System.nanoTime() + ".tmp");
        try {
            Files.write(tmp.toPath(), yaml.saveToString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                tmp.deleteOnExit();
            }
        }
    }

    /**
     * 把不可用的文件挪成 {@code *.corrupt}，并返回「不可用」的结论。
     *
     * <p>{@code REPLACE_EXISTING}：上一次的坏文件会被覆盖。刻意如此——保留「最新一次现场」比
     * 堆一串历史上更有用，日志里会打出被截断的原因。</p>
     */
    private static Loaded quarantine(File file, Status status, String detail, Logger logger) {
        File parent = file.getAbsoluteFile().getParentFile();
        File corrupt = new File(parent, file.getName() + ".corrupt");
        try {
            Files.move(file.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.severe(file.getName() + " is unusable (" + detail + "); renamed to "
                    + corrupt.getName() + "; this run is READ-ONLY for that file");
            return new Loaded(null, status, detail);
        } catch (IOException moveFailure) {
            logger.severe(file.getName() + " is unusable (" + detail + ") and quarantine FAILED ("
                    + moveFailure.getMessage() + "); original kept; this run is READ-ONLY");
            return new Loaded(null, Status.QUARANTINE_FAILED, detail);
        }
    }

    // ------------------------------------------------------------------
    // 逐文件语义哨兵（PLAN D1.2-③）
    //
    // 这一层是「拦住截断却解析成功」的唯一手段：实测 0 字节、截在列表中间、截在字符串中间
    // 都能被 snakeyaml 成功解析。哨兵只对「文件已存在」生效（文件不存在时 load() 直接返回
    // ABSENT，不会调用 validator），因此首次运行不会被误杀。
    // ------------------------------------------------------------------

    /** {@code arenas.yml} 的哨兵。{@code ArenaManager} 持有该文件的 gate，三个写入方共用。 */
    public static Function<YamlConfiguration, String> arenasValidator() {
        return yaml -> {
            // ① schema-version：存在就要求 ≥1；缺键放行（旧版文件可能没有）
            if (yaml.contains("schema-version") && yaml.getInt("schema-version", 0) < 1) {
                return "schema-version is present but invalid: " + yaml.get("schema-version");
            }
            // ② 顶层必须有 arenas 段——截在 arenas 段之前的文件不会有它
            if (!(yaml.get("arenas") instanceof ConfigurationSection arenas)) {
                return "top-level 'arenas' section is missing";
            }
            Set<String> arenaIds = arenas.getKeys(false);
            // ③ 「arenas 空 + lobby/default-arena 还在」只可能是截断（出厂模板 arenas 有 3 个子键）
            if (arenaIds.isEmpty()
                    && (yaml.contains("default-arena") || yaml.contains("lobby"))) {
                return "'arenas' section is empty while default-arena/lobby still exist";
            }
            if (arenaIds.isEmpty()) {
                return null;
            }
            // ④ 至少要有一个竞技场声明了非空 world，否则这份文件不可能是正常写出的结果。
            //    world 缺失/非字符串/空白 ⇒ ArenaManager.readDefinition 会把它 skip 掉，
            //    「一个能用的都不剩」与「整份文件写坏了」在本层无法区分，按可疑处理更安全。
            boolean anyUsableWorld = false;
            // ⑤ 截断特征（fix-round1 monsters-config-dc-003：修掉「只有 world」的误杀）
            //
            //    旧实现把「键集合被 {world} 覆盖」直接当作截断签名，于是
            //    arenas:<id>: { world: xxx } 会被判 SUSPECT → 整份文件改名 .corrupt →
            //    ArenaManager.load() 清空 registry、本进程拒写。这是误杀：加载器明确接受这种形态。
            //    ArenaManager.validationFailure：「core 缺失是合法的（core == null 表示尚未部署）」，
            //    且 readDefinition 对缺 game 段走 Builder 初值，所以最小竞技场段是合法配置。
            //
            //    为什么裸 {world} 不能当截断证据——按本仓库全部写入方的实际键序核对：
            //      · 出厂文件 arenas.yml:44 起：display-name → enabled → world → waiting-location …
            //      · AdminDeployManager.saveSpawnPoints：spawn-points → mob-spawn-points →
            //        villagers → core.* → waiting-location（全在 world 之后）
            //    world 永远不是第一个落入段里的键，而写到一半只会留下键序的【前缀】。因此任何
            //    写入方的截断产物都不可能是「只有 world」：要么是空竞技场段（`arena1: {}`），
            //    要么至少带 display-name / enabled / spawn-points（三者都不在本白名单内，天然不触发）。
            //
            //    据此保留两种可复现的真截断形态：
            //      (a) 空竞技场段 —— 永远可疑；
            //      (b) 薄段出现在至少一个【饱满】竞技场之后 —— 截断只砍文件尾部，前面不可能变薄。
            //    「饱满」= 键集合里存在白名单之外的键。单竞技场的裸 {world} 文件两种都不命中，
            //    按合法放行。代价如实记录：若被放过的真是一份被截断的极简文件，后果是该竞技场
            //    显示「地图缺失 / 未部署」（可见、可用向导重建）；而假阳性的后果是整份配置被隔离、
            //    registry 清空、进程拒写。两害相权，前者可自愈，后者不能。
            //
            //    实测补充（本回合 probe 结论）：`arena1:` 后面什么都不写（无值、无 `{}`）时，
            //    YamlConfiguration 会把该键整个丢弃，解析结果是 arenas 段**自身为空**、
            //    arenaIds=[]，根本走不到本分支，而是落到上面的分支 ③。这是既有的、本次未改动的
            //    行为；出厂文件的截断会被 ③ 的 lobby/default-arena 信号接住。
            boolean sawRichArena = false;
            for (String id : arenaIds) {
                ConfigurationSection arena = arenas.getConfigurationSection(id);
                if (arena == null) {
                    continue;
                }
                String world = arena.getString("world");
                if (world != null && !world.isBlank()) {
                    anyUsableWorld = true;
                }
                Set<String> arenaKeys = arena.getKeys(false);
                boolean thin = arenaKeys.stream().allMatch(ARENA_TRUNCATION_ALLOWED_KEYS::contains);
                if (thin && (arenaKeys.isEmpty() || sawRichArena)) {
                    return "arena '" + id + "' has only " + arenaKeys
                            + " — looks like a truncated write";
                }
                if (!thin) {
                    sawRichArena = true;
                }
            }
            if (!anyUsableWorld) {
                return "no arena declares a non-blank 'world'";
            }
            return null;
        };
    }

    /** {@code nodes.yml} 的哨兵：顶层 {@code nodes} 必须存在且是列表；空列表合法（0 节点是正常状态）。 */
    public static Function<YamlConfiguration, String> nodesValidator() {
        return yaml -> yaml.isList("nodes") ? null : "top-level 'nodes' list is missing";
    }

    /**
     * {@code game-settings.yml} 的哨兵：至少命中 3 个已知顶层键。
     *
     * <p>键集合镜像 {@code ConfigManager.KNOWN_TOP_LEVEL_KEYS}（该字段为 private，无法直接引用；
     * 改动时两处必须同步）。出厂文件 322 行、19 个顶层键，命中 3 的下限极宽松。</p>
     */
    public static Function<YamlConfiguration, String> gameSettingsValidator() {
        return yaml -> {
            int hits = 0;
            for (String key : GAME_SETTINGS_KNOWN_TOP_LEVEL_KEYS) {
                if (yaml.contains(key)) {
                    hits++;
                }
            }
            return hits >= 3 ? null : "only " + hits + " known top-level key(s) present (need >= 3)";
        };
    }

    /**
     * {@code monsters-config.yml} 的哨兵：至少 1 个怪物段，且每个怪物段至少命中 1 个已知键。
     *
     * <p><b>为什么不再要求 {@code entity-type}（fix-round1 monsters-config-dc-004）：</b>旧判定
     * 「至少 1 个段带 {@code entity-type}」只对<b>出厂文件</b>成立，对<b>手写文件</b>不成立。
     * 文件头 {@code monsters-config.yml:8-24} 列出的「生效」字段有 10 项，{@code entity-type} 只是
     * 第 1 项；而它恰好是<b>唯一没有 GUI 编辑器</b>的键（其余装备/攻击键都能在怪物设置页改）。
     * 于是「服主只改装备、不写 entity-type」这种完全合法的编辑会被判 SUSPECT，
     * 整份文件被重命名为 {@code *.corrupt}——为一个可选键丢掉全部配置，代价与收益倒挂。</p>
     *
     * <p><b>新判定为什么不算放水：</b>改为「每个怪物段都必须至少命中一个
     * {@link #MONSTER_KNOWN_KEYS}」，覆盖面反而<b>更宽</b>——旧规则只查
     * 「有没有任意一个段带 entity-type」，新规则对<b>每个</b>段都要求它是本插件认识的形态。
     * 真正该拦的形态（0 个段 = 截断在第一个段之前、段内全是拼错/未知键 = 写到一半或写坏）
     * 依然被拦住，只是不再因为缺一个可选键而误杀整份文件。</p>
     */
    public static Function<YamlConfiguration, String> monstersConfigValidator() {
        return yaml -> {
            int sections = 0;
            for (String key : yaml.getKeys(false)) {
                ConfigurationSection section = yaml.getConfigurationSection(key);
                if (section == null) {
                    continue; // 顶层标量不是怪物段，不计入也不判可疑
                }
                sections++;
                if (MONSTER_KNOWN_KEYS.stream().noneMatch(section::contains)) {
                    return "monster section '" + key + "' has none of the known keys " + MONSTER_KNOWN_KEYS;
                }
            }
            return sections >= 1 ? null : "no monster section found (file truncated before the first entry?)";
        };
    }

    /**
     * {@code monsters-config.yml} 中「本插件认识的」怪物子键。
     *
     * <p>取文件头 {@code monsters-config.yml:8-24} 两张清单的并集：10 个生效键 + 10 个不生效键。
     * 「不生效」的键也收，是因为出厂文件里确实写着它们（被 {@code game-settings.yml} 覆盖），
     * 它们同样能证明这一段是正常的怪物段而不是截断/拼错的产物。改动时与文件头同步。</p>
     */
    private static final Set<String> MONSTER_KNOWN_KEYS = Set.of(
            "entity-type", "base-damage", "itemsadder-id", "custom-model-data",
            "main-hand", "off-hand", "helmet", "chestplate", "leggings", "boots",
            "display-name", "display-egg", "health", "speed", "hook-velocity",
            "cooldown-ticks", "stun-duration-ticks", "airdrop-mob-count",
            "explode-damage-multiplier", "entity-explosion");

    /**
     * {@code display.yml} 的哨兵：顶层至少一个展示段，且段内不能只剩段头。
     *
     * <p><b>为什么这里不能只靠「能不能解析」：</b>{@code display.yml} 的主体是一份结构化的
     * {@code lines} / {@code header} / {@code footer} 列表，而截断最典型的产物正好落在结构边上——
     * 写到一半中断时，剩下的可能是 {@code scoreboard:} 孤零零一个段头，也可能把 {@code lines}
     * 截成标量。snakeyaml 对这两种形态都能<b>成功解析</b>（段头 = 空段，标量 = 合法标量），
     * 于是「解析通过」完全无法区分「服主故意留空」与「文件被截断」。所以判定必须落在语义上：
     * 段头无任何已知子键 = 截断特征；{@code lines} 不是列表 = 不是加载器能消费的形态。</p>
     *
     * <p><b>刻意的非规则：</b>{@code lines: []} 是<b>合法</b>的「只留标题」，绝不当成截断——
     * 空文件已被 {@link #load} 的 {@code raw.isBlank()} 分支拦下，这里的空列表只可能来自
     * 管理员显式书写。同理，缺 {@code lines} 键 = 恢复出厂布局，也不可疑。</p>
     *
     * <p><b>一个必须知道的 Bukkit 细节：</b>真正「只剩段头」的 {@code scoreboard:}（后面一个子键都没有）
     * 在 snakeyaml 里值就是 {@code null}，{@code YamlConfiguration} 会把该键整个丢弃
     * （{@code contains}/{@code getValues} 都看不到它）。因此这种最典型的截断形态<b>不会</b>
     * 走到下面的「bare header」分支，而是落进第 ① 条「两段都没有」——判定结论同样是 SUSPECT，
     * 只是文案不同。下面的 bare-header 分支覆盖的是另一种形态：段还在、但子键全是未知名字
     * （典型是写到一半或拼错键名），此时段对象仍然存在。</p>
     */
    public static Function<YamlConfiguration, String> displayValidator() {
        return yaml -> {
            ConfigurationSection board = yaml.getConfigurationSection("scoreboard");
            ConfigurationSection tab = yaml.getConfigurationSection("tab");
            // ① 两段都没有 → 不可能是正常的展示配置（截断在第一个段之前，或顶层被写坏）
            if (board == null && tab == null) {
                return "neither 'scoreboard' nor 'tab' section is present";
            }
            // ② 段存在但一个已知子键都没有 = 只剩段头，典型截断产物
            if (board != null && DISPLAY_BOARD_KEYS.stream().noneMatch(board::contains)) {
                return "the 'scoreboard' section is a bare header (truncated file?)";
            }
            if (tab != null && DISPLAY_TAB_KEYS.stream().noneMatch(tab::contains)) {
                return "the 'tab' section is a bare header (truncated file?)";
            }
            // ③ lines 若存在必须是列表；空列表合法（只留标题），不在此判可疑
            if (board != null && board.contains("lines") && !board.isList("lines")) {
                return "scoreboard.lines must be a list";
            }
            return null;
        };
    }

    /** {@code scoreboard} 段的任一已知子键即可证明它不是「只剩段头」。 */
    private static final Set<String> DISPLAY_BOARD_KEYS = Set.of("enabled", "lines", "title");

    /** {@code tab} 段的任一已知子键即可证明它不是「只剩段头」。 */
    private static final Set<String> DISPLAY_TAB_KEYS = Set.of("enabled", "header", "footer", "contexts");

    /**
     * {@code messages.yml} 的哨兵：{@code en} 或 {@code zh} <b>至少一个</b>是段且 ≥50 个键。
     *
     * <p>不要求两者都在——服主可能只保留一种语言。实测出厂文件 en/zh 各 1012 个键，50 极宽松。</p>
     */
    public static Function<YamlConfiguration, String> messagesValidator() {
        return yaml -> {
            for (String locale : List.of("en", "zh")) {
                ConfigurationSection section = yaml.getConfigurationSection(locale);
                if (section != null && section.getKeys(true).size() >= MESSAGES_MIN_KEYS) {
                    return null;
                }
            }
            return "neither 'en' nor 'zh' has at least " + MESSAGES_MIN_KEYS + " keys";
        };
    }

    /** messages.yml 的语言段最少键数（哨兵下限，不是功能约束）。 */
    private static final int MESSAGES_MIN_KEYS = 50;

    /**
     * 「一个竞技场段的键集合若只被这些键覆盖，可能是截断写产物」——出厂模板的竞技场段永远有更多键，
     * 且 {@code ArenaManager.clearDeploymentData} 只删部署期键、保留 world/enabled/display-name。
     *
     * <p><b>这里只是「必要不充分」条件</b>（fix-round1 monsters-config-dc-003）：光凭键集合不足以定罪，
     * 因为删掉 {@code core} 后的最小竞技场段正是加载器认可的「尚未部署」形态
     * （{@code ArenaManager.validationFailure}：{@code core == null} 合法）。要判截断，
     * 还必须命中 {@code arenasValidator} ⑤ 的附加信号（空段，或文件仍带着姊妹段/前面的竞技场）。
     * 详见 {@link #arenasValidator()} 的注释。</p>
     */
    private static final Set<String> ARENA_TRUNCATION_ALLOWED_KEYS = Set.of("world");

    /** 与 {@code ConfigManager.KNOWN_TOP_LEVEL_KEYS} 同步（该字段为 private，此处镜像）。 */
    private static final Set<String> GAME_SETTINGS_KNOWN_TOP_LEVEL_KEYS = Set.of(
            "world-name", "core", "min-players", "max-players", "victory-waves",
            "wave-interval-seconds", "wave-timeout-seconds", "defeat-countdown-seconds",
            "villager-health", "rejoin-timeout-seconds", "empty-arena-end-seconds", "map-restore", "scoreboard",
            "hammer", "fireball", "shop", "starter-kit", "nodes", "spawning",
            "monsters", "waves");
}
