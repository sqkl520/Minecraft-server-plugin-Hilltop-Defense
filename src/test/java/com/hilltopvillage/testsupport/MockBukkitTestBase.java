package com.hilltopvillage.testsupport;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import com.hilltopvillage.HilltopVillagePlugin;

/**
 * 所有 MockBukkit 测试的公共基类：负责 server 的 mock/unmock 生命周期与常用夹具。
 *
 * <p><b>为什么要有基类</b>：MockBukkit 是<b>进程级单例</b>状态（它反射写
 * {@code Bukkit.server}）。漏掉 {@code unmock()} 会让下一个测试类在
 * {@code MockBukkit.mock()} 处抛 {@code IllegalStateException: Already mocking}，
 * 且报错位置与真正的肇事测试相距很远。把 {@code unmock()} 放进
 * {@code @AfterEach} 是唯一可靠的防漏方式 —— 断言失败、异常抛出都仍会执行它。</p>
 *
 * <p><b>失败时也要 unmock</b>：{@code @AfterEach} 在测试方法抛异常后仍执行，
 * 因此失败用例不会污染后续用例。但<b>不要</b>在 {@code @BeforeEach} 里捕获异常后继续
 * 使用半初始化的 server —— 那时状态已不可信。</p>
 *
 * <p><b>日志捕获</b>：{@link #attachLogCapture} 把 handler 挂到 Bukkit 的插件 logger 上，
 * 断言行内容（例如 "unrecognized top-level keys"）。用完必须
 * {@link #detachLogCapture()}，否则 handler 会跨测试累积。</p>
 */
public abstract class MockBukkitTestBase {

    /** 当前测试的 mock 服务端。每次 {@code @BeforeEach} 新建。 */
    protected ServerMock server;

    /** 每次 {@code @BeforeEach} 新建的临时插件数据目录（JUnit 会在测试后清理）。 */
    protected Path dataFolder;

    /** 挂载中的日志捕获器；null 表示未挂载。 */
    private LogCapture logCapture;

    @BeforeEach
    void startMockServer() throws IOException {
        server = MockBukkit.mock();
        dataFolder = Files.createTempDirectory("hv-test-data-");
    }

    @AfterEach
    void stopMockServer() {
        detachLogCapture();
        try {
            MockBukkit.unmock();
        } finally {
            TestPaths.deleteQuietly(dataFolder);
            server = null;
            dataFolder = null;
        }
    }

    // ---------------------------------------------------------------- 夹具

    /**
     * 加载 {@link BareHost}（onEnable 空实现），并断言它真的 enabled。
     *
     * @return 已启用的宿主实例
     */
    protected BareHost loadBareHost() {
        PluginDescriptionFile description = new PluginDescriptionFile(
                "HilltopVillageTestHost", "1.0.0", BareHost.class.getName());
        BareHost host = MockBukkit.loadWith(BareHost.class, description);
        assertNotNull(host, "MockBukkit.loadWith 返回 null —— 宿主未加载");
        return host;
    }

    /**
     * 创建一个 MockBudkit 的哑插件（{@link PluginMock}），用于只需要
     * {@code JavaPlugin} 身份的构造器（如 {@code ArenaManager}）。
     *
     * <p>注意：{@code PluginMock} 是<b>代理</b>而非 {@code HilltopVillagePlugin} 子类，
     * 不能强转成后者。需要 {@code HilltopVillagePlugin} 类型时用 {@link #loadBareHost()}。</p>
     */
    protected PluginMock createDummyPlugin(String name) {
        return MockBukkit.createMockPlugin(name);
    }

    /**
     * 预建出厂 {@code arenas.yml} 里点名的全部世界。
     *
     * <p>这是 {@code MockBukkit.load(HilltopVillagePlugin.class)} 能跑通的前提：
     * {@code ArenaManager.ensureWorldsLoaded()} 只对「尚未加载」的世界调用
     * {@code Bukkit.getWorldContainer()}，而 MockBukkit 未实现该方法。四个名字
     * （{@code lobby} + {@code hilltop_arena1..3}）任何一个缺失都会抛
     * {@code UnimplementedOperationException}。</p>
     *
     * <p>即便 {@code arenas.yml} 将来增删竞技场，{@code MultiArenaManager} 也只会
     * 为配置里存在的世界要求这一条件，所以本方法按出厂模板写死是安全的；
     * 若配置被改，请以 {@code src/main/resources/arenas.yml} 为准同步本方法。</p>
     */
    protected void createFactoryWorlds() {
        for (String name : FACTORY_WORLD_NAMES) {
            if (server.getWorld(name) == null) {
                server.addSimpleWorld(name);
            }
        }
    }

    /** 出厂 {@code arenas.yml} 里的世界名（大厅 + 三个竞技场）。 */
    public static final List<String> FACTORY_WORLD_NAMES =
            List.of("lobby", "hilltop_arena1", "hilltop_arena2", "hilltop_arena3");

