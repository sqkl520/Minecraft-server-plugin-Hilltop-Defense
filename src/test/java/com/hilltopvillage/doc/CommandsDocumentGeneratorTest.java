package com.hilltopvillage.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link CommandsDocumentGenerator} 的行为测试。
 *
 * <p><b>为什么这个类的测试值得写</b>：{@code CommandsDocumentGenerator} 的全文是硬编码 markdown，
 * 但它的价值不在「字符串对不对」，而在两件会真的坏掉的事：</p>
 * <ol>
 *   <li><b>异步 IO 不能把启动链拖死。</b>{@code generate()} 必须立刻返回，真正的写盘由
 *       {@code runTaskAsynchronously} 完成。若有人「顺手改成同步写」，服务器启动会多一次
 *       磁盘阻塞；若有人把 {@code runTaskAsynchronously} 写成 {@code runTask}，主线程同样被占。</li>
 *   <li><b>写盘失败不得抛异常。</b>round1 的审计确认 {@code catch (IOException)} 包住了写入，
 *       所以「目录不可写」不会把插件启动打挂 —— 这条是可测的、也是唯一能真的坏的路径。</li>
 * </ol>
 *
 * <p><b>实测阻塞点（本机 4.45.0）</b>：{@code BukkitSchedulerMock#performTicks(int)} <b>不会</b>
 * 推进异步任务（探针实测：推进 400 tick 后文件仍不存在，等待 ~300ms 墙钟后出现，24912 字节）。
 * 因此本类对「异步写完成」的断言使用<b>短墙钟轮询</b>（上限 10 秒，实测量级 ~0.3 秒），
 * 而不是 {@code performTicks}；若轮询超时则用例失败并说明原因，不会静默跳过。</p>
 */
@DisplayName("CommandsDocumentGenerator — 异步写盘与容错")
class CommandsDocumentGeneratorTest extends MockBukkitTestBase {

    /** 异步轮询上限：实测约 300ms，给 10s 只为容忍机器负载（见 round2-01 §8 的波动记录）。 */
    private static final long ASYNC_TIMEOUT_MILLIS = 10_000L;

    private File docFile;

    @BeforeEach
    void setUp() {
        // loadBareHost 的数据目录由 MockBukkit 提供，测试结束随 unmock 一起清理。
    }

    /**
     * 等文件出现且非空。
     *
     * @return 是否在超时前观测到非空文件
     */
    private boolean awaitDocFile(File file) {
        long deadline = System.nanoTime() + ASYNC_TIMEOUT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (file.isFile() && file.length() > 0) return true;
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return file.isFile() && file.length() > 0;
    }

    // ---------------------------------------------------------------- 1. 异步契约

    @Test
    @DisplayName("generate() 立刻返回（写盘必须是异步的，不得阻塞调用线程）")
    void generateReturnsWithoutWaitingForTheDiskWrite() {
        com.hilltopvillage.testsupport.BareHost host = loadBareHost();
        docFile = new File(host.getDataFolder(), "commands.md");

        CommandsDocumentGenerator generator = new CommandsDocumentGenerator(host);

        long start = System.nanoTime();
        generator.generate();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMillis < 500L,
                "generate() 应在数毫秒内返回（实测 " + elapsedMillis + "ms）。"
                        + "若这里变慢，说明有人把 runTaskAsynchronously 改成了同步写盘 —— "
                        + "插件启动会多一次磁盘阻塞");
        // 立刻返回的代价是「此刻文件通常还不存在」。这里不做断言（文件系统可能极快），
        // 但下面那条用例会等到它真的出现。
    }

    @Test
    @DisplayName("异步任务最终真的写出 commands.md（UTF-8，内容非空）")
    void asyncTaskWritesTheDocument() throws Exception {
        com.hilltopvillage.testsupport.BareHost host = loadBareHost();
        docFile = new File(host.getDataFolder(), "commands.md");
        assertFalse(docFile.exists(), "前置条件：文件此刻不应存在，否则测不到「异步写」这一步");

        new CommandsDocumentGenerator(host).generate();

        assertTrue(awaitDocFile(docFile),
                "异步写盘未在 " + ASYNC_TIMEOUT_MILLIS + "ms 内产生非空 commands.md。"
                        + "MockBukkit 的 performTicks 不推进异步任务（本类实测），"
                        + "所以这里用墙钟轮询；超时说明调度器/写盘链路真的坏了");

        String content = Files.readString(docFile.toPath(), StandardCharsets.UTF_8);
        // UTF-8 的证据：中文标题能原样读回（若写成平台默认编码，GBK 环境下会变成乱码）。
        assertTrue(content.startsWith("# 村民守卫战PLUS"),
                "文件必须以 UTF-8 写出且以标题开头；实际开头：" + content.substring(0, Math.min(40, content.length())));
        assertTrue(content.contains("## 基础命令"), "文档应包含「基础命令」段");
        assertTrue(content.contains("## 权限节点"), "文档应包含「权限节点」段");
    }

    @Test
    @DisplayName("重复调用是覆盖而不是追加（除时间戳外两份内容完全一致）")
    void repeatedGenerationOverwrites() throws Exception {
        com.hilltopvillage.testsupport.BareHost host = loadBareHost();
        docFile = new File(host.getDataFolder(), "commands.md");
        CommandsDocumentGenerator generator = new CommandsDocumentGenerator(host);

        generator.generate();
        assertTrue(awaitDocFile(docFile), "前置条件：首次生成应成功");
        String first = Files.readString(docFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(first.length() > 1000, "前置条件：首次生成应写出完整文档");

        generator.generate();
        String second = readAfterLengthSettles(docFile, first.length());

        // 唯一允许变化的是「最后更新」那一行的时间戳；其余必须逐字节相同。
        // 若有人改成 append 模式，第二份会是两份文档的拼接 → 不等。
        assertEquals(withoutTimestamp(first), withoutTimestamp(second),
                "第二次生成的内容必须与第一次一致（忽略时间戳）。不一致最可能的原因是"
                        + "用追加模式写文件 —— 那样每次服务器启动都会把整份命令文档再复制一遍");

        int titleCount = countOccurrences(second, "# 村民守卫战PLUS — 命令文档");
        assertEquals(1, titleCount,
                "标题在一次生成里只能出现一次；出现 " + titleCount + " 次即为追加写");
    }

    /** 抹掉「最后更新」那一行，用于比较两次生成的正文。 */
    private static String withoutTimestamp(String content) {
        StringBuilder sb = new StringBuilder(content.length());
        for (String line : content.split("\n", -1)) {
            if (line.contains("最后更新:")) {
                sb.append("<timestamp>\n");
            } else {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 等文件长度稳定后读全文（最多 {@link #ASYNC_TIMEOUT_MILLIS}）。
     *
     * <p>为什么不是「等到 >= X 字节就读」：写入过程会让长度在 0 ↔ 全长之间跳变，
     * 单次采样命中阈值不代表写完了。稳定判据（连续 5 次 25ms 采样长度不变且达到期望的 90%）
     * 才是可复现的。</p>
     */
    private String readAfterLengthSettles(File file, long expectedLength) throws Exception {
        long deadline = System.nanoTime() + ASYNC_TIMEOUT_MILLIS * 1_000_000L;
        long last = -1L;
        int stableSamples = 0;
        while (System.nanoTime() < deadline) {
            long now = file.length();
            if (now == last && now >= expectedLength * 0.9) {
                if (++stableSamples >= 5) break;
            } else {
                stableSamples = 0;
            }
            last = now;
            Thread.sleep(25L);
        }
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 2. 容错

    @Test
    @DisplayName("目标路径被目录占位时 generate() 不抛异常（启动链不得被打挂）")
    void unwritableTargetDoesNotThrow() throws Exception {
        com.hilltopvillage.testsupport.BareHost host = loadBareHost();

        // 在数据目录里预先建一个**同名目录**：docFile.getParentFile() 存在（所以 mkdirs 分支跳过），
        // 但 Files.write(docFile.toPath()) 必然抛 IOException("Is a directory")。
        // 这是测试「写盘失败被 catch」最省事且真实的方式 —— 不需要替换 getDataFolder()。
        File blocker = new File(host.getDataFolder(), "commands.md");
        assertTrue(blocker.mkdirs(), "前置条件：应在数据目录里建出同名目录占位");
        assertFalse(blocker.isFile(), "前置条件：占位必须是目录而不是文件");

        CommandsDocumentGenerator generator = new CommandsDocumentGenerator(host);

        // 关键断言：异常必须被 catch 在 generate() 内部（或异步体内部），不能穿出来打挂启动链。
        generator.generate();
        assertNotNull(host, "generate() 返回后宿主仍应可用");
    }

    @Test
    @DisplayName("写盘失败只打一条 WARNING，不产生 SEVERE")
    void writeFailureIsReportedAsWarning() throws Exception {
        com.hilltopvillage.testsupport.BareHost host = loadBareHost();
        File blocker = new File(host.getDataFolder(), "commands.md");
        assertTrue(blocker.mkdirs(), "前置条件：应在数据目录里建出同名目录占位");

        com.hilltopvillage.testsupport.MockBukkitTestBase.LogCapture capture =
                attachLogCapture(host.getLogger());
        try {
            new CommandsDocumentGenerator(host).generate();

            // 等异步体执行完（成功会打 INFO，失败会打 WARNING）。
            long deadline = System.nanoTime() + ASYNC_TIMEOUT_MILLIS * 1_000_000L;
            while (System.nanoTime() < deadline && !capture.anyContains("commands.md")) {
                Thread.sleep(25L);
            }

            assertTrue(capture.anyMatch(line -> line.startsWith("WARNING|")
                            && line.contains("commands.md")),
                    "写盘失败必须打 WARNING 说明「命令文档生成失败」，实际日志：" + capture.lines());
            assertEquals(0, capture.countAt(java.util.logging.Level.SEVERE),
                    "写盘失败是降级而不是致命错误，必须是 WARNING 而非 SEVERE（否则运维会误判为插件故障）");
            assertFalse(capture.anyMatch(line -> line.startsWith("INFO|") && line.contains("generated")),
                    "失败路径不得打出「Command reference generated」的 INFO —— 那会让日志自相矛盾");
        } finally {
            detachLogCapture();
        }
    }

    // ---------------------------------------------------------------- 3. 文档内容契约

    @Test
    @DisplayName("文档覆盖 README 承诺的权限节点与关键命令")
    void documentListsPermissionsAndCoreCommands() throws Exception {
        com.hilltopvillage.testsupport.BareHost host = loadBareHost();
        docFile = new File(host.getDataFolder(), "commands.md");

        new CommandsDocumentGenerator(host).generate();
        assertTrue(awaitDocFile(docFile), "前置条件：文档应生成成功");

        List<String> required = List.of(
                "`/hilltop join`",
                "`/hilltop leave`",
                "`/hilltop tps`",
                "`hilltopvillage.admin`",
                "`hilltopvillage.config.admin`",
                "`hilltopvillage.config.nodes`",
                "`/hilltop wave skip",
                "`/hilltop shop give");
        String content = Files.readString(docFile.toPath(), StandardCharsets.UTF_8);
        for (String fragment : required) {
            assertTrue(content.contains(fragment),
                    "文档缺少 " + fragment + " —— 硬编码文本与 README/plugin.yml 脱节时，"
                            + "服主照文档操作会得到错误结果（fix5 M-18 的教训）");
        }
    }

    @Test
    @DisplayName("「注意事项」段落不漏掉控制台黑名单（回归 fix5 M-18 的同类问题）")
    void documentStatesConsoleBlacklist() throws Exception {
        com.hilltopvillage.testsupport.BareHost host = loadBareHost();
        docFile = new File(host.getDataFolder(), "commands.md");

        new CommandsDocumentGenerator(host).generate();
        assertTrue(awaitDocFile(docFile), "前置条件：文档应生成成功");

        String content = Files.readString(docFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(content.contains("控制台限制"),
                "文档必须说明控制台黑名单 —— 这是运维最容易踩的一条");
        assertTrue(content.contains("`/hv` 或 `/htv`"),
                "文档必须写明 /hilltop 的别名，否则玩家不知道有短命令可用");
    }

    // ---------------------------------------------------------------- 工具

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
