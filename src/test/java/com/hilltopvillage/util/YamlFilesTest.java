package com.hilltopvillage.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link YamlFiles} 的行为测试。
 *
 * <p><b>为什么必须用「真实文件 + 真实 YamlConfiguration」而不是 mock：</b>本类要拦的两个问题
 * （截断写产物能被成功解析、{@code loadConfiguration} 吞异常）都是第三方库的<b>实际行为</b>，
 * mock 掉就测不到了。测试用 {@link TempDir} 隔离，无需 MockBukkit。</p>
 */
class YamlFilesTest {

    private static final Logger LOG = Logger.getLogger("YamlFilesTest");

    /**
     * 最小「形态正常」的 arenas.yml：竞技场段除 world 外还有别的键。
     *
     * <p>只写 {@code world} 一个键会被哨兵②b 判为截断特征（这正是本组用例要区分的东西），
     * 所以凡是「期望通过」的样本都不能用单键竞技场段。</p>
     */
    private static final String REALISTIC_ARENA =
            "arenas:\n  arena1:\n    display-name: x\n    enabled: true\n    world: hilltop_arena1\n";

    // ---------------------------------------------------------------- 读盘：隔离与判定

    @Test
    @DisplayName("0 字节文件 → SUSPECT，原文件移为 .corrupt，config 为 null")
    void emptyFileIsSuspectAndQuarantined(@TempDir Path dir) throws IOException {
        File file = dir.resolve("arenas.yml").toFile();
        Files.write(file.toPath(), new byte[0]);

        YamlFiles.Loaded loaded = YamlFiles.load(file, YamlFiles.arenasValidator(), LOG);

        assertEquals(YamlFiles.Status.SUSPECT, loaded.status());
        assertFalse(loaded.usable());
        assertNull(loaded.config());
        assertFalse(file.exists(), "被隔离的原文件不应留在原地");
        assertTrue(dir.resolve("arenas.yml.corrupt").toFile().exists(), ".corrupt 应被创建");
        assertEquals(0, Files.size(dir.resolve("arenas.yml.corrupt")), ".corrupt 应保留原内容");
    }

    @Test
    @DisplayName("只有注释的文件 → SUSPECT（解析不抛，但已存在却无文档）")
    void commentOnlyFileIsSuspect(@TempDir Path dir) throws IOException {
        File file = dir.resolve("arenas.yml").toFile();
        Files.writeString(file.toPath(), "# 只有注释\n# 没有文档\n", StandardCharsets.UTF_8);

        YamlFiles.Loaded loaded = YamlFiles.load(file, YamlFiles.arenasValidator(), LOG);

        assertEquals(YamlFiles.Status.SUSPECT, loaded.status());
        assertFalse(loaded.usable());
        assertTrue(dir.resolve("arenas.yml.corrupt").toFile().exists());
    }

    @Test
    @DisplayName("缩进错位 → PARSE_FAILED（解析异常真的抛出并被接住）")
    void badIndentationIsParseFailed(@TempDir Path dir) throws IOException {
        File file = dir.resolve("game-settings.yml").toFile();
        Files.writeString(file.toPath(), "arenas:\n arena1:\n world: x\n   y: 2\n", StandardCharsets.UTF_8);

        YamlFiles.Loaded loaded = YamlFiles.load(file, YamlFiles.gameSettingsValidator(), LOG);

        assertEquals(YamlFiles.Status.PARSE_FAILED, loaded.status());
        assertFalse(loaded.usable());
        assertTrue(dir.resolve("game-settings.yml.corrupt").toFile().exists());
    }

