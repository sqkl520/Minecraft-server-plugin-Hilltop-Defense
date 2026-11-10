package com.hilltopvillage.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * {@code messages.yml} 出厂文案同步（PLAN D4）。
 *
 * <p><b>要解决的问题：</b>旧的合并策略是「磁盘优先 + 只补缺失键」，于是所有措辞改进对老服永久失效
 * ——磁盘里那行旧出厂值永远赢过 JAR 里的新值。但直接把策略改成「JAR 总是覆盖」会回滚服主的翻译，
 * 同样不可接受。二者之间只有一种可判定的判据：<b>这行字节是不是我们发过的</b>。</p>
 *
 * <p><b>核心不变式（三条，按优先级）：</b></p>
 * <ol>
 *   <li><b>磁盘值等于当前出厂值，或等于该键任一历史出厂值 ⇒ 认定为「未自定义」⇒ 可以更新。</b></li>
 *   <li><b>其它一切情况 ⇒ 认定为「已自定义」⇒ 永不覆盖</b>，并写入 {@code _meta.custom}。</li>
 *   <li><b>基线缺失 / 损坏 ⇒ 退化为「全部视为自定义」</b>（fail-safe：一个键都不改）。</li>
 * </ol>
 *
 * <p><b>为什么是纯函数类：</b>不碰 Bukkit 单例、不读文件、不打日志。输入是两份
 * {@link YamlConfiguration} + 一份历史基线，输出是「做了什么」的清单，因此可以只依赖
 * {@code YamlConfiguration.loadFromString} 直接单测。</p>
 *
 * <p><b>「历史出厂值」从哪来：</b>{@code messages-legacy.yml}（内嵌资源，由
 * {@code .work/fix-critical/gen-messages-legacy.mjs} 从全部 git tag 生成）。老服第一次升级时
 * 还没有 {@code _meta}，只能靠这份基线认出「磁盘上的旧文案是我们发的」。</p>
 *
 * <p><b>写入职责：</b>本类<b>就地修改</b>传入的 {@code disk} 配置；是否落盘由调用方决定。
 * {@link Result#changed()} 为 false 时必须零写入，否则 {@code synced-at} 会让文件 mtime 每次启动都变。</p>
 */
public final class MessageTextSync {

    /** 本插件支持的语言段。缓存构建同样只遍历这两个，{@code _meta} 天然被忽略。 */
    private static final String[] LOCALES = {"en", "zh"};

    /** 哈希长度：SHA-256 十六进制的前 16 位（64 bit，对「认出自己的文案」这一用途足够）。 */
    private static final int HASH_HEX_LENGTH = 16;

    private MessageTextSync() {
    }

    /** 对单个键采取的动作，供调用方记账与播报。 */
    public enum Action {
        /** 什么都不做（已一致 / JAR 已无此键 / 结构节点） */
        KEEP,
        /** 磁盘值仍是出厂文案 → 已替换为新出厂文案 */
        UPGRADE,
        /** 磁盘缺此键 → 已补入新出厂文案 */
        ADD,
        /** 判定为服主自定义 → 未改动 */
        MARK_CUSTOM
    }

    /**
     * 单个键的处理记录。
     *
     * @param path   绝对路径（{@code locale.key}）
     * @param action 动作
     * @param from   改动前的磁盘值（缺失为 null）
     * @param to     改动后的目标值（JAR 侧无此键时为 null）
     */
    public record Step(String path, Action action, String from, String to) {
    }

    /**
     * 同步结果。
     *
     * @param steps          逐键记录（按 locale、键名排序，便于日志稳定）
     * @param upgraded       被升级的键数
     * @param added          被补入的键数
     * @param keptCustom     被判为自定义而保留的键数
     * @param changed        是否有任何改动（为 false 时调用方必须零写入，保证稳态零磁盘改动）
     * @param baselineCorrupt {@code _meta} 结构损坏 → 本次全保守，一个键都没碰
     */
    public record Result(List<Step> steps, int upgraded, int added, int keptCustom,
                         boolean changed, boolean baselineCorrupt) {
    }

    /**
     * 执行同步。
     *
     * @param jar            JAR 内出厂配置（权威新值，只读）
     * @param disk           磁盘配置（<b>会被就地修改</b>：set 新值 / 写 {@code _meta}）
     * @param legacy         历史出厂值哈希：{@code locale.key -> 该键历史上所有出厂值的哈希}
     * @param factoryVersion 当前插件版本（写进 {@code _meta.factory-version}；null 时保留旧值）
     * @return 同步结果
     */
    public static Result sync(YamlConfiguration jar, YamlConfiguration disk,
                              Map<String, Set<String>> legacy, String factoryVersion) {
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(disk, "disk");
        Map<String, Set<String>> knownHashes = legacy == null ? Map.of() : legacy;
        List<Step> steps = new ArrayList<>();
        int upgraded = 0;
        int added = 0;
        int keptCustom = 0;
        boolean changed = false;

        // ---- 0. 读基线。_meta 存在但类型不对 = 被工具写坏 ⇒ 不变式 3：全保守 ----
        Object rawMeta = disk.get("_meta");
        ConfigurationSection meta = rawMeta instanceof ConfigurationSection section ? section : null;
        boolean baselineCorrupt = rawMeta != null && meta == null;
        ConfigurationSection keyHashes = null;
        List<String> customBefore = new ArrayList<>();
        if (meta != null) {
            Object rawKeys = meta.get("keys");
            if (rawKeys instanceof ConfigurationSection keys) {
                keyHashes = keys;
            } else if (rawKeys != null) {
                baselineCorrupt = true;
            }
            Object rawCustom = meta.get("custom");
            if (rawCustom instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof String text) {
                        customBefore.add(text);
                    } else {
                        baselineCorrupt = true;
                        break;
                    }
                }
            } else if (rawCustom != null) {
                baselineCorrupt = true;
            }
        }
        if (baselineCorrupt) {
            // 结构已不可信：只记录「全部原样保留」的清单，绝不写 _meta
            //（写它等于丢弃我们读不懂的那些自定义标记）
            for (String locale : LOCALES) {
                for (String key : collectPaths(jar, disk, locale)) {
                    String path = locale + "." + key;
                    steps.add(new Step(path, Action.KEEP, diskText(disk, path), jarText(jar, path)));
                }
            }
            return new Result(steps, 0, 0, 0, false, true);
        }

        Set<String> custom = new LinkedHashSet<>(customBefore);

        // ---- 1/2. 逐 (locale, key) 判定 ----
        for (String locale : LOCALES) {
            for (String key : collectPaths(jar, disk, locale)) {
                String path = locale + "." + key;
                String jarText = jarText(jar, path);

                // 2a. JAR 已无此键（旧版遗留 / 服主自己加的键）：不删、不改
                if (jarText == null) {
                    steps.add(new Step(path, Action.KEEP, diskText(disk, path), null));
                    continue;
                }

                // 2b. 磁盘缺此键：补入（与旧行为一致；纯增量，不可能破坏任何东西）
                if (!disk.contains(path)) {
                    if (putText(disk, path, jarText)) {
                        changed = true;
                        keyHashesSet(disk, path, hash(jarText));
                        custom.remove(path);
                        steps.add(new Step(path, Action.ADD, null, jarText));
                        added++;
                    } else {
                        steps.add(new Step(path, Action.KEEP, null, jarText));
                    }
                    continue;
                }

                String diskText = diskText(disk, path);
                // 2c. 键存在但不是字符串（被写成了段/列表）：结构由服主掌控，绝不触碰
                if (diskText == null) {
                    if (custom.add(path)) {
                        changed = true;
                    }
                    steps.add(new Step(path, Action.MARK_CUSTOM, null, jarText));
                    keptCustom++;
                    continue;
                }

                // 2d. 与当前出厂值完全一致：刷新基线，并解除 custom（它已回到出厂值）
                if (diskText.equals(jarText)) {
                    if (keyHashesSet(disk, path, hash(jarText))) {
                        changed = true;
                    }
                    if (custom.remove(path)) {
                        changed = true;
                    }
                    steps.add(new Step(path, Action.KEEP, diskText, jarText));
                    continue;
                }

                // 2e. 不一致 —— 依次判定。任何一条不命中「可更新」都落到 MARK_CUSTOM。

                // ① 已在 custom 名单：权威黑名单，绝对不动
                if (custom.contains(path)) {
                    steps.add(new Step(path, Action.MARK_CUSTOM, diskText, jarText));
                    keptCustom++;
                    continue;
                }

                // ② 有基线且磁盘仍是「上次同步的那个出厂值」→ 未自定义 → 升级
                String baseline = keyHashes == null ? null : keyHashes.getString(path);
                if (baseline != null && baseline.equals(hash(diskText))) {
                    if (putText(disk, path, jarText)) {
                        changed = true;
                        keyHashesSet(disk, path, hash(jarText));
                        steps.add(new Step(path, Action.UPGRADE, diskText, jarText));
                        upgraded++;
                    } else {
                        steps.add(new Step(path, Action.KEEP, diskText, jarText));
                    }
                    continue;
                }

                // ③ 有基线但磁盘已变 → 上次同步之后被改过 → 保留
                if (baseline != null) {
                    if (custom.add(path)) {
                        changed = true;
                    }
                    steps.add(new Step(path, Action.MARK_CUSTOM, diskText, jarText));
                    keptCustom++;
                    continue;
                }

                // ④ 无基线（老文件 / 服主新增的键）：
                //    磁盘值命中「任一历史出厂值」→ 是我们的旧文案，可升级；
                //    否则 → 视为自定义（最保守分支，绝不覆盖）。
                //    基线文件缺失时 legacy 为空表 ⇒ 恒走 else，即不变式 3 的「全部保守」。
                Set<String> known = knownHashes.getOrDefault(path, Set.of());
                if (known.contains(hash(diskText))) {
                    if (putText(disk, path, jarText)) {
                        changed = true;
                        keyHashesSet(disk, path, hash(jarText));
                        steps.add(new Step(path, Action.UPGRADE, diskText, jarText));
                        upgraded++;
                    } else {
                        steps.add(new Step(path, Action.KEEP, diskText, jarText));
                    }
                } else {
                    if (custom.add(path)) {
                        changed = true;
                    }
                    steps.add(new Step(path, Action.MARK_CUSTOM, diskText, jarText));
                    keptCustom++;
                }
            }
        }

        // ---- 3. 回写 _meta ----
        if (factoryVersion != null && !factoryVersion.equals(meta == null ? null : meta.getString("factory-version"))) {
            disk.set("_meta.factory-version", factoryVersion);
            changed = true;
        }
        List<String> customAfter = new ArrayList<>(custom);
        Collections.sort(customAfter);
        List<String> customNow = meta == null ? List.of() : meta.getStringList("custom");
        if (!customAfter.equals(customNow)) {
            disk.set("_meta.custom", customAfter);
            changed = true;
        }
        // synced-at 只在真有变更时刷新：否则每次启动都 mtime 变化，备份工具会以为文件天天在改
        if (changed) {
            disk.set("_meta.synced-at", System.currentTimeMillis());
        }
        return new Result(steps, upgraded, added, keptCustom, changed, false);
    }

    /**
     * 解析 {@code messages-legacy.yml} 文本。
     *
     * <p>文件形态是<b>顶层扁平键</b>（{@code en.game-start-need-players: [hash, ...]}）。这里刻意用
     * 非点号分隔符装载：Bukkit 默认把 {@code .} 当路径分隔符，扁平键会被拆成段树，一旦某个键是另一个键的
     * 前缀就会互相覆盖。改用 {@code '\0'} 后每个键都是字面量，不存在任何碰撞。</p>
     *
     * <p>任何异常都返回空表（不变式 3：退化为全部保守），绝不把坏基线当成别的含义。</p>
     */
    public static Map<String, Set<String>> parseLegacy(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.options().pathSeparator('\0');
        try {
            legacy.loadFromString(raw);
        } catch (Exception broken) {
            return Map.of();
        }
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String path : legacy.getKeys(false)) {
            if (!legacy.isList(path)) {
                continue;
            }
            List<String> hashes = legacy.getStringList(path);
            if (!hashes.isEmpty()) {
                out.computeIfAbsent(path, ignored -> new LinkedHashSet<>()).addAll(hashes);
            }
        }
        return out;
    }

    /**
     * SHA-256 前 16 位十六进制。
     *
     * <p>哈希的是<b>原始 YAML 字符串</b>（未经颜色码编译）。JAR 侧、磁盘侧、生成脚本三者同源。</p>
     */
    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(HASH_HEX_LENGTH);
            for (int i = 0; i < HASH_HEX_LENGTH / 2; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // 每个 JRE 都必须提供 SHA-256；真没有说明运行环境坏了，不能悄悄当成「无基线」
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    // ---- 内部 ----

    private static String jarText(YamlConfiguration jar, String path) {
        return jar.isString(path) ? jar.getString(path) : null;
    }

    private static String diskText(YamlConfiguration disk, String path) {
        return disk.isString(path) ? disk.getString(path) : null;
    }

    /**
     * 写一个文案值。
     *
     * <p>兜住极端结构异常（同一 locale 下同时存在 {@code a} 与 {@code a.b} 两个键时 YAML 无法同时表示
     * 「字符串的 a」与「作为段的 a」）。此时<b>放弃写入</b>（保持旧值），绝不写坏文件。
     * 当前出厂文件与全部历史 tag 均无此结构，这里是纯防御。</p>
     *
     * @return true = 已写入内存配置
     */
    private static boolean putText(YamlConfiguration disk, String path, String text) {
        try {
            disk.set(path, text);
            return true;
        } catch (RuntimeException structureClash) {
            return false;
        }
    }

    /**
     * 记录某键「当前出厂值」的哈希。
     *
     * <p><b>存储形态：</b>{@code _meta.keys.<locale>.<key>} 是<b>嵌套段</b>而不是扁平点号键。原因是
     * Bukkit 的 {@code getString("en.deploy.x")} 按 {@code .} 切分查找，扁平字面量键读不出来；
     * 嵌套形态在「内存里 set → saveToString → 重新 loadFromString」全链路都能用同一路径读写。</p>
     *
     * @return 是否真的改动了内存配置（用于稳态零写入）
     */
    private static boolean keyHashesSet(YamlConfiguration disk, String path, String hashValue) {
        String metaPath = "_meta.keys." + path;
        if (disk.isString(metaPath) && hashValue.equals(disk.getString(metaPath))) {
            return false;
        }
        try {
            disk.set(metaPath, hashValue);
            return true;
        } catch (RuntimeException structureClash) {
            // 键 a 与 a.b 并存时无法建基线：本次升级照常生效，该键下一轮会走 legacy/保守分支
            return false;
        }
    }

    private static Set<String> collectPaths(YamlConfiguration jar, YamlConfiguration disk, String locale) {
        Set<String> paths = new TreeSet<>();
        collectPaths(jar, locale, paths);
        collectPaths(disk, locale, paths);
        return paths;
    }

    private static void collectPaths(YamlConfiguration config, String locale, Set<String> out) {
        ConfigurationSection section = config.getConfigurationSection(locale);
        if (section == null) {
            return;
        }
        out.addAll(section.getKeys(true));
    }
}
