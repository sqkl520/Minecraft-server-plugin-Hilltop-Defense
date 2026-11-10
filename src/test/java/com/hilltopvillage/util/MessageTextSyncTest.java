package com.hilltopvillage.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MessageTextSync} 的行为测试。
 *
 * <p>测试的核心不是「升级能不能生效」，而是<b>「服主自定义绝不会被覆盖」</b>：
 * 每一条「该更新」的用例，目的都是证明系统确实有能力更新；每一条「不该更新」的用例，
 * 都是为了防止某次「优化」把保守分支改宽。</p>
 *
 * <p>用 {@code YamlConfiguration.loadFromString} 直接造配置，不需要 MockBukkit——
 * 该类本身不初始化 Bukkit 单例。</p>
 */
class MessageTextSyncTest {

    private static final String VERSION = "1.1.0-SNAPSHOT-fix2";

    /** 模拟 JAR：库里两个键，都是「新出厂文案」。 */
    private static YamlConfiguration jar(String enValue, String zhValue) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("en.game-start-need-players", enValue);
        config.set("zh.game-start-need-players", zhValue);
        return config;
    }

    private static YamlConfiguration disk(String raw) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(raw);
        } catch (org.bukkit.configuration.InvalidConfigurationException failure) {
            throw new AssertionError("test fixture is not valid YAML", failure);
        }
        return config;
    }

    private static String legacyOf(String text) {
        return MessageTextSync.hash(text);
    }

    /** 基线：某键的历史出厂值哈希。 */
    private static Map<String, Set<String>> legacy(String path, String... texts) {
        Set<String> hashes = java.util.Arrays.stream(texts).map(MessageTextSync::hash)
                .collect(Collectors.toSet());
        return Map.of(path, hashes);
    }

    private static MessageTextSync.Step stepOf(MessageTextSync.Result result, String path) {
        return result.steps().stream().filter(step -> step.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("no step recorded for " + path));
    }

    // ---------------------------------------------------------------- 不变式 1：可更新

    @Test
    @DisplayName("基线存在 + 磁盘值 == 基线哈希 → UPGRADE，磁盘变新文案，基线刷新")
    void baselineMatchesUpgrades() {
        String oldText = "&cOLD factory text";
        YamlConfiguration jar = jar("&cNEW factory text", "&c新出厂文案");
        YamlConfiguration disk = disk("_meta:\n"
                + "  keys:\n"
                + "    en.game-start-need-players: \"" + legacyOf(oldText) + "\"\n"
                + "en:\n"
                + "  game-start-need-players: \"" + oldText + "\"\n"
                + "zh:\n"
                + "  game-start-need-players: \"&c新出厂文案\"\n");

        MessageTextSync.Result result = MessageTextSync.sync(jar, disk, Map.of(), VERSION);

        assertEquals(MessageTextSync.Action.UPGRADE,
                stepOf(result, "en.game-start-need-players").action());
        assertEquals("&cNEW factory text", disk.getString("en.game-start-need-players"));
        assertEquals(1, result.upgraded());
        // 基线被刷新成新出厂值的哈希
        assertEquals(legacyOf("&cNEW factory text"),
                disk.getString("_meta.keys.en.game-start-need-players"));
        // 已一致的那个键只 KEEP，不算升级
        assertEquals(MessageTextSync.Action.KEEP, stepOf(result, "zh.game-start-need-players").action());
        assertTrue(result.changed());
    }

    @Test
    @DisplayName("无 _meta + 磁盘值命中历史基线 → UPGRADE（老文件跨版本升级的主路径）")
    void legacyBaselineUpgradesOldFile() {
        String fix2Text = "&c无法开始游戏：当前 {0}/{1} 名玩家。需要更多玩家加入（请改 arenas.yml）";
        YamlConfiguration jar = jar("&cNEW en", "&c新文案");
        YamlConfiguration disk = disk("en:\n"
                + "  game-start-need-players: \"&cOLD en\"\n"
                + "zh:\n"
                + "  game-start-need-players: \"" + fix2Text + "\"\n");

        Map<String, Set<String>> legacyHashes = legacy("zh.game-start-need-players", fix2Text, "更早的文案");
        MessageTextSync.Result result = MessageTextSync.sync(jar, disk, legacyHashes, VERSION);

        assertEquals(MessageTextSync.Action.UPGRADE,
                stepOf(result, "zh.game-start-need-players").action());
        assertEquals("&c新文案", disk.getString("zh.game-start-need-players"));
        // 历史基线命中后，也要把「当前出厂值」的哈希写进 _meta，下次走基线分支
        assertEquals(legacyOf("&c新文案"), disk.getString("_meta.keys.zh.game-start-need-players"));
        // en 侧没有基线也不在 legacy 表里 → 保守
        assertEquals(MessageTextSync.Action.MARK_CUSTOM,
                stepOf(result, "en.game-start-need-players").action());
        assertEquals("&cOLD en", disk.getString("en.game-start-need-players"));
    }

    @Test
    @DisplayName("更新后 _meta.keys 的哈希被刷新（第二次同步零改动）")
    void baselineRefreshedAfterUpgrade() {
        String oldText = "&cold";
        YamlConfiguration jar = jar("&cnew", "&c新");
        YamlConfiguration disk = disk("_meta:\n"
                + "  keys:\n"
                + "    en.game-start-need-players: \"" + legacyOf(oldText) + "\"\n"
                + "en:\n"
                + "  game-start-need-players: \"" + oldText + "\"\n"
                + "zh:\n"
                + "  game-start-need-players: \"&c新\"\n");

        MessageTextSync.Result first = MessageTextSync.sync(jar, disk, Map.of(), VERSION);
        assertTrue(first.changed());
        assertEquals(legacyOf("&cnew"), disk.getString("_meta.keys.en.game-start-need-players"));

        // 第二轮：磁盘已是新值，应一行都不改（稳态零写入）
        MessageTextSync.Result second = MessageTextSync.sync(jar, disk, Map.of(), VERSION);
        assertFalse(second.changed(), "稳态下不应产生任何改动");
        assertEquals(0, second.upgraded());
        assertEquals(0, second.added());
        assertEquals(0, second.keptCustom());
    }

    // ---------------------------------------------------------------- 不变式 2：绝不覆盖

    @Test
    @DisplayName("磁盘值被改过 + 有基线 → MARK_CUSTOM，值不变，写进 _meta.custom")
    void customizedValueIsKept() {
        YamlConfiguration jar = jar("&cNEW factory text", "&c新出厂文案");
        YamlConfiguration disk = disk("_meta:\n"
                + "  keys:\n"
                + "    en.game-start-need-players: \"" + legacyOf("&cOLD factory text") + "\"\n"
                + "en:\n"
                + "  game-start-need-players: \"&d我的自定义文案\"\n"
                + "zh:\n"
                + "  game-start-need-players: \"&c新出厂文案\"\n");

        MessageTextSync.Result result = MessageTextSync.sync(jar, disk, Map.of(), VERSION);

        assertEquals(MessageTextSync.Action.MARK_CUSTOM,
                stepOf(result, "en.game-start-need-players").action());
        assertEquals("&d我的自定义文案", disk.getString("en.game-start-need-players"),
                "自定义值绝不能被覆盖");
        assertEquals(List.of("en.game-start-need-players"), disk.getStringList("_meta.custom"));
        assertEquals(1, result.keptCustom());
        assertEquals(0, result.upgraded());
    }

    @Test
    @DisplayName("已在 _meta.custom 的键：即使值恰好等于某个历史出厂值也不动（黑名单优先）")
    void customListIsAuthoritative() {
        String oldText = "&cOLD factory text";
        YamlConfiguration jar = jar("&cNEW", "&c新");
        YamlConfiguration disk = disk("_meta:\n"
                + "  keys:\n"
                + "    en.game-start-need-players: \"" + legacyOf("&cSomethingElse") + "\"\n"
                + "  custom:\n"
                + "    - en.game-start-need-players\n"
                + "en:\n"
                + "  game-start-need-players: \"" + oldText + "\"\n"
                + "zh:\n"
                + "  game-start-need-players: \"&c新\"\n");

        MessageTextSync.Result result = MessageTextSync.sync(jar, disk,
                legacy("en.game-start-need-players", oldText), VERSION);

        assertEquals(MessageTextSync.Action.MARK_CUSTOM,
                stepOf(result, "en.game-start-need-players").action());
        assertEquals(oldText, disk.getString("en.game-start-need-players"));
        assertEquals(0, result.upgraded());
    }

    @Test
    @DisplayName("自定义键在后续版本仍不被覆盖（幂等：连续两轮都保留）")
    void customSurvivesNextVersion() {
        YamlConfiguration jarV1 = jar("&cv1", "&c新1");
        YamlConfiguration disk = disk("en:\n  game-start-need-players: \"&d我的文案\"\nzh:\n  game-start-need-players: \"&c新1\"\n");

        MessageTextSync.sync(jarV1, disk, Map.of(), VERSION);          // 第一轮：进入 custom
        assertEquals(List.of("en.game-start-need-players"), disk.getStringList("_meta.custom"));

        // 下一版：出厂文案又变了
        YamlConfiguration jarV2 = jar("&cv2", "&c新2");
        MessageTextSync.Result second = MessageTextSync.sync(jarV2, disk, Map.of(), "next-version");

        assertEquals("&d我的文案", disk.getString("en.game-start-need-players"),
                "跨版本仍然是自定义值");
        assertEquals(MessageTextSync.Action.MARK_CUSTOM,
                stepOf(second, "en.game-start-need-players").action());
        // zh 侧没有自定义过 → 正常升级
        assertEquals("&c新2", disk.getString("zh.game-start-need-players"));
    }

    @Test
    @DisplayName("自定义键改回当前出厂值 → 从 custom 自动移除")
    void returningToFactoryClearsCustom() {
        YamlConfiguration jar = jar("&cNEW", "&c新");
        YamlConfiguration disk = disk("_meta:\n"
                + "  custom:\n"
                + "    - en.game-start-need-players\n"
                + "en:\n"
                + "  game-start-need-players: \"&cNEW\"\n"
                + "zh:\n"
                + "  game-start-need-players: \"&c新\"\n");

        MessageTextSync.Result result = MessageTextSync.sync(jar, disk, Map.of(), VERSION);

        assertEquals(MessageTextSync.Action.KEEP, stepOf(result, "en.game-start-need-players").action());
        assertFalse(disk.getStringList("_meta.custom").contains("en.game-start-need-players"),
                "值已回到出厂值时应解除黑名单");
        assertEquals(legacyOf("&cNEW"), disk.getString("_meta.keys.en.game-start-need-players"));
    }

    // ---------------------------------------------------------------- 不变式 3：基线缺失 → 全保守

    @Test
    @DisplayName("_meta 完全缺失 + 无 legacy 基线 → 全保守（一个键都不改）")
    void missingMetaAndLegacyIsFullyConservative() {
        YamlConfiguration jar = jar("&cNEW en", "&c新文案");
        YamlConfiguration disk = disk("en:\n"
                + "  game-start-need-players: \"&cOLD en\"\n"
                + "zh:\n"
                + "  game-start-need-players: \"&c旧文案\"\n");

        MessageTextSync.Result result = MessageTextSync.sync(jar, disk, Map.of(), VERSION);

        assertEquals("&cOLD en", disk.getString("en.game-start-need-players"));
        assertEquals("&c旧文案", disk.getString("zh.game-start-need-players"));
        assertEquals(0, result.upgraded());
        assertEquals(2, result.keptCustom());
        assertEquals(2, disk.getStringList("_meta.custom").size(),
                "保守判定要把两个键都记进 custom，以便服主审计");
    }

    @Test
    @DisplayName("_meta 是列表（结构损坏）→ 不抛异常、零改动、标记 baselineCorrupt")
    void corruptMetaIsConservative() {
        YamlConfiguration jar = jar("&cNEW", "&c新");
        YamlConfiguration disk = disk("_meta:\n"
                + "  - 1\n"
                + "  - 2\n"
                + "en:\n"
                + "  game-start-need-players: \"&cOLD\"\n"
                + "zh:\n"
                + "  game-start-need-players: \"&c旧\"\n");

        MessageTextSync.Result result = MessageTextSync.sync(jar, disk, Map.of(), VERSION);

        assertTrue(result.baselineCorrupt());
        assertFalse(result.changed());
        assertEquals("&cOLD", disk.getString("en.game-start-need-players"));
        assertEquals("&c旧", disk.getString("zh.game-start-need-players"));
        // 结构损坏时绝不重写 _meta（写它等于丢弃读不懂的自定义标记）
        assertTrue(disk.isList("_meta"), "_meta 应保持服主/工具写入的原状");
    }

    @Test
    @DisplayName("_meta.keys 里某键缺失（其余键有基线）→ 仅该键保守，其它键照常升级")
    void missingSingleBaselineIsConservativeForThatKeyOnly() {
        YamlConfiguration jar = new YamlConfiguration();
        jar.set("en.a", "&cNEW-a");
        jar.set("en.b", "&cNEW-b");
        YamlConfiguration disk = disk("_meta:\n"
                + "  keys:\n"
                + "    en.b: \"" + legacyOf("&cOLD-b") + "\"\n"
                + "en:\n"
                + "  a: \"&cOLD-a\"\n"
                + "  b: \"&cOLD-b\"\n");

        MessageTextSync.Result result = MessageTextSync.sync(jar, disk, Map.of(), VERSION);

        assertEquals(MessageTextSync.Action.MARK_CUSTOM, stepOf(result, "en.a").action());
        assertEquals("&cOLD-a", disk.getString("en.a"), "无基线的键必须保守");
        assertEquals(MessageTextSync.Action.UPGRADE, stepOf(result, "en.b").action());
        assertEquals("&cNEW-b", disk.getString("en.b"));
        assertEquals(List.of("en.a"), disk.getStringList("_meta.custom"));
    }

    @Test
    @DisplayName("legacy 基线为空表（资源缺失）→ 等价于「全部保守」")
    void emptyLegacyMapIsConservative() {
        YamlConfiguration jar = jar("&cNEW", "&c新");
        YamlConfiguration disk = disk("en:\n  game-start-need-players: \"&cOLD\"\nzh:\n  game-start-need-players: \"&c旧\"\n");

        MessageTextSync.Result result = MessageTextSync.sync(jar, disk, null, VERSION);

        assertEquals(0, result.upgraded());
        assertEquals("&cOLD", disk.getString("en.game-start-need-players"));
        assertEquals("&c旧", disk.getString("zh.game-start-need-players"));
    }

    // ---------------------------------------------------------------- 其它分支

    @Test
    @DisplayName("磁盘缺键 → ADD（纯增量，不会破坏任何东西）")
    void missingKeyIsAdded() {
        YamlConfiguration jar = new YamlConfiguration();
        jar.set("en.brand-new", "&ahello");
        YamlConfiguration disk = disk("en:\n  existing: \"&fkeep\"\n");

        MessageTextSync.Result result = MessageTextSync.sync(jar, disk, Map.of(), VERSION);

        assertEquals(MessageTextSync.Action.ADD, stepOf(result, "en.brand-new").action());
        assertEquals("&ahello", disk.getString("en.brand-new"));
        assertEquals("&fkeep", disk.getString("en.existing"), "无关的键不动");
        assertEquals(1, result.added());
        assertEquals(legacyOf("&ahello"), disk.getString("_meta.keys.en.brand-new"));
    }

    @Test
    @DisplayName("JAR 已删的键 → KEEP，磁盘保留（可能正是服主自己的键）")
    void removedKeyIsKept() {
        YamlConfiguration jar = jar("&cNEW", "&c新");
        YamlConfiguration disk = disk("en:\n"
                + "  game-start-need-players: \"&cNEW\"\n"
                + "  server-only-key: \"&7custom\"\n"
                + "zh:\n"
                + "  game-start-need-players: \"&c新\"\n");

        MessageTextSync.Result result = MessageTextSync.sync(jar, disk, Map.of(), VERSION);

        assertEquals(MessageTextSync.Action.KEEP, stepOf(result, "en.server-only-key").action());
        assertEquals("&7custom", disk.getString("en.server-only-key"));
        assertNull(stepOf(result, "en.server-only-key").to());
    }

    @Test
    @DisplayName("磁盘只有一种语言 → 另一种全部 ADD，不互相影响")
    void singleLocaleDiskIsFilledIn() {
        YamlConfiguration jar = jar("&cNEW", "&c新");
        YamlConfiguration disk = disk("zh:\n  game-start-need-players: \"&c旧\"\n");

        MessageTextSync.Result result = MessageTextSync.sync(jar, disk,
                legacy("zh.game-start-need-players", "&c旧"), VERSION);

        assertEquals(MessageTextSync.Action.ADD, stepOf(result, "en.game-start-need-players").action());
        assertEquals("&cNEW", disk.getString("en.game-start-need-players"));
        assertEquals(MessageTextSync.Action.UPGRADE,
                stepOf(result, "zh.game-start-need-players").action());
        assertEquals("&c新", disk.getString("zh.game-start-need-players"));
    }

    @Test
    @DisplayName("非字符串值（被写成段）→ 不触碰，记为自定义")
    void nonStringValueIsNotTouched() {
        YamlConfiguration jar = jar("&cNEW", "&c新");
        YamlConfiguration disk = disk("en:\n"
                + "  game-start-need-players:\n"
                + "    nested: \"weird\"\n"
                + "zh:\n"
                + "  game-start-need-players: \"&c新\"\n");

        MessageTextSync.Result result = MessageTextSync.sync(jar, disk, Map.of(), VERSION);

        assertTrue(disk.isConfigurationSection("en.game-start-need-players"),
                "被写成段的值必须保持原状");
        assertEquals(MessageTextSync.Action.MARK_CUSTOM,
                stepOf(result, "en.game-start-need-players").action());
    }

    @Test
    @DisplayName("_meta.custom 列表会被排序写回，便于 diff 与人工审计")
    void customListIsSorted() {
        YamlConfiguration jar = new YamlConfiguration();
        jar.set("en.zzz", "&cnew-z");
        jar.set("en.aaa", "&cnew-a");
        YamlConfiguration disk = disk("en:\n"
                + "  zzz: \"&dx\"\n"
                + "  aaa: \"&dy\"\n");

        MessageTextSync.sync(jar, disk, Map.of(), VERSION);

        assertEquals(List.of("en.aaa", "en.zzz"), disk.getStringList("_meta.custom"));
    }

    @Test
    @DisplayName("factory-version 被写进 _meta，且只在变化时算改动")
    void factoryVersionWrittenOnce() {
        YamlConfiguration jar = jar("&cNEW", "&c新");
        YamlConfiguration disk = disk("en:\n  game-start-need-players: \"&cNEW\"\nzh:\n  game-start-need-players: \"&c新\"\n");

        MessageTextSync.Result first = MessageTextSync.sync(jar, disk, Map.of(), VERSION);
        assertEquals(VERSION, disk.getString("_meta.factory-version"));
        assertTrue(first.changed());

        MessageTextSync.Result second = MessageTextSync.sync(jar, disk, Map.of(), VERSION);
        assertFalse(second.changed(), "版本没变时不应再算改动");
    }

    // ---------------------------------------------------------------- 基线解析

    @Test
    @DisplayName("parseLegacy 能读出扁平点号键（不能被 Bukkit 的点号路径拆掉）")
    void parseLegacyHandlesDottedKeys() {
        String raw = "en.deploy.cmd-setcore:\n"
                + "  - \"aaaaaaaaaaaaaaaa\"\n"
                + "zh.deploy.cmd-setcore:\n"
                + "  - \"bbbbbbbbbbbbbbbb\"\n"
                + "  - \"cccccccccccccccc\"\n";

        Map<String, Set<String>> parsed = MessageTextSync.parseLegacy(raw);

        assertEquals(Set.of("aaaaaaaaaaaaaaaa"), parsed.get("en.deploy.cmd-setcore"));
        assertEquals(Set.of("bbbbbbbbbbbbbbbb", "cccccccccccccccc"),
                parsed.get("zh.deploy.cmd-setcore"));
    }

    @Test
    @DisplayName("parseLegacy 对坏文本返回空表（fail-safe）")
    void parseLegacyBrokenReturnsEmpty() {
        assertTrue(MessageTextSync.parseLegacy("en: [unclosed").isEmpty());
        assertTrue(MessageTextSync.parseLegacy("").isEmpty());
        assertTrue(MessageTextSync.parseLegacy(null).isEmpty());
    }

    @Test
    @DisplayName("hash 与出厂的十六进制口径一致（SHA-256 前 16 位小写）")
    void hashShape() {
        String hash = MessageTextSync.hash("abc");
        assertEquals(16, hash.length());
        assertTrue(hash.matches("[0-9a-f]{16}"));
        assertEquals("ba7816bf8f01cfea", hash, "SHA-256(\"abc\") 的前 16 位");
    }

    @Test
    @DisplayName("含 \\n 转义的值：哈希的是解析后的字符串（与 JAR 侧同源），仍能正确升级")
    void escapedValueHashesParsedText() {
        String oldText = "old\nvalue";                 // 磁盘里写作 "old\nvalue"（转义字面量）
        String newText = "line1\nline2";               // JAR 里的多行新文案
        YamlConfiguration jar = new YamlConfiguration();
        jar.set("en.multi", newText);
        YamlConfiguration disk = disk("_meta:\n"
                + "  keys:\n"
                + "    en.multi: \"" + legacyOf(oldText) + "\"\n"
                + "en:\n"
                + "  multi: \"old\\nvalue\"\n");

        assertEquals(oldText, disk.getString("en.multi"), "转义必须被解析成真实换行");

        MessageTextSync.Result result = MessageTextSync.sync(jar, disk, Map.of(), VERSION);

        assertEquals(MessageTextSync.Action.UPGRADE, stepOf(result, "en.multi").action());
        assertEquals(newText, disk.getString("en.multi"));
    }

    @Test
    @DisplayName("_meta.keys 写成嵌套段而非扁平点号键（扁平键 Bukkit 读不出来）")
    void metaKeysAreNestedAndReadable() {
        YamlConfiguration jar = jar("&cNEW", "&c新");
        YamlConfiguration disk = disk("en:\n  game-start-need-players: \"&cNEW\"\nzh:\n  game-start-need-players: \"&c新\"\n");

        MessageTextSync.sync(jar, disk, Map.of(), VERSION);

        // 用 Bukkit 的路径查找读得出来（扁平字面量键在这里会返回 null）
        assertEquals(legacyOf("&cNEW"),
                disk.getString("_meta.keys.en.game-start-need-players"),
                "_meta.keys 必须能被路径查找读到");
        // 落盘再读回也要能读到（saveToString 后重新解析）
        YamlConfiguration reloaded = new YamlConfiguration();
        try {
            reloaded.loadFromString(disk.saveToString());
        } catch (org.bukkit.configuration.InvalidConfigurationException failure) {
            throw new AssertionError(failure);
        }
        assertEquals(legacyOf("&cNEW"), reloaded.getString("_meta.keys.en.game-start-need-players"));
    }
}
