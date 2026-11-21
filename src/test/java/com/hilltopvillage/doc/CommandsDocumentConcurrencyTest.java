package com.hilltopvillage.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F7 缺陷 3：{@code writeAtomically} 的并发写必须被串行化。
 *
 * <p><b>缺陷</b>：{@code grep "synchronized|Lock|concurrent"} 在生成器里零命中。两个线程
 * 各自 {@code createTempFile}、各自 {@code move}，两次写入的「移动」窗口可以重叠：
 * 在 {@code ATOMIC_MOVE} 主路径上表现为「后移动者赢，先移动者的写入被静默丢弃」，
 * 在非原子降级路径上窗口更大（先删目标再改名），重叠会真的丢文件。</p>
 *
 * <p><b>本测法为什么不是「测桩」</b>：被测对象是 {@link CommandsDocumentGenerator#writeAtomically}
 * 的<b>串行化性质</b>本身。{@code MoveStep} 在这里只用来「把移动拉长到可观测的 80ms」，
 * 它是否被打桩与被测性质无关 —— 断言的是「两个线程能否同时处在写盘临界区内」，
 * 而临界区和锁都是真实的生产代码。</p>
 *
 * <p><b>为什么不用「并发写后校验文件内容」的测法</b>：单文件替换在 Windows/POSIX 上都是
 * 一次 rename，内容永远是某一份完整的文档，因此内容断言在修复前也会通过 —— 那种用例
 * 无法暴露缺陷，只会给出「并发没问题」的假结论。真正可观测的差异是临界区重叠。</p>
 */
@DisplayName("CommandsDocumentGenerator — 并发写串行化（F7）")
class CommandsDocumentConcurrencyTest {

    private static final int THREADS = 4;
    private static final long MOVE_DWELL_MILLIS = 80L;

    @Test
    @DisplayName("多个线程同时写同一目标时，写盘临界区里最多只有一个线程")
    void concurrentWritesNeverOverlapInsideTheCriticalSection() throws Exception {
        Path dir = Files.createTempDirectory("f7-concurrent");
        Path target = dir.resolve("commands.md");
        Files.writeString(target, "# 初始文档\n", StandardCharsets.UTF_8);

        Method writeAtomically = findWriteAtomicallyWithSeam();
        Class<?> moveStepType = writeAtomically.getParameterTypes()[2];

        AtomicInteger inCriticalSection = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        AtomicInteger movesCompleted = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();

        // 让所有线程尽量同时起跑：barrier 只负责起跑同步，不负责临界区同步。
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);

        InvocationHandler dwellingMove = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "dwelling-move-seam";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            int concurrency = inCriticalSection.incrementAndGet();
            try {
                maxObserved.accumulateAndGet(concurrency, Math::max);
                Thread.sleep(MOVE_DWELL_MILLIS);
                Files.move((Path) args[0], (Path) args[1],
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                movesCompleted.incrementAndGet();
            } finally {
                inCriticalSection.decrementAndGet();
            }
            return null;
        };
        Object moveStep = Proxy.newProxyInstance(
                CommandsDocumentGenerator.class.getClassLoader(),
                new Class<?>[] {moveStepType},
                dwellingMove);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            byte[] content = ("# 并发写入 #" + i + "\n").getBytes(StandardCharsets.UTF_8);
            Thread thread = new Thread(() -> {
                try {
                    ready.countDown();
                    go.await();
                    writeAtomically.invoke(null, target, content, moveStep);
                } catch (java.lang.reflect.InvocationTargetException wrapped) {
                    synchronized (failures) {
                        failures.add(wrapped.getCause());
                    }
                } catch (Throwable other) {
                    synchronized (failures) {
                        failures.add(other);
                    }
                }
            }, "f7-writer-" + i);
            thread.start();
            threads.add(thread);
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "前置条件：" + THREADS + " 个写线程应在 10s 内全部就绪");
        go.countDown();
        for (Thread thread : threads) {
            thread.join(30_000L);
            assertTrue(!thread.isAlive(), "写线程 " + thread.getName() + " 在 30s 内未结束 —— 疑似死锁");
        }

        assertEquals(List.of(), failures,
                "并发写不应出现任何异常：串行化之后每个线程都应正常完成；实际失败：" + failures);
        assertEquals(THREADS, movesCompleted.get(),
                "每个线程都应完成自己的那次替换，实际只有 " + movesCompleted.get() + " 次");

        assertEquals(1, maxObserved.get(),
                "观测到 " + maxObserved.get() + " 个线程同时处在写盘临界区内。"
                        + "writeAtomically 未加锁时，两个线程可以同时 createTempFile + move，"
                        + "在非原子降级路径（先删目标再改名）上这会真的丢文件；"
                        + "主路径上也表现为后移动者的写入静默覆盖先移动者");
    }

    @Test
    @DisplayName("源守卫（补充）：临界区由显式的 lock()/unlock() 包围，不是只写了个「synchronized」字样")
    void writeAtomicallyIsGuardedByALock() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/hilltopvillage/doc/CommandsDocumentGenerator.java"),
                StandardCharsets.UTF_8);

        // 这条只是补充守卫：上面的行为测试才是判据。它便宜地挡住「整段锁被删掉」这类改动。
        assertTrue(source.contains(".lock()") && source.contains(".unlock()"),
                "CommandsDocumentGenerator 里没有成对的 lock()/unlock() —— "
                        + "writeAtomically 对并发写没有任何处理（F7 缺陷 3）");
    }

    private static Method findWriteAtomicallyWithSeam() {
        Method found = Arrays.stream(CommandsDocumentGenerator.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("writeAtomically"))
                .filter(method -> method.getParameterCount() == 3)
                .filter(method -> method.getParameterTypes()[0] == Path.class)
                .findFirst()
                .orElse(null);
        assertNotNull(found, "CommandsDocumentGenerator 缺少 writeAtomically(Path, byte[], MoveStep) 接缝");
        found.setAccessible(true);
        return found;
    }
}