    @Test
    @DisplayName("截断在列表中间 → SUSPECT：靠语义哨兵，不是靠异常")
    void truncatedListIsSuspectOnlyBecauseOfSentinel(@TempDir Path dir) throws IOException {
        // 实测：snakeyaml 对这份输入【不抛异常】，解析出 [{x=1.0, y=null}]。
        // 所以只有校验器能拦住它 —— 这是本类相对「只换 loadFromString」的实质增量。
        String truncated = "spawn-points:\n  - x: 1.0\n    y:";

        File withoutSentinel = dir.resolve("no-sentinel.yml").toFile();
        Files.writeString(withoutSentinel.toPath(), truncated, StandardCharsets.UTF_8);
        YamlFiles.Loaded unchecked = YamlFiles.load(withoutSentinel, null, LOG);
        assertEquals(YamlFiles.Status.OK, unchecked.status(),
                "无哨兵时必须解析成功（证明这一层不是异常兜住的）");
        assertEquals(1, unchecked.config().getMapList("spawn-points").size());

        File withSentinel = dir.resolve("arenas.yml").toFile();
        Files.writeString(withSentinel.toPath(), truncated, StandardCharsets.UTF_8);
        YamlFiles.Loaded checked = YamlFiles.load(withSentinel, YamlFiles.arenasValidator(), LOG);
        assertEquals(YamlFiles.Status.SUSPECT, checked.status());
        assertFalse(checked.usable());
        assertNotNull(checked.detail());
        assertTrue(checked.detail().contains("arenas"), "detail 应说明缺什么：" + checked.detail());
    }

    @Test
    @DisplayName("文件不存在 → ABSENT，返回可写的空配置（不是错误）")
    void absentFileIsUsableEmptyConfig(@TempDir Path dir) {
        File file = dir.resolve("nodes.yml").toFile();

        YamlFiles.Loaded loaded = YamlFiles.load(file, YamlFiles.nodesValidator(), LOG);

        assertEquals(YamlFiles.Status.ABSENT, loaded.status());
        assertTrue(loaded.usable());
        assertNotNull(loaded.config());
        assertTrue(loaded.config().getKeys(false).isEmpty());
        assertNull(loaded.detail());
        assertFalse(file.exists(), "load 不应顺手创建文件");
    }

