package com.hilltopvillage.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * F7 缺陷 1 + 缺陷 2 的回归测试：<b>直接执行生产代码里的降级分支</b>。
 *
 * <p><b>为什么不能复用 {@link CommandsDocumentAtomicWriteTest}</b>：那条用例注入的
 * {@code MoveStep} 替换的是整个「移动」这一步，异常在任何文件被删除之前就抛出了，
 * 所以 {@code moveIntoPlace}（含 {@code catch (AtomicMoveNotSupportedException)} 分支）
 * <b>在全部测试里一次都没被执行过</b>。它断言的是桩的行为，不是生产代码的行为。</p>
 *
 * <p><b>本类的测法</b>：把「真正执行原子移动的一步」抽成 {@code AtomicMover} 接缝，
 * 注入物只做一件事 —— <b>抛 {@link AtomicMoveNotSupportedException} 把控制流推进 catch 分支</b>；
 * catch 分支里的全部逻辑（降级为非原子 {@code Files.move}、失败上抛、降级 WARNING）
 * 都是真实生产代码，在真实文件系统上跑。这是「注入触发条件」而不是「注入被测逻辑」。</p>
 *
 * <p><b>为什么不构造真实的 {@code AtomicMoveNotSupportedException}</b>：它只在
 * 「同目录/同卷 rename 无法原子完成」时由 JDK 抛出，而本类的临时文件与目标同目录，
 * 在本地 NTFS/ext4/APFS 上永远不会触发。真实场景不可造 ⇒ 只能注入触发条件，
 * 但<b>被测逻辑必须是真的</b>。</p>
 */
@DisplayName("CommandsDocumentGenerator — ATOMIC_MOVE 降级路径的真实行为（F7）")
class CommandsDocumentFallbackTest extends MockBukkitTestBase {

    /** 生产源文件；surefire 的工作目录是模块根目录。 */
    private static final Path PRODUCTION_SOURCE =
            Path.of("src/main/java/com/hilltopvillage/doc/CommandsDocumentGenerator.java");

    // ------------------------------------------------------------------ 1. 降级成功路径

    @Test
    @DisplayName("ATOMIC_MOVE 被拒 → 生产降级分支真的执行非原子移动，新文档落盘并记降级 WARNING")
    void atomicMoveRejectionRunsTheRealNonAtomicFallback() throws Throwable {
        Path dir = Files.createTempDirectory("f7-fallback-ok");
        Path target = dir.resolve("commands.md");
        String oldDocument = "# 旧文档\n\n这份内容会在降级移动成功后消失（非原子 move 的固有语义）。\n";
        String newDocument = "# 新文档\n\n降级分支必须把这份内容真的搬到位。\n";
        Files.writeString(target, oldDocument, StandardCharsets.UTF_8);
        Path staged = dir.resolve("commands.md.staged.tmp");
        Files.writeString(staged, newDocument, StandardCharsets.UTF_8);

        List<String> warnings = new ArrayList<>();
        invokeMoveIntoPlace(staged, target, atomicMoverRejectingAtomicMove(), warnings);

        assertEquals(newDocument, Files.readString(target, StandardCharsets.UTF_8),
                "降级分支必须真的把暂存文件搬成目标文档 —— 若这里仍是旧文档，说明 catch 分支没有执行移动");
        assertFalse(Files.exists(staged),
                "降级移动成功后暂存文件应已不存在（它被搬走了）；实际仍存在：" + staged);
        assertEquals(1, warnings.size(),
                "降级必须是可观测的：进入非原子路径要记一条 WARNING（缺陷 1 要求），实际：" + warnings);
        assertTrue(warnings.get(0).contains("ATOMIC_MOVE"),
                "降级 WARNING 必须点名 ATOMIC_MOVE 不受支持，否则运维无法判断文件系统有问题；实际：" + warnings.get(0));
    }

    // ------------------------------------------------------------------ 2. 降级失败路径

