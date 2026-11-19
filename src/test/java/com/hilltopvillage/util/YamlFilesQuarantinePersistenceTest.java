package com.hilltopvillage.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隔离态跨重启的持续性。
 *
 * <p><b>这一族缺陷的共同形态</b>：{@link YamlFileGate} 的 {@code readOnly} 是实例级
 * volatile，<b>每次启动新建一个</b>；而 {@link YamlFiles#quarantine} 把原文件
 * {@code Files.move} 成了 {@code *.corrupt}，原路径真空了。</p>
 *
 * <p>于是下一次启动时：{@code !file.exists()} 为真 → 调用方 {@code saveResource}
 * 写出出厂模板 → 新 gate 读到一个合法的新文件 → 返回 OK → 闩被清掉。
 * <b>那层「本次运行只读」的保护只活一次运行</b>，管理员手工调的配置在下一次重启时
 * 被出厂值覆盖，只剩一个陈旧的 {@code .corrupt} 作证据。</p>
 *
 * <p>所以本类每个用例都必须<b>新建一个 gate 实例</b>来模拟重启 ——
 * 只断言「同一次运行内不会重建」是测不出这个 bug 的，因为原来的代码在单次运行内是对的。</p>
 */
class YamlFilesQuarantinePersistenceTest {

    private static final Logger LOG = Logger.getLogger("YamlFilesQuarantinePersistenceTest");

    // ---------------------------------------------------------------- 判据本体

    @Test
    @DisplayName("isQuarantined：磁盘有 .corrupt 就算隔离态（即使闸门是全新未锁的）")
    void corruptFileAloneIsEnough(@TempDir Path dir) throws IOException {
        File target = dir.resolve("game-settings.yml").toFile();
        // 模拟「上一轮已隔离」：原文件不在，.corrupt 在
        Files.writeString(dir.resolve("game-settings.yml.corrupt"), "broken", StandardCharsets.UTF_8);
        YamlFileGate freshGate = new YamlFileGate(target, null, LOG);   // ← 模拟重启后的新实例

        assertFalse(freshGate.isReadOnly(), "前置：新闸门必须是未锁的，否则这个用例什么也没测到");
        assertTrue(YamlFiles.isQuarantined(target, freshGate),
                "只有 .corrupt、闸门全新 —— 也必须判为隔离态，否则重启后模板会被写回来");
    }

    @Test
    @DisplayName("isQuarantined：闸门已锁也算隔离态（即使 .corrupt 被服主挪走了）")
    void lockedGateAloneIsEnough(@TempDir Path dir) {
        File target = dir.resolve("game-settings.yml").toFile();
        YamlFileGate gate = new YamlFileGate(target, null, LOG);
        gate.lock();

        assertFalse(YamlFiles.corruptFile(target).exists(), "前置：磁盘上没有 .corrupt");
        assertTrue(YamlFiles.isQuarantined(target, gate),
                "闸门已锁、.corrupt 被挪走 —— 也必须判为隔离态");
    }

    @Test
    @DisplayName("isQuarantined：真·首次安装（无 .corrupt、闸门未锁）→ 不判隔离，必须允许重建")
    void firstInstallIsNotQuarantined(@TempDir Path dir) {
        File target = dir.resolve("game-settings.yml").toFile();
        YamlFileGate gate = new YamlFileGate(target, null, LOG);

        assertFalse(YamlFiles.isQuarantined(target, gate),
                "首次安装不得被判成隔离态，否则新装用户永远拿不到出厂配置");
    }

    @Test
    @DisplayName("isQuarantined：gate 为 null 时不抛 NPE")
    void nullGateIsSafe(@TempDir Path dir) {
        File target = dir.resolve("x.yml").toFile();
        assertFalse(YamlFiles.isQuarantined(target, null),
                "没有 .corrupt 且 gate 为 null → 不判隔离（而不是崩）");
    }

    // ---------------------------------------------------------------- 端到端：跨重启不重建

    @Test
    @DisplayName("跨重启：上一轮隔离过 → 新一轮绝不能从模板重建（三步验证）")
    void quarantineSurvivesRestart(@TempDir Path dir) throws IOException {
        File target = dir.resolve("game-settings.yml").toFile();

        // ── 第一轮：写一份坏文件，让它被隔离 ──
        Files.writeString(target.toPath(), "", StandardCharsets.UTF_8);   // 0 字节 = SUSPECT
        YamlFileGate firstRun = new YamlFileGate(target, null, LOG);
        YamlFiles.Loaded firstLoaded = YamlFiles.load(target, null, LOG);

        assertFalse(firstLoaded.usable(), "前置：第一轮必须判为不可用");
        assertFalse(target.exists(), "前置：隔离后原文件必须已不在磁盘上");
        assertTrue(YamlFiles.corruptFile(target).exists(), "前置：必须留下 .corrupt 现场");

        // ── 第二轮（模拟重启）：全新 gate，原文件仍不存在 ──
        YamlFileGate afterRestart = new YamlFileGate(target, null, LOG);
        assertFalse(afterRestart.isReadOnly(), "重启后的新闸门是干净的（这正是缺陷的成因）");

        // 修复后的判据：必须在 saveResource 之前就问出「这是隔离态」
        assertTrue(YamlFiles.isQuarantined(target, afterRestart),
                "跨重启后仍必须判为隔离态，否则下面那次 saveResource 会把管理员的配置换成出厂值");

        // ── 反证：如果不问这个判据，模板确实会被写回来 ──
        // 这里不真的调 saveResource（需要 Plugin 实例），而是断言判据本身，
        // 并用「判据为 true」来证明调用方会在写模板之前 return。
    }

    @Test
    @DisplayName("lock() 让 writable() 立刻变 false —— 缺了它，缺失分支 return 后写路径仍以为能写")
    void lockClosesTheGate(@TempDir Path dir) {
        File target = dir.resolve("game-settings.yml").toFile();
        YamlFileGate gate = new YamlFileGate(target, null, LOG);

        assertTrue(gate.writable(), "前置：新闸门可写");

        gate.lock();

        assertTrue(gate.isReadOnly(), "lock() 之后必须只读");
        assertFalse(gate.writable(), "lock() 之后 writable() 必须为 false");
        assertFalse(gate.write(new org.bukkit.configuration.file.YamlConfiguration()),
                "lock() 之后的 write 必须失败，不能静默写入");
    }

    @Test
    @DisplayName("reset() 仍能解封 —— lock() 不能把 reloadconfig 的路堵死")
    void resetStillWorksAfterLock(@TempDir Path dir) {
        File target = dir.resolve("game-settings.yml").toFile();
        YamlFileGate gate = new YamlFileGate(target, null, LOG);
        gate.lock();
        assertFalse(gate.writable(), "前置：已锁");

        gate.reset();

        assertTrue(gate.writable(), "reset() 必须能解封，否则服主修好文件后无法恢复");
    }
}