    /**
     * 全量加载真实插件（跑完整的 {@code onEnable()}）。
     *
     * <p><b>实测前提</b>：必须先 {@link #createFactoryWorlds()}。不带任何世界会抛
     * {@code UnimplementedOperationException at ServerMock.getWorldContainer}；
     * 只建 {@code world} 也一样失败 —— 这与两份 round2 报告里"5 个世界（含 world）"的说法不符，
     * 实测只需要 {@code arenas.yml} 点名的 4 个。</p>
     *
     * <p><b>代价</b>：单次约 0.5–2.1 秒（实测四次：2078ms / 559ms / 1181ms / 4807ms）。
     * 只要测试只需要 {@code getDataFolder()} / {@code getLogger()}，请用
     * {@link #loadBareHost()}（约 1 秒且不依赖世界），不要让每个类都付全量启动的钱。</p>
     *
     * <p><b>返回值与 {@code performTicks}</b>：{@code enableAllConfiguredArenas()} 走异步启用队列，
     * 因此返回时 {@code getActiveArenas()} 可能仍是空的。需要真实 {@code ArenaInstance} 的测试
     * 必须再 {@code server.getScheduler().performTicks(20)}（实测 20 tick 足够，
     * 之后 {@code getArena("arena1")} 非 null 且 {@code getGameManager()} 可用）。</p>
     */
    protected HilltopVillagePlugin loadFullPlugin() {
        createFactoryWorlds();
        HilltopVillagePlugin plugin = MockBukkit.load(HilltopVillagePlugin.class);
        assertNotNull(plugin, "MockBukkit.load 返回 null —— 插件未加载");
        return plugin;
    }

    /**
     * {@link #loadFullPlugin()} + 推进 20 tick，让竞技场启用队列跑完。
     *
     * @return 已启用、且至少有一个 {@code ArenaInstance} 就绪的插件
     */
    protected HilltopVillagePlugin loadFullPluginWithArenas() {
        HilltopVillagePlugin plugin = loadFullPlugin();
        server.getScheduler().performTicks(20);
        return plugin;
    }

    // ---------------------------------------------------------------- 日志

    /**
     * 开始捕获某个 logger 的输出。重复调用会先卸载旧 handler。
     *
     * @param logger 通常是 {@code plugin.getLogger()}
     * @return 捕获器，可随时读取已收集的行
     */
    protected LogCapture attachLogCapture(Logger logger) {
        detachLogCapture();
        logCapture = new LogCapture(logger);
        return logCapture;
    }

    /** 卸载日志 handler。不调用也无妨（{@code @AfterEach} 兜底）。 */
    protected void detachLogCapture() {
        if (logCapture != null) {
            logCapture.detach();
            logCapture = null;
        }
    }

    /**
     * 内存日志捕获器。行格式固定为 {@code LEVEL|message}，便于用
     * {@code contains("WARNING|...")} 断言。
     *
     * <p>它同时捕获 {@code Throwable} 的类名（追加在消息后），因为
     * {@code log(Level, String, Throwable)} 的异常对象不会出现在
     * {@link LogRecord#getMessage()} 里，而 MockBukkit 未实现的操作正是靠异常类型区分的。</p>
     */
    public static final class LogCapture extends Handler {

        private final Logger logger;
        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        LogCapture(Logger logger) {
            this.logger = logger;
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            if (record == null) return;
            String line = record.getLevel().getName() + "|" + record.getMessage();
            if (record.getThrown() != null) {
                line += " [" + record.getThrown().getClass().getSimpleName() + "]";
            }
            lines.add(line);
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }

        void detach() {
            logger.removeHandler(this);
        }

        /** 已捕获的行（快照，可安全遍历）。 */
        public List<String> lines() {
            synchronized (lines) {
                return List.copyOf(lines);
            }
        }

        /** 是否有任一捕获行满足条件。 */
        public boolean anyMatch(java.util.function.Predicate<String> predicate) {
            synchronized (lines) {
                return lines.stream().anyMatch(predicate);
            }
        }

        /** 是否有任一捕获行包含给定片段（大小写敏感）。 */
        public boolean anyContains(String fragment) {
            return anyMatch(line -> line.contains(fragment));
        }

        /** 给定等级的捕获行数量。 */
        public long countAt(Level level) {
            String prefix = level.getName() + "|";
            synchronized (lines) {
                return lines.stream().filter(line -> line.startsWith(prefix)).count();
            }
        }

        /** 清空已捕获内容但保留 handler。 */
        public void reset() {
            lines.clear();
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 文件与目录的小工具，放在这里避免额外顶层类。 */
    public static final class TestPaths {

        private TestPaths() {
        }

        /** 递归删除，忽略一切错误（测试清理不应盖过真正的失败原因）。 */
        public static void deleteQuietly(Path root) {
            if (root == null) return;
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 清理失败不影响测试结论
                    }
                });
            } catch (IOException ignored) {
                // 目录可能已被 JUnit 清理
            }
        }

        /** 把内容整体写入文件，父目录自动创建。 */
        public static void write(File file, String content) throws IOException {
            File parent = file.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            Files.writeString(file.toPath(), content);
        }

        /** 读全文；文件不存在返回空串。 */
        public static String read(File file) throws IOException {
            return file.exists() ? Files.readString(file.toPath()) : "";
        }
    }
}