    @Test
    @DisplayName("降级移动自身失败 → IOException 原样上抛、降级已记 WARNING、目标侧不被破坏")
    void failingFallbackPropagatesAndDoesNotSwallowTheError() throws Throwable {
        Path dir = Files.createTempDirectory("f7-fallback-fail");
        // 目标是一个**非空目录**：非原子 Files.move(REPLACE_EXISTING) 必然失败
        // （Windows: RemoveDirectory → ERROR_DIR_NOT_EMPTY；POSIX: rename → ENOTEMPTY）。
        Path target = dir.resolve("commands.md");
        Files.createDirectory(target);
        Path occupant = target.resolve("keep.txt");
        Files.writeString(occupant, "目标目录里的内容必须原样保留\n", StandardCharsets.UTF_8);
        Path staged = dir.resolve("commands.md.staged.tmp");
        Files.writeString(staged, "# 新文档\n", StandardCharsets.UTF_8);

        List<String> warnings = new ArrayList<>();
        Throwable thrown = assertThrows(Throwable.class,
                () -> invokeMoveIntoPlace(staged, target, atomicMoverRejectingAtomicMove(), warnings),
                "降级移动失败必须把异常抛给调用方（generate() 的 catch 才能记 WARNING），不得吞掉");

        assertTrue(thrown instanceof IOException,
                "降级失败必须以 IOException 形式上抛；实际：" + thrown);
        assertFalse(thrown instanceof AtomicMoveNotSupportedException,
                "上抛的必须是降级移动（真实文件系统）的失败，而不是注入的触发条件泄漏出来 —— "
                        + "若这里看到 AtomicMoveNotSupportedException，说明生产代码没走 catch 分支；实际：" + thrown);

        assertTrue(Files.isDirectory(target),
                "降级移动失败后目标仍是那个目录（本次失败不应把目标换成文件）");
        assertEquals("目标目录里的内容必须原样保留\n",
                Files.readString(occupant, StandardCharsets.UTF_8),
                "降级移动失败不得破坏目标侧已有的内容");

        assertEquals(1, warnings.size(),
                "即使降级最终失败，也必须先记下「已进入非原子路径」这条 WARNING —— "
                        + "否则运维只能看到一个 IOException，无法判断是不是文件系统不支持原子替换；实际：" + warnings);
        assertTrue(warnings.get(0).contains("ATOMIC_MOVE"), "降级 WARNING 必须点名 ATOMIC_MOVE；实际：" + warnings.get(0));
    }

    // ------------------------------------------------------------------ 3. 只有该异常才降级

    @Test
    @DisplayName("普通 IOException 不触发降级：原样上抛、不记降级 WARNING、目标不被触碰")
    void otherIoExceptionsDoNotTriggerTheFallback() throws Throwable {
        Path dir = Files.createTempDirectory("f7-no-fallback");
        Path target = dir.resolve("commands.md");
        String oldDocument = "# 旧文档\n\n普通 IO 失败时这份内容必须原样保留。\n";
        Files.writeString(target, oldDocument, StandardCharsets.UTF_8);
        Path staged = dir.resolve("commands.md.staged.tmp");
        Files.writeString(staged, "# 新文档\n", StandardCharsets.UTF_8);

        IOException injected = new IOException("注入的普通 IO 失败（不是 ATOMIC_MOVE 不受支持）");
        List<String> warnings = new ArrayList<>();
        Throwable thrown = assertThrows(Throwable.class,
                () -> invokeMoveIntoPlace(staged, target, atomicMoverThrowing(injected), warnings));

        assertEquals(injected, thrown,
                "必须是同一个异常实例原样上抛 —— 任何降级/包装都会让 generate() 的日志失真");
        assertEquals(oldDocument, Files.readString(target, StandardCharsets.UTF_8),
                "非 ATOMIC_MOVE 的失败绝不能改动目标文档");
        assertEquals(List.of(), warnings,
                "未进入降级分支就不该有降级 WARNING（否则日志会把普通 IO 故障误报成文件系统不支持原子替换）；"
                        + "实际：" + warnings);
    }

    // ------------------------------------------------------------------ 4. javadoc 诚实性

    @Test
    @DisplayName("javadoc 不再声称「任何失败都保留旧目标」，并写明降级在 Windows 上会先删目标")
    void javadocStopsClaimingTheFalseInvariant() throws Exception {
        String source = Files.readString(PRODUCTION_SOURCE, StandardCharsets.UTF_8);

        assertFalse(source.contains("任何失败都保留旧目标"),
                "javadoc 仍在承诺「任何失败都保留旧目标」—— 这在降级路径上是假的："
                        + "Windows 的非原子 Files.move 先 DeleteFile(target) 再 MoveFileEx，"
                        + "MoveFileEx 失败时旧文档已经没了（JDK sun/nio/fs/WindowsFileCopy.move）");        assertTrue(source.contains("非原子"),
                "javadoc 必须明确写出降级路径用的是非原子移动");
        assertTrue(source.contains("DeleteFile") && source.contains("先删除目标"),
                "javadoc 必须写明降级路径在 Windows 上「先删除目标、再改名」—— "
                        + "这是「旧的 commands.md 可能已经没了」的唯一原因，泛泛说「可能失败」不够");
        assertTrue(source.contains("WARNING") || source.contains("warning"),
                "javadoc 必须写明降级会记 WARNING（唯一让运维发现走了降级路径的手段）");
    }

    // ------------------------------------------------------------------ 5. 端到端：降级真的打到插件 logger

    @Test
    @DisplayName("端到端：generate() 走进降级分支时，插件 logger 里真的出现降级 WARNING，文档仍然写出")
    void generateReportsTheFallbackToThePluginLogger() throws Exception {
        MockBukkitTestBase.LogCapture capture = null;
        try {
            com.hilltopvillage.testsupport.BareHost host = loadBareHost();
            capture = attachLogCapture(host.getLogger());
            Path docFile = new File(host.getDataFolder(), "commands.md").toPath();

            Object mover = atomicMoverRejectingAtomicMove();
            // 用「参数类型是 AtomicMover」的那个包内构造器，把拒绝注入到真实调用链上。
            java.lang.reflect.Constructor<?> ctor = Arrays
                    .stream(CommandsDocumentGenerator.class.getDeclaredConstructors())
                    .filter(c -> c.getParameterCount() == 2)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "缺少「插件 + AtomicMover」的包内构造器：降级 WARNING 无法端到端验证"));
            ctor.setAccessible(true);
            CommandsDocumentGenerator generator =
                    (CommandsDocumentGenerator) ctor.newInstance(host, mover);
            generator.generate();