    @Test
    @DisplayName("已有的 .corrupt 被覆盖（保留最新一次现场）")
    void existingCorruptIsReplaced(@TempDir Path dir) throws IOException {
        File file = dir.resolve("arenas.yml").toFile();
        Files.writeString(dir.resolve("arenas.yml.corrupt"), "old-scene\n", StandardCharsets.UTF_8);
        Files.write(file.toPath(), new byte[0]);

        YamlFiles.load(file, YamlFiles.arenasValidator(), LOG);

        assertEquals("", Files.readString(dir.resolve("arenas.yml.corrupt"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("合法出厂 arenas.yml → OK，且 saveToString() 后注释逐字保留")
    void factoryArenasLoadsAndKeepsComments(@TempDir Path dir) throws IOException {
        String factory = readResource("/arenas.yml");
        File file = dir.resolve("arenas.yml").toFile();
        Files.writeString(file.toPath(), factory, StandardCharsets.UTF_8);

        YamlFiles.Loaded loaded = YamlFiles.load(file, YamlFiles.arenasValidator(), LOG);

        assertEquals(YamlFiles.Status.OK, loaded.status());
        assertNotNull(loaded.config());
        assertTrue(file.exists(), "OK 的文件不应被隔离");
        assertFalse(dir.resolve("arenas.yml.corrupt").toFile().exists());

        String saved = loaded.config().saveToString();

        // 整行注释（含文件头、缩进内的说明）必须逐字保留
        List<String> before = commentLines(factory);
        List<String> after = commentLines(saved);
        assertEquals(before, after, "整行注释必须逐字保留（数量 " + before.size() + "）");
        assertTrue(before.size() > 20, "出厂文件应有大量注释，前置条件检查");

        // 行尾注释的文本也必须保留（缩进/空白由 yaml 库重排，不要求空白一致）
        assertEquals(inlineCommentTexts(factory), inlineCommentTexts(saved),
                "行尾注释文本必须保留");
    }

    @Test
    @DisplayName("出厂 nodes/game-settings/monsters/messages 全部通过各自哨兵")
    void factoryFilesPassTheirSentinel(@TempDir Path dir) throws IOException {
        assertFactoryOk(dir, "/game-settings.yml", YamlFiles.gameSettingsValidator());
        assertFactoryOk(dir, "/monsters-config.yml", YamlFiles.monstersConfigValidator());
        assertFactoryOk(dir, "/messages.yml", YamlFiles.messagesValidator());

        // nodes.yml 不在 resources 里（首次运行走 ABSENT）→ 用最小合法内容验证
        File nodes = dir.resolve("nodes.yml").toFile();
        Files.writeString(nodes.toPath(), "nodes: []\n", StandardCharsets.UTF_8);
        assertEquals(YamlFiles.Status.OK, YamlFiles.load(nodes, YamlFiles.nodesValidator(), LOG).status(),
                "空 nodes 列表合法（0 节点是正常状态）");
    }

    // ---------------------------------------------------------------- 语义哨兵单测

    @Test
    @DisplayName("arenas 哨兵：缺 arenas 段 / 空 arenas + lobby / schema-version 非法 都判可疑")
    void arenasSentinelRejectsTruncations() {
        assertSuspect(YamlFiles.arenasValidator(), "schema-version: 2\n", "缺 arenas 段");
        assertSuspect(YamlFiles.arenasValidator(),
                "default-arena: arena1\nlobby:\n  world: lobby\narenas: {}\n", "arenas 空 + lobby 并存");
        assertSuspect(YamlFiles.arenasValidator(), "schema-version: 0\n" + REALISTIC_ARENA,
                "schema-version 存在但 < 1");
        assertSuspect(YamlFiles.arenasValidator(), "arenas: []\n", "arenas 不是段");
        // 放行：旧文件没有 schema-version，但竞技场段形态正常
        assertOk(YamlFiles.arenasValidator(), REALISTIC_ARENA);
    }

    @Test
    @DisplayName("arenas 哨兵②b：真截断形态 → SUSPECT 并隔离；裸 {world} 最小竞技场放行（dc-003）")
    void arenasSentinelRejectsSpecReproSample(@TempDir Path dir) throws IOException {
        // --- 保留的真截断形态 1：空竞技场段（一个键都没有，砍在段头之后）---
        // 实测 `arena1: {}` 解析出 getKeys(false) == []，段对象仍存在 → 命中薄段分支。
        File file = dir.resolve("arenas.yml").toFile();
        String emptySection = "arenas:\n  arena1: {}\n";
        Files.writeString(file.toPath(), emptySection, StandardCharsets.UTF_8);

        YamlFiles.Loaded loaded = YamlFiles.load(file, YamlFiles.arenasValidator(), LOG);

        assertEquals(YamlFiles.Status.SUSPECT, loaded.status(),
                "空竞技场段必须判 SUSPECT：" + loaded.detail());
        assertFalse(loaded.usable());
        assertFalse(file.exists(), "必须被隔离");
        assertTrue(dir.resolve("arenas.yml.corrupt").toFile().exists());
        assertEquals(emptySection,
                Files.readString(dir.resolve("arenas.yml.corrupt"), StandardCharsets.UTF_8),
                ".corrupt 应保留原始字节");

        assertSuspect(YamlFiles.arenasValidator(), "arenas:\n  arena1: {}\n",
                "竞技场段一个键都没有也是截断形态");

        // --- 保留的真截断形态 2：薄段跟在【饱满】竞技场之后（截断只砍文件尾部）---
        assertSuspect(YamlFiles.arenasValidator(),
                "arenas:\n"
                        + "  arena1:\n    display-name: x\n    enabled: true\n    world: w1\n"
                        + "  arena2:\n    world: w2\n",
                "前面已有饱满竞技场，尾部薄段 = 截断特征");
    }

    @Test
    @DisplayName("arenas 哨兵：单竞技场裸 {world}（core 未部署）→ 放行，不再隔离整份文件（dc-003）")
    void arenasSentinelAcceptsMinimalCorelessArena() {
        // 回归用例：修复前这条断言为真 —— 整份 arenas.yml 被改名 .corrupt、registry 清空。
        // 依据：ArenaManager.validationFailure「core 缺失是合法的（core == null 表示尚未部署）」，
        // 且 readDefinition 对缺 game 段走 ArenaDefinition.Builder 字段初值。
        assertOk(YamlFiles.arenasValidator(), "arenas:\n  arena1:\n    world: hilltop_arena1\n",
                "手写的最小竞技场段是加载器认可的「尚未部署」形态");

        // 值被截断成 "are" 的形态同样放行：world 不是任一写入方最先写的键，
        // 本层无法把「手写的短世界名」与「值被截断」区分开，硬判会重新误杀合法文件。
        assertOk(YamlFiles.arenasValidator(), "arenas:\n  arena1:\n    world: are\n",
                "单竞技场裸 world 不再当截断证据");

        // 多竞技场里每个都只有 world：没有「前面已饱满」的信号，同样放行
        assertOk(YamlFiles.arenasValidator(),
                "arenas:\n  arena1:\n    world: w1\n  arena2:\n    world: w2\n",
                "两个薄段并列不构成尾部截断证据");
    }

    @Test
    @DisplayName("arenas 哨兵：文件级隔离只发生在真截断形态上（dc-003 端到端）")
    void minimalCorelessArenaIsNotQuarantined(@TempDir Path dir) throws IOException {
        // 缺陷原文：这条形态会让整份文件被隔离、ArenaManager.load() 清空 registry、进程拒写。
        File file = dir.resolve("arenas.yml").toFile();
        String minimal = "arenas:\n  arena1:\n    world: hilltop_arena1\n";
        Files.writeString(file.toPath(), minimal, StandardCharsets.UTF_8);

        YamlFiles.Loaded loaded = YamlFiles.load(file, YamlFiles.arenasValidator(), LOG);

        assertEquals(YamlFiles.Status.OK, loaded.status(), "不应判可疑：" + loaded.detail());
        assertTrue(loaded.usable());
        assertNotNull(loaded.config());
        assertTrue(file.exists(), "合法文件必须留在原地");
        assertFalse(dir.resolve("arenas.yml.corrupt").toFile().exists(), "不应产生 .corrupt");
        assertEquals("hilltop_arena1", loaded.config().getString("arenas.arena1.world"));
    }

    @Test
    @DisplayName("arenas 哨兵②a：有子段但没有任何非空 world → 可疑")
    void arenasSentinelRequiresAtLeastOneUsableWorld() {
        assertSuspect(YamlFiles.arenasValidator(),
                "arenas:\n  a:\n    enabled: true\n    display-name: x\n",
                "world 缺失");
        assertSuspect(YamlFiles.arenasValidator(),
                "arenas:\n  a:\n    world: \"  \"\n    enabled: true\n",
                "world 是空白串");
        // 半配置竞技场（只有 enabled，无 world）+ 一个正常竞技场 → 放行（loader 会 skip 前者）
        assertOk(YamlFiles.arenasValidator(),
                "arenas:\n  stray:\n    enabled: false\n  arena1:\n    display-name: x\n"
                        + "    enabled: true\n    world: hilltop_arena1\n");
        // clearDeploymentData 之后的最小合法形态（world/enabled/display-name 保留，部署键被删）→ 放行
        assertOk(YamlFiles.arenasValidator(),
                "arenas:\n  arena1:\n    display-name: x\n    enabled: true\n    world: hilltop_arena1\n",
                "刚被重置过的竞技场不能误判");
    }

    @Test
    @DisplayName("nodes 哨兵：缺 nodes 键 / 不是列表 判可疑；空列表放行")
    void nodesSentinel() {
        assertSuspect(YamlFiles.nodesValidator(), "other: 1\n");
        assertSuspect(YamlFiles.nodesValidator(), "nodes:\n  a: 1\n");
        assertOk(YamlFiles.nodesValidator(), "nodes: []\n");
        assertOk(YamlFiles.nodesValidator(), "nodes:\n  - world: w\n");
    }

    @Test
    @DisplayName("game-settings 哨兵：少于 3 个已知顶层键判可疑（截断在开头）")
    void gameSettingsSentinel() {
        assertSuspect(YamlFiles.gameSettingsValidator(), "world-name: world\nmin-players: 2\n");
        assertOk(YamlFiles.gameSettingsValidator(),
                "world-name: world\nmin-players: 2\nmax-players: 6\n");
    }

    @Test
    @DisplayName("monsters 哨兵：空文件/未知键判可疑；缺 entity-type 但键可识别 → 放行（dc-004）")
    void monstersSentinel() {
        // --- 仍然可疑：没有任何怪物段（截断在第一个段之前 / 文件只剩标量）---
        assertSuspect(YamlFiles.monstersConfigValidator(), "schema: 1\n",
                "顶层全是标量 = 没有怪物段");
        assertSuspect(YamlFiles.monstersConfigValidator(), "# 只剩注释\n",
                "空文档 = 解析后 0 个段");

        // --- 仍然可疑：段里全是未知键（写到一半 / 键名写坏）---
        assertSuspect(YamlFiles.monstersConfigValidator(), "beetle:\n  healht: 40\n",
                "唯一子键是拼错的未知键");

        // --- 回归用例：dc-004 的误杀形态。修复前这两条会判 SUSPECT 并隔离整份文件 ---
        // entity-type 是「生效」清单里唯一没有 GUI 编辑器的键，只改装备的文件完全合法。
        assertOk(YamlFiles.monstersConfigValidator(), "beetle:\n  main-hand: IRON_SWORD\n",
                "只写装备键（无 entity-type）是合法手写配置");
        assertOk(YamlFiles.monstersConfigValidator(),
                "beetle:\n  base-damage: 4.0\n  helmet: IRON_HELMET\n",
                "只写攻击与装备键也应放行");
        // 出厂文件里「不生效」的键同样能证明这是正常的怪物段
        assertOk(YamlFiles.monstersConfigValidator(), "beetle:\n  health: 40\n",
                "health 在文件头的可编辑清单里，不应被当成垃圾");

        // --- 放行：正常形态、多段、以及混合（一段未知键 + 一段可识别键）---
        assertOk(YamlFiles.monstersConfigValidator(), "beetle:\n  entity-type: CAVE_SPIDER\n");
        assertOk(YamlFiles.monstersConfigValidator(),
                "beetle:\n  entity-type: CAVE_SPIDER\nhunter:\n  main-hand: BOW\n",
                "多段各自命中已知键");
    }

    @Test
    @DisplayName("monsters 哨兵：出厂 monsters-config.yml 仍通过，且隔离路径对空文档仍生效")
    void monstersSentinelKeepsRealTruncationDetection(@TempDir Path dir) throws IOException {
        // 出厂文件（三段，全键）必须照旧通过 —— 证明放宽没有把正常文件也放倒
        assertFactoryOk(dir, "/monsters-config.yml", YamlFiles.monstersConfigValidator());

        // 真实截断：0 字节文件走 load() 的 raw.isBlank() 分支，与哨兵无关但路径必须仍生效
        File empty = dir.resolve("monsters-config.yml").toFile();
        Files.write(empty.toPath(), new byte[0]);
        YamlFiles.Loaded loaded = YamlFiles.load(empty, YamlFiles.monstersConfigValidator(), LOG);
        assertEquals(YamlFiles.Status.SUSPECT, loaded.status());
        assertTrue(dir.resolve("monsters-config.yml.corrupt").toFile().exists(), "空文件仍须被隔离");
    }

    @Test
    @DisplayName("messages 哨兵：en/zh 至少一个 ≥50 键；只有一种语言也放行")
    void messagesSentinel() {
        assertSuspect(YamlFiles.messagesValidator(), "en:\n  a: b\nzh:\n  c: d\n");
        StringBuilder onlyZh = new StringBuilder("zh:\n");
        for (int i = 0; i < 50; i++) {
            onlyZh.append("  k").append(i).append(": v\n");
        }
        assertOk(YamlFiles.messagesValidator(), onlyZh.toString(), "只有 zh 也应放行");
    }

    // ---------------------------------------------------------------- 写盘

    @Test
    @DisplayName("write() 后读回内容一致，目录里无 .tmp 残留")
    void writeRoundTripLeavesNoTempFile(@TempDir Path dir) throws IOException {
        File file = dir.resolve("game-settings.yml").toFile();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("min-players", 2);
        yaml.set("nodes.repair-item", "GOLDEN_PICKAXE");
        yaml.set("waves.1.mobs", List.of("explode-beetle", "hook-claw-hunter"));

        YamlFiles.write(file, yaml);

        assertTrue(file.exists());
        YamlConfiguration reread = parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        assertEquals(2, reread.getInt("min-players"));
        assertEquals("GOLDEN_PICKAXE", reread.getString("nodes.repair-item"));
        assertEquals(List.of("explode-beetle", "hook-claw-hunter"), reread.getStringList("waves.1.mobs"));
        assertTrue(tempFiles(dir).isEmpty(), "不应留下 .tmp：" + tempFiles(dir));
        try (Stream<Path> entries = Files.list(dir)) {
            assertEquals(1, entries.count(), "目录里只应有目标文件");
        }
    }

    @Test
    @DisplayName("write() 覆盖已有文件时保留未改动的注释（读→改→写 全链）")
    void writeAfterLoadKeepsComments(@TempDir Path dir) throws IOException {
        File file = dir.resolve("arenas.yml").toFile();
        Files.writeString(file.toPath(), readResource("/arenas.yml"), StandardCharsets.UTF_8);

        YamlFiles.Loaded loaded = YamlFiles.load(file, YamlFiles.arenasValidator(), LOG);
        loaded.config().set("arenas.arena1.game.min-players", 3);
        YamlFiles.write(file, loaded.config());

        String saved = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(commentLines(readResource("/arenas.yml")), commentLines(saved),
                "改一个值不应该吃掉任何整行注释");
        assertEquals(3, loaded.config().getInt("arenas.arena1.game.min-players"));
    }

    @Test
    @DisplayName("write() 自动创建缺失的父目录")
    void writeCreatesParentDirectory(@TempDir Path dir) throws IOException {
        File file = dir.resolve("nested/deeper/x.yml").toFile();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("a", 1);

        YamlFiles.write(file, yaml);

        assertTrue(file.exists());
    }

    @Test
    @DisplayName("写失败必须抛 IOException，不静默（目标位置被目录占据）")
    void writeFailurePropagates(@TempDir Path dir) throws IOException {
        // 跨平台可复现的失败：目标路径是个非空目录，move 必然抛 IOException。
        Path target = dir.resolve("blocked.yml");
        Files.createDirectories(target.resolve("child"));
        File file = target.toFile();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("a", 1);

        assertThrows(IOException.class, () -> YamlFiles.write(file, yaml));
        assertTrue(tempFiles(dir).isEmpty(), "失败也必须清掉 .tmp：" + tempFiles(dir));
    }

    // ------------------------------------------------ 写失败路径：分平台各测「本平台真实会失败」的形态
    //
    // 背景（round5-37）：旧用例是
    //     Assumptions.assumeFalse(isWindows(), "Windows 的 setWritable(false) 语义不可靠");
    //     file.setWritable(false);  assertThrows(IOException.class, () -> YamlFiles.write(file, yaml));
    // 守卫方向装反了喵 —— 它跳过的 Windows 恰恰是唯一会抛 AccessDeniedException 的平台，
    // 而它真跑的 Linux 上 YamlFiles.write 会静默成功，于是 assertThrows 必红喵。
    //
    // 根因在语义层，不在写错一行判断：YamlFiles.write 走「同目录 tmp + ATOMIC_MOVE」，
    // 而 POSIX 的 rename(2) 只校验【目录】的写权限，完全不看被覆盖文件的权限位 ——
    // 所以 0444 目标会被成功覆盖（真实 Linux JVM 非 root 实测 threw=false，目标内容被改掉）喵。
    // Windows 恰好相反：MoveFileEx 在目标带 FILE_ATTRIBUTE_READONLY 时返回 ERROR_ACCESS_DENIED，
    // 所以「目标只读」在 Windows 上是可靠的失败构造，在 POSIX 上根本不是喵。
    //
    // 因此不能用「一条用例 + 一条平台假设」去覆盖两套不同语义（任何单一守卫都必然在
    // 另一半平台上错），改为按平台各测各的【真实】失败形态：
    //   · POSIX  ：父目录 0555 → 同目录 tmp 创建被拒（不经过 rename 语义，POSIX 上恒成立）
    //   · Windows：目标文件只读 → ATOMIC_MOVE 被拒（MoveFileEx 的文档语义）
    // 两条用例互斥执行：任一平台上都是「一条真跑、一条带原因显式跳过」，skip 总数恒为 1喵。

    @Test
    @DisplayName("POSIX：父目录不可写 → tmp 建不出来 → 抛 IOException，原文件与目录现场不变")
    void writeIntoUnwritableDirectoryThrows(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(isPosix(),
                "非 POSIX 文件系统：没有权限位可设，本用例的失败构造不存在"
                        + "（Windows 侧由 writeToReadOnlyFileThrows 覆盖）");
        Path work = Files.createDirectories(dir.resolve("ro-dir"));
        File file = work.resolve("arenas.yml").toFile();
        Files.writeString(file.toPath(), REALISTIC_ARENA, StandardCharsets.UTF_8);

        // 只收回写位，保留 r-x：要造的是「目录内不能新建文件」，不是「目录不可读/不可进入」，
        // 后者会让 tempFiles() 的现场检查也失败，测不出想测的东西喵。
        Set<PosixFilePermission> originalPerms = Files.getPosixFilePermissions(work);
        Set<PosixFilePermission> readExecOnly = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(work, readExecOnly);
        try {
            // 前置校验：权限得真的生效才谈得上「测失败路径」。root（容器/WSL 默认用户）与
            // 某些挂载（ACL、FUSE、Windows 盘）会无视权限位，那种环境下断言 IOException
            // 只会得到一条与被测代码无关的假失败，因此如实跳过并写清原因喵。
            Assumptions.assumeFalse(Files.isWritable(work),
                    "当前用户或文件系统无视目录写权限（root / ACL 挂载），无法构造真实失败");

            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("arenas.arena1.game.min-players", 3);

            assertThrows(IOException.class, () -> YamlFiles.write(file, yaml),
                    "目录不可写时 tmp 创建必然失败，write() 必须抛 IOException 而不是静默丢数据");
            assertEquals(REALISTIC_ARENA, Files.readString(file.toPath(), StandardCharsets.UTF_8),
                    "写失败时目标文件必须保持原样（原子写的核心承诺）");
            assertTrue(tempFiles(work).isEmpty(), "失败也必须清掉 .tmp：" + tempFiles(work));
        } finally {
            // 不还原就删不掉目录内容，@TempDir 清理会连测试一起报 error喵。
            Files.setPosixFilePermissions(work, originalPerms);
        }
    }

    @Test
    @DisplayName("Windows：目标文件只读 → ATOMIC_MOVE 被拒 → 抛 IOException，原文件内容不变")
    void writeToReadOnlyFileThrows(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(isWindows(),
                "POSIX 的 rename(2) 不校验目标文件权限位，只读目标会被静默覆盖"
                        + "（POSIX 侧由 writeIntoUnwritableDirectoryThrows 覆盖）");
        File file = dir.resolve("arenas.yml").toFile();
        Files.writeString(file.toPath(), REALISTIC_ARENA, StandardCharsets.UTF_8);
        // 前置条件也用 assumption 而不是 assert：某些文件系统（FAT/exFAT 经 SMB 挂载等）
        // 的只读位不生效，那是环境问题而不是 YamlFiles 的缺陷，不该判红喵。
        Assumptions.assumeTrue(file.setWritable(false), "本机造不出只读文件");
        try {
            Assumptions.assumeFalse(file.canWrite(), "只读位未生效（非常规文件系统？），无法构造真实失败");

            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("arenas.arena1.game.min-players", 3);

            assertThrows(IOException.class, () -> YamlFiles.write(file, yaml),
                    "MoveFileEx 覆盖只读目标会返回 ERROR_ACCESS_DENIED，write() 必须把它抛出来");
            assertEquals(REALISTIC_ARENA, Files.readString(file.toPath(), StandardCharsets.UTF_8),
                    "写失败时目标文件必须保持原样（原子写的核心承诺）");
            assertTrue(tempFiles(dir).isEmpty(), "失败也必须清掉 .tmp：" + tempFiles(dir));
        } finally {
            assertTrue(file.setWritable(true), "还原只读位失败，@TempDir 可能清理不掉");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void assertFactoryOk(Path dir, String resource, java.util.function.Function<YamlConfiguration, String> validator)
            throws IOException {
        File file = dir.resolve(Path.of(resource).getFileName().toString()).toFile();
        Files.writeString(file.toPath(), readResource(resource), StandardCharsets.UTF_8);
        YamlFiles.Loaded loaded = YamlFiles.load(file, validator, LOG);
        assertEquals(YamlFiles.Status.OK, loaded.status(), resource + " 应通过哨兵：" + loaded.detail());
    }

    private static void assertSuspect(java.util.function.Function<YamlConfiguration, String> validator, String text) {
        assertSuspect(validator, text, "");
    }

    private static void assertSuspect(java.util.function.Function<YamlConfiguration, String> validator, String text,
                                      String why) {
        YamlConfiguration yaml = parse(text);
        assertNotNull(validator.apply(yaml), "应判可疑：" + why + " ← " + text.replace("\n", "\\n"));
    }

    private static void assertOk(java.util.function.Function<YamlConfiguration, String> validator, String text) {
        assertOk(validator, text, "");
    }

    private static void assertOk(java.util.function.Function<YamlConfiguration, String> validator, String text,
                                 String why) {
        YamlConfiguration yaml = parse(text);
        assertNull(validator.apply(yaml), "不应判可疑：" + why + " ← " + text.replace("\n", "\\n"));
    }

    private static YamlConfiguration parse(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException exception) {
            throw new AssertionError("测试输入本身应是合法 YAML：" + text.replace("\n", "\\n"), exception);
        }
        return yaml;
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = YamlFilesTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "资源不存在：" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> commentLines(String text) {
        return text.lines().filter(line -> line.stripLeading().startsWith("#"))
                .map(String::strip).toList();
    }

    private static List<String> inlineCommentTexts(String text) {
        return text.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .map(line -> {
                    int hash = line.indexOf('#');
                    return hash < 0 ? null : line.substring(hash).strip();
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static List<Path> tempFiles(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 是否支持 POSIX 权限位。
     *
     * <p>用「文件系统是否提供 {@code PosixFileAttributeView}」判定，而不是 {@code os.name}：
     * 真正决定 {@code setPosixFilePermissions} 能不能用的，是这条路径落在哪个文件系统上
     * （Linux/macOS 的本地盘 = 有；Windows 盘、某些网络挂载 = 没有），不是内核名字喵。</p>
     */
    private static boolean isPosix() {
        try {
            return Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir", ".")))
                    .supportsFileAttributeView(PosixFileAttributeView.class);
        } catch (IOException | RuntimeException unsupported) {
            return false;
        }
    }
}
