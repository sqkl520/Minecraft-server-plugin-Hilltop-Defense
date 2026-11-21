package com.hilltopvillage.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F7 回归测试：{@code commands.md} 必须走「同目录临时文件 + 原子替换」，失败时保留旧文档。
 *
 * <p><b>缺陷行为</b>：{@code generate()} 的异步块用 {@code Files.write(docFile.toPath(), ...)}
 * 直接写目标文件。该调用会先截断目标再写，写入过程（崩溃、磁盘满、权限变化、并发读）
 * 失败就留下空文件或半截文档；而生成只在启动时跑一次，没有重试，也没有旧版本可回退。</p>
 *
 * <p><b>为什么用反射而不是直接调用</b>：本测试必须在<b>修复前</b>的代码上编译通过。
 * 修复引入的 {@code writeAtomically(Path, byte[])} 与移动接缝在修复前并不存在，
 * 直接引用会变成编译错误，而编译错误不是红测。按方法名反射后：</p>
 * <ul>
 *   <li>修复前 —— {@link #successfulWriteReplacesTargetAndLeavesNoTempFile()} 与
 *       {@link #failedMoveKeepsOldDocumentAndCleansUpTempFile()} 在查找方法时以清晰的
 *       {@link AssertionError} 失败；{@link #asyncBlockNoLongerTruncatesTheTargetInPlace()}
 *       直接断言「源码里仍是 Files.write(docFile)」而失败。</li>
 *   <li>修复后 —— 三条都通过：临时文件先写满、移动用 ATOMIC_MOVE、移动失败时旧目标原样保留
 *       且临时文件被清理。</li>
 * </ul>
 *
 * <p><b>不依赖时序</b>：没有 sleep，没有并发写盘竞态。移动接缝是同步调用的，
 * 「移动发生的那一刻」被临时文件与旧目标的实际内容直接观测。</p>
 */
@DisplayName("CommandsDocumentGenerator — commands.md 原子写（F7）")
class CommandsDocumentAtomicWriteTest {

    /** 生产源文件；surefire 的工作目录是模块根目录（与 AirdropSpawnGoalTest 的源码守卫一致）。 */
    private static final Path PRODUCTION_SOURCE =
            Path.of("src/main/java/com/hilltopvillage/doc/CommandsDocumentGenerator.java");

    // ------------------------------------------------------------------ 1. 成功路径

    @Test
    @DisplayName("成功路径：整份替换目标文档，成功后不残留临时文件")
    void successfulWriteReplacesTargetAndLeavesNoTempFile() throws Exception {
        Path dir = Files.createTempDirectory("f7-atomic-success");
        Path target = dir.resolve("commands.md");
        Files.writeString(target, "旧文档：整份旧内容\n", StandardCharsets.UTF_8);
        String newDocument = "# 村民守卫战PLUS — 命令文档\n\n整份新内容。\n";

        Method writeAtomically = findWriteAtomically(2);
        try {
            writeAtomically.invoke(null, target, newDocument.getBytes(StandardCharsets.UTF_8));
        } catch (InvocationTargetException failure) {
            throw new AssertionError("writeAtomically 在正常路径不应抛异常，实际抛了 " + failure.getCause(),
                    failure.getCause());
        }

        assertEquals(newDocument, Files.readString(target, StandardCharsets.UTF_8),
                "原子写成功后目标文档必须是完整的新内容");
        assertEquals(List.of("commands.md"), listFileNames(dir),
                "同目录必须只剩目标文档：临时文件若残留，每次服务器启动都会堆一个 .tmp；"
                        + "实际残留：" + listFileNames(dir));
    }

    // ------------------------------------------------------------------ 2. 移动失败路径

    @Test
    @DisplayName("移动失败：旧文档原样保留，临时文件被清理，IOException 抛给调用方")
    void failedMoveKeepsOldDocumentAndCleansUpTempFile() throws Exception {
        Path dir = Files.createTempDirectory("f7-atomic-failure");
        Path target = dir.resolve("commands.md");
        String oldDocument = "# 旧文档\n\n这一行必须在失败的写入之后仍然存在。\n";
        Files.writeString(target, oldDocument, StandardCharsets.UTF_8);
        String newDocument = "# 新文档\n\n这份内容不应落盘。\n";
        byte[] newContent = newDocument.getBytes(StandardCharsets.UTF_8);

        Method writeAtomically = findWriteAtomically(3);
        Class<?> moveStepType = writeAtomically.getParameterTypes()[2];
        assertTrue(moveStepType.isInterface(),
                "第三参数应为可注入的移动接缝（函数式接口），实际是 " + moveStepType.getName());

        AtomicReference<Path> sourceAtMove = new AtomicReference<>();
        AtomicReference<Path> targetAtMove = new AtomicReference<>();
        AtomicReference<String> sourceContentAtMove = new AtomicReference<>();
        AtomicReference<String> targetContentAtMove = new AtomicReference<>();

        InvocationHandler failingMove = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "failing-move-seam";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            Path source = (Path) args[0];
            Path destination = (Path) args[1];
            sourceAtMove.set(source);
            targetAtMove.set(destination);
            sourceContentAtMove.set(readIfExists(source));
            targetContentAtMove.set(readIfExists(destination));
            throw new IOException("注入的移动失败（F7 故障注入）");
        };
        Object failingMoveStep = Proxy.newProxyInstance(
                CommandsDocumentGenerator.class.getClassLoader(),
                new Class<?>[] {moveStepType},
                failingMove);

        Throwable thrown = assertThrows(Throwable.class, () -> {
            try {
                writeAtomically.invoke(null, target, newContent, failingMoveStep);
            } catch (InvocationTargetException invocation) {
                throw invocation.getCause();
            }
        });
        assertTrue(thrown instanceof IOException,
                "移动失败必须以 IOException 抛给调用方（generate() 的 catch 才能打 WARNING）；实际：" + thrown);

        // ---- 移动发生的那一刻：这正是 F7 要证明的窗口 ----
        assertNotNull(sourceAtMove.get(),
                "生产路径没有调用移动接缝 —— 说明它没有「先写临时文件、再替换目标」");
        Path movedFrom = sourceAtMove.get().toAbsolutePath().normalize();
        Path movedTo = targetAtMove.get().toAbsolutePath().normalize();
        assertEquals(target.toAbsolutePath().normalize(), movedTo,
                "移动的目标必须是 commands.md 本身");
        assertNotEquals(movedTo, movedFrom, "移动的源不能是目标文件本身（那就是就地截断写）");
        assertEquals(target.toAbsolutePath().normalize().getParent(), movedFrom.getParent(),
                "临时文件必须与目标同目录（跨分区/跨目录会让 ATOMIC_MOVE 失效）；实际：" + movedFrom);
        assertEquals(newDocument, sourceContentAtMove.get(),
                "移动之前，临时文件里必须已经是完整的新内容");
        assertEquals(oldDocument, targetContentAtMove.get(),
                "移动之前目标文档就已经被改写/截断 —— 这正是 F7 的缺陷："
                        + "直接 Files.write 目标文件时，崩溃/磁盘满会留下空文件或半截文档");

        // ---- 失败之后：旧文档可用，临时文件不残留 ----
        assertEquals(oldDocument, Files.readString(target, StandardCharsets.UTF_8),
                "移动失败后旧文档必须原样保留（这是「没有重试、启动只生成一次」场景下唯一的回退版本）");
        assertEquals(List.of("commands.md"), listFileNames(dir),
                "失败路径必须清理临时文件，否则每次失败都会在数据目录里留下垃圾；"
                        + "实际残留：" + listFileNames(dir));
    }

    // ------------------------------------------------------------------ 3. 源码守卫

    @Test
    @DisplayName("无插件的重载也不得吞掉降级告警（类名 logger 兜底）")
    void twoArgOverloadDoesNotSwallowTheFallbackWarning() throws Exception {
        String source = Files.readString(PRODUCTION_SOURCE, StandardCharsets.UTF_8);
        int start = source.indexOf("static void writeAtomically(Path target, byte[] content) throws IOException {");
        assertTrue(start >= 0, "找不到两参数的 writeAtomically 重载");
        int end = source.indexOf("static void writeAtomically(Path target, byte[] content, MoveStep", start);
        String body = source.substring(start, end);

        assertFalse(body.contains("message -> { }"),
                "两参数重载把降级告警丢进了空 Consumer —— 那条路径上的降级会完全无声，"
                        + "而 javadoc 承诺「降级会记一条 WARNING」；这个重载是最容易被顺手调用的入口");
        assertTrue(body.contains("Logger.getLogger"),
                "两参数重载应退回到类名 logger 记录降级告警（它拿不到插件 logger）");
    }

    @Test
    @DisplayName("源码守卫：异步块不再直接 Files.write 目标，替换必须使用 ATOMIC_MOVE")
    void asyncBlockNoLongerTruncatesTheTargetInPlace() throws Exception {
        String source = Files.readString(PRODUCTION_SOURCE, StandardCharsets.UTF_8);

        assertFalse(source.contains("Files.write(docFile"),
                "异步块仍在直接 Files.write 目标 commands.md —— 截断写没有临时文件，"
                        + "崩溃/磁盘满/权限变化会留下空文档或半截文档，且没有旧版本可回退");
        assertTrue(source.contains("writeAtomically("),
                "commands.md 必须通过 writeAtomically(...) 写盘");
        assertTrue(source.contains("ATOMIC_MOVE"),
                "替换步骤必须使用 StandardCopyOption.ATOMIC_MOVE（单用 REPLACE_EXISTING 在 Windows 上不是原子替换）");
        assertTrue(source.contains("AtomicMoveNotSupportedException"),
                "只允许对 AtomicMoveNotSupportedException 降级为非原子 move，"
                        + "其他 IOException 必须原样抛出（否则失败被吞掉，旧文档状态无法判断）");
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 按名字 + 参数个数查找 {@code writeAtomically}。
     *
     * <p>刻意用反射：修复前该方法不存在，本测试仍能编译，失败原因是清晰的
     * {@link AssertionError} 而不是编译错误。</p>
     */
    private static Method findWriteAtomically(int parameterCount) {
        Method found = Arrays.stream(CommandsDocumentGenerator.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("writeAtomically"))
                .filter(method -> method.getParameterCount() == parameterCount)
                .filter(method -> method.getParameterTypes()[0] == Path.class)
                .findFirst()
                .orElse(null);
        assertNotNull(found,
                "CommandsDocumentGenerator 缺少 writeAtomically(" + parameterCount + " 参数)："
                        + "F7 的修复要求把 commands.md 改成「同目录临时文件 + 原子替换」，"
                        + "并公开一个包内可见的移动接缝供故障注入；当前生产代码仍是直接截断写目标文件");
        found.setAccessible(true);
        return found;
    }

    private static String readIfExists(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static List<String> listFileNames(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(path -> names.add(path.getFileName().toString()));
        }
        names.sort(String::compareTo);
        return names;
    }
}