            long deadline = System.nanoTime() + 10_000L * 1_000_000L;
            // 等的是「成功那条 INFO」而不是 WARNING：降级 WARNING 在非原子移动**之前**发出，
            // 只等它会把「移动还没做完」当成「已结束」，从而读到尚未落盘的文件。
            while (System.nanoTime() < deadline && !capture.anyContains("Command reference generated")) {
                Thread.sleep(25L);
            }

            assertTrue(capture.anyMatch(line -> line.startsWith("WARNING|") && line.contains("ATOMIC_MOVE")),
                    "降级分支必须把 WARNING 打到插件 logger（运维唯一能发现「文件系统不支持原子替换」的渠道）；"
                            + "实际日志：" + capture.lines());
            assertEquals(0, capture.countAt(java.util.logging.Level.SEVERE),
                    "降级是可恢复的文档产物问题，不该是 SEVERE；实际日志：" + capture.lines());
            assertTrue(capture.anyContains("Command reference generated"),
                    "降级成功后仍应打出「已生成」的 INFO（降级不是失败）；实际日志：" + capture.lines());
            assertTrue(Files.isRegularFile(docFile),
                    "降级路径成功后 commands.md 仍必须存在（走到非原子移动就该把它搬到位）；"
                            + "目录内容：" + listNames(docFile.getParent()));
        } finally {
            if (capture != null) {
                detachLogCapture();
            }
        }
    }

    // ------------------------------------------------------------------ 反射工具

    private static List<String> listNames(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException unreadable) {
            return List.of("<unreadable: " + unreadable + ">");
        }
    }

    /**
     * 调用 {@code moveIntoPlace(Path, Path, AtomicMover, Consumer<String>)}。
     *
     * <p>用反射而不是直接调用：修复前该方法不存在，反射能让失败表现为清晰的
     * {@link AssertionError}（「降级逻辑不可单独测试」），而不是一个编译错误。</p>
     */
    private static void invokeMoveIntoPlace(Path source, Path target, Object atomicMover,
            List<String> warnings) throws Throwable {
        Method moveIntoPlace = findMoveIntoPlace();
        try {
            moveIntoPlace.invoke(null, source, target, atomicMover, (Consumer<String>) warnings::add);
        } catch (InvocationTargetException invocation) {
            throw invocation.getCause();
        }
    }

    private static Method findMoveIntoPlace() {
        Method found = Arrays.stream(CommandsDocumentGenerator.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("moveIntoPlace"))
                .filter(method -> method.getParameterCount() == 4)
                .filter(method -> method.getParameterTypes()[0] == Path.class)
                .findFirst()
                .orElse(null);
        assertNotNull(found,
                "CommandsDocumentGenerator 缺少可单独调用的 moveIntoPlace(Path, Path, AtomicMover, Consumer)："
                        + "降级分支必须成为可被真实执行的生产代码（缺陷 2：现有测试只替换整个 MoveStep 接缝，"
                        + "moveIntoPlace 从未被执行过）");
        found.setAccessible(true);
        Class<?> moverType = found.getParameterTypes()[2];
        assertTrue(moverType.isInterface(),
                "第 3 参数应为可注入的「原子移动」函数式接口，实际是 " + moverType.getName());
        return found;
    }

    /** 注入物：只把控制流推进 catch 分支，不做任何文件操作。 */
    private static Object atomicMoverRejectingAtomicMove() throws Exception {
        Method moveIntoPlace = findMoveIntoPlace();
        Class<?> moverType = moveIntoPlace.getParameterTypes()[2];
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "atomic-mover-rejecting-atomic-move";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            Path source = (Path) args[0];
            Path destination = (Path) args[1];
            throw new AtomicMoveNotSupportedException(
                    source.toString(), destination.toString(), "注入：本文件系统不支持原子替换（F7 降级路径）");
        };
        return Proxy.newProxyInstance(CommandsDocumentGenerator.class.getClassLoader(),
                new Class<?>[] {moverType}, handler);
    }

    /** 注入物：抛一个不是 {@link AtomicMoveNotSupportedException} 的普通 IO 失败。 */
    private static Object atomicMoverThrowing(IOException failure) throws Exception {
        Method moveIntoPlace = findMoveIntoPlace();
        Class<?> moverType = moveIntoPlace.getParameterTypes()[2];
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "atomic-mover-throwing-io";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            throw failure;
        };
        return Proxy.newProxyInstance(CommandsDocumentGenerator.class.getClassLoader(),
                new Class<?>[] {moverType}, handler);
    }
}
