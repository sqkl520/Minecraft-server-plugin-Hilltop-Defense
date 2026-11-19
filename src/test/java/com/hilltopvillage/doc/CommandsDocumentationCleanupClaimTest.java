package com.hilltopvillage.doc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * 生成文档与 {@code /hilltop tps} 实际输出的一致性测试。
 *
 * <p>{@code TpsDiagnostic#printDiagnostic} 只输出 TPS/MSPT、内存、玩家、波次、怪物、节点
 * 与各世界实体/区块计数，不存在 DisplayEntity 清理队列；{@code DisplayEntityManager}
 * 的类注释也明确说明该清理队列链已作为零调用死代码删除。因此自动生成的命令参考
 * 不得再向管理员承诺这个不存在的输出项。</p>
 */
@DisplayName("CommandsDocumentGenerator — /hilltop tps 不得记录已删除的清理队列")
class CommandsDocumentationCleanupClaimTest extends MockBukkitTestBase {

    private static final long ASYNC_TIMEOUT_MILLIS = 10_000L;

    @Test
    @DisplayName("生成的 /hilltop tps 文档不得包含 DisplayEntity 清理队列")
    void generatedTpsDocumentationDoesNotClaimDisplayEntityCleanupQueue() throws Exception {
        BareHost host = loadBareHost();
        File docFile = new File(host.getDataFolder(), "commands.md");

        new CommandsDocumentGenerator(host).generate();
        String content = awaitDocument(docFile);

        assertTrue(content.contains("### `/hilltop tps`"),
                "前置条件：生成的文档必须包含 /hilltop tps 段，否则本测试没有瞄准目标段落");
        assertFalse(content.contains("DisplayEntity 清理队列"),
                "生成的 /hilltop tps 文档仍声称输出「DisplayEntity 清理队列」，但 "
                        + "TpsDiagnostic#printDiagnostic 不输出该项，DisplayEntityManager "
                        + "也注明该清理队列链已删除。管理员会据此排查不存在的输出。");
    }

    /** 等待异步写盘完成并读取全文；长度连续稳定后才读取，避免读到半份文件。 */
    private String awaitDocument(File file) throws Exception {
        long deadline = System.nanoTime() + ASYNC_TIMEOUT_MILLIS * 1_000_000L;
        long lastLength = -1L;
        int stableSamples = 0;
        while (System.nanoTime() < deadline) {
            long length = file.isFile() ? file.length() : -1L;
            if (length > 0 && length == lastLength) {
                if (++stableSamples >= 3) {
                    break;
                }
            } else {
                stableSamples = 0;
            }
            lastLength = length;
            Thread.sleep(25L);
        }

        assertTrue(file.isFile() && file.length() > 0,
                "异步写盘未在 " + ASYNC_TIMEOUT_MILLIS + "ms 内产生非空 commands.md");
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }
}
