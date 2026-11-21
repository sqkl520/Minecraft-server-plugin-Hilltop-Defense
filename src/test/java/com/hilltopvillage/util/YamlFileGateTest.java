package com.hilltopvillage.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link YamlFileGate} 的行为测试：读盘失败 → 本进程永久拒写。
 *
 * <p>核心断言是「<b>隔离动作本身不能绕过降级</b>」：坏文件被挪成 {@code .corrupt} 后，
 * 后续重读只能看到 ABSENT。若闸门把 ABSENT 当成「可写」，写盘就会用一个空配置覆盖磁盘，
 * 降级形同虚设。</p>
 */
class YamlFileGateTest {

    private static final Logger LOG = Logger.getLogger("YamlFileGateTest");

    /** 形态正常的 arenas.yml（竞技场段不能只有 world，否则命中截断特征哨兵）。 */
    private static final String REALISTIC_ARENA =
            "arenas:\n  arena1:\n    display-name: x\n    enabled: true\n    world: hilltop_arena1\n";

    @Test
    @DisplayName("首次运行（文件不存在）→ 可写，写盘成功")
    void absentFileIsWritable(@TempDir Path dir) throws IOException {
        File file = dir.resolve("nodes.yml").toFile();
        YamlFileGate gate = new YamlFileGate(file, YamlFiles.nodesValidator(), LOG);

        YamlFiles.Loaded loaded = gate.load();
        assertEquals(YamlFiles.Status.ABSENT, loaded.status());
        assertFalse(gate.isReadOnly());
        assertTrue(gate.writable());

        loaded.config().set("nodes", List.of());
        assertTrue(gate.write(loaded.config()));
        assertTrue(file.exists());
    }

    @Test
    @DisplayName("合法文件 → OK 且可写")
    void validFileIsWritable(@TempDir Path dir) throws IOException {
        File file = dir.resolve("arenas.yml").toFile();
        Files.writeString(file.toPath(), REALISTIC_ARENA, StandardCharsets.UTF_8);
        YamlFileGate gate = new YamlFileGate(file, YamlFiles.arenasValidator(), LOG);

        assertEquals(YamlFiles.Status.OK, gate.load().status());
        assertFalse(gate.isReadOnly());
        assertTrue(gate.write(new YamlConfiguration()));
    }

    @Test
    @DisplayName("损坏文件 → 锁定只读；write() 返回 false 且磁盘无新文件（D1 四步链停在第 2 步）")
    void corruptFileBlocksAllWrites(@TempDir Path dir) throws IOException {
        // 出厂 arenas.yml 在 arenas: 之前被截断（真实写中断的形态：schema-version 在，arenas 段没写完）
        File file = dir.resolve("arenas.yml").toFile();
        Files.writeString(file.toPath(), "# header\nschema-version: 2\n", StandardCharsets.UTF_8);
        YamlFileGate gate = new YamlFileGate(file, YamlFiles.arenasValidator(), LOG);

        assertFalse(gate.load().usable());
        assertTrue(gate.isReadOnly());
        assertFalse(gate.writable());

        YamlConfiguration fresh = new YamlConfiguration();
        fresh.set("arenas.arena1.world", "hilltop_arena1");
        assertFalse(gate.write(fresh), "只读闸门必须拒绝写盘");

        assertFalse(file.exists(), "绝不能生成新的 arenas.yml");
        assertTrue(quarantineFiles(dir, "arenas.yml") > 0);
        assertEquals(1, countFiles(dir), "目录里只应有 .corrupt");
    }

    @Test
    @DisplayName("隔离后重读（此时只能得到 ABSENT）不得解除锁定")
    void reReadAfterQuarantineDoesNotUnlock(@TempDir Path dir) throws IOException {
        File file = dir.resolve("arenas.yml").toFile();
        Files.write(file.toPath(), new byte[0]);
        YamlFileGate gate = new YamlFileGate(file, YamlFiles.arenasValidator(), LOG);

        assertFalse(gate.load().usable());
        // 隔离后文件已不在 → 第二次 load 必然 ABSENT
        YamlFiles.Loaded second = gate.load();
        assertEquals(YamlFiles.Status.ABSENT, second.status());
        assertTrue(gate.isReadOnly(), "ABSENT 不得解除锁定");
        assertFalse(gate.write(second.config()), "否则空配置会覆盖磁盘");
        assertFalse(file.exists());
    }

    @Test
    @DisplayName("写盘失败（目标被目录占据）→ write() 返回 false，不抛")
    void writeFailureReturnsFalse(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("arenas.yml");
        Files.createDirectories(target.resolve("child"));
        YamlFileGate gate = new YamlFileGate(target.toFile(), null, LOG);

        assertTrue(gate.writable());
        assertFalse(gate.write(new YamlConfiguration()));
    }

    @Test
    @DisplayName("拒绝写盘只警告一次")
    void refusesOnce() throws IOException {
        CapturingHandler captured = new CapturingHandler();
        Logger logger = Logger.getLogger("YamlFileGateTest.once");
        logger.setUseParentHandlers(false);
        logger.addHandler(captured);
        logger.setLevel(Level.ALL);
        Path dir = Files.createTempDirectory("gate-once");
        try {
            File file = dir.resolve("arenas.yml").toFile();
            Files.write(file.toPath(), new byte[0]);
            YamlFileGate gate = new YamlFileGate(file, YamlFiles.arenasValidator(), logger);
            gate.load();

            for (int i = 0; i < 5; i++) {
                assertFalse(gate.write(new YamlConfiguration()));
            }

            assertEquals(1, captured.count(Level.WARNING), "拒绝写盘的 warning 只应打一次：" + captured.messages());
        } finally {
            logger.removeHandler(captured);
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    @DisplayName("reset() 解锁；修好文件后 load() 得 OK 且可写（reloadconfig 路径）")
    void resetUnlocksAndRepairedFileBecomesWritable(@TempDir Path dir) throws IOException {
        File file = dir.resolve("arenas.yml").toFile();
        Files.write(file.toPath(), new byte[0]);
        YamlFileGate gate = new YamlFileGate(file, YamlFiles.arenasValidator(), LOG);
        assertFalse(gate.load().usable());
        assertTrue(gate.isReadOnly());

        // 管理员把 .corrupt 改回并补全
        deleteAllQuarantineFiles(dir, "arenas.yml");
        Files.writeString(file.toPath(), REALISTIC_ARENA, StandardCharsets.UTF_8);

        gate.reset();
        assertFalse(gate.isReadOnly());
        assertEquals(YamlFiles.Status.OK, gate.load().status());
        assertTrue(gate.write(new YamlConfiguration()));
    }

    @Test
    @DisplayName("reset() 后文件仍坏 → 再次锁定")
    void resetThenStillBrokenRelocks(@TempDir Path dir) throws IOException {
        File file = dir.resolve("arenas.yml").toFile();
        // 用「解析失败」而不是「哨兵失败」：哨兵失败也会把文件隔离掉，
        // 隔离后第二次 load 只能得到 ABSENT，测不到「重新锁定」。
        Files.writeString(file.toPath(), "arenas:\n arena1:\n world: x\n   y: 2\n", StandardCharsets.UTF_8);
        YamlFileGate gate = new YamlFileGate(file, YamlFiles.arenasValidator(), LOG);
        assertEquals(YamlFiles.Status.PARSE_FAILED, gate.load().status());
        assertTrue(gate.isReadOnly());

        // 管理员把坏文件放回去（模拟「修了一半还是坏的」）
        Files.writeString(file.toPath(), "arenas:\n arena1:\n world: x\n   y: 2\n", StandardCharsets.UTF_8);
        gate.reset();
        assertEquals(YamlFiles.Status.PARSE_FAILED, gate.load().status());
        assertTrue(gate.isReadOnly());

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("arenas.a.world", "w");
        assertFalse(gate.write(yaml));
    }

    @Test
    @DisplayName("无哨兵的文件：解析通过即可写；解析失败仍会锁定")
    void nullValidatorStillProtectsAgainstParseFailure(@TempDir Path dir) throws IOException {
        File ok = dir.resolve("spawnpoints.yml").toFile();
        Files.writeString(ok.toPath(), "spawn: 1\n", StandardCharsets.UTF_8);
        YamlFileGate okGate = new YamlFileGate(ok, null, LOG);
        assertNull(okGate.load().detail());
        assertTrue(okGate.write(new YamlConfiguration()));

        File bad = dir.resolve("broken.yml").toFile();
        Files.writeString(bad.toPath(), "a:\n b:\n  c: 1\n   d: 2\n", StandardCharsets.UTF_8);
        YamlFileGate badGate = new YamlFileGate(bad, null, LOG);
        assertFalse(badGate.load().usable());
        assertTrue(badGate.isReadOnly());
        assertFalse(badGate.write(new YamlConfiguration()));
    }

    @Test
    @DisplayName("readOnly 对并发线程可见：无 happens-before 边的自旋观察（volatile 语义）")
    void readOnlyVisibleAcrossThreads(@TempDir Path dir) throws Exception {
        File file = dir.resolve("arenas.yml").toFile();
        Files.write(file.toPath(), new byte[0]);

        // 写线程在 load() 里把 readOnly 置 true；读线程在完全独立的核上死循环读它。
        // 关键在于避免 start()/join() 提供的 happens-before：读线程先自旋等一个
        // 「双方都跑起来了」的信号，且主线程在读线程退出前不做任何同步动作。
        // 若 readOnly 不是 volatile，读线程可能永远看不到那次写（JMM 允许缓存该字段），
        // 于是自旋到超时 → 测试红。
        YamlFileGate gate = new YamlFileGate(file, YamlFiles.arenasValidator(), LOG);
        java.util.concurrent.atomic.AtomicBoolean go = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean seen = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();

        Thread reader = new Thread(() -> {
            go.set(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (gate.isReadOnly()) {   // ← 被观察的 volatile 读
                    seen.set(true);
                    return;
                }
            }
            timedOut.set(true);
        });
        reader.start();
        while (!go.get()) {
            Thread.onSpinWait();           // 等读线程进入自旋（不建立 readOnly 的 happens-before）
        }
        gate.load();                       // 写线程：置 readOnly = true
        reader.join(10_000);

        assertFalse(timedOut.get(), "读线程 5 秒内没看到 readOnly=true；volatile 语义可能已丢失");
        assertTrue(seen.get(), "并发读线程应能观察到 readOnly=true");
    }

    // ---------------------------------------------------------------- helpers

    private static long countFiles(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.count();
        }
    }

    /** 数某个文件的隔离产物（固定名或带时间戳的都算——自 S10 系列修复起命名带时间戳）。 */
    private static long quarantineFiles(Path dir, String baseName) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.equals(baseName + ".corrupt") || n.startsWith(baseName + ".corrupt.");
                    })
                    .count();
        }
    }

    /** 删掉某个文件的全部隔离产物（模拟服主清理证据后重启）。 */
    private static void deleteAllQuarantineFiles(Path dir, String baseName) throws IOException {
        try (var entries = Files.list(dir)) {
            for (Path p : entries.toList()) {
                String n = p.getFileName().toString();
                if (n.equals(baseName + ".corrupt") || n.startsWith(baseName + ".corrupt.")) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** 收集日志记录，用于断言「只警告一次」。 */
    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        synchronized long count(Level level) {
            return records.stream().filter(r -> r.getLevel().equals(level)).count();
        }

        synchronized List<String> messages() {
            return records.stream().map(LogRecord::getMessage).toList();
        }
    }
}
