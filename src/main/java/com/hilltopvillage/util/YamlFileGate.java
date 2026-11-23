package com.hilltopvillage.util;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 单个配置文件的读写闸门：一旦读盘被判不可用，本进程内永久拒写，直到显式 reset()。
 *
 * <p><b>为什么需要「每文件一个闸门」而不是各类各写一份 boolean：</b>{@code arenas.yml} 有 3 个写入方
 * （{@code ArenaManager} / {@code AdminDeployManager} / {@code ArenaAdminService}）。各持一份会导致
 * 「A 判只读、B 照写」，降级形同虚设。</p>
 *
 * <p><b>不自动解封：</b>任何写路径都不得调用 reset()。auto-unblock 会让
 * 「管理员误操作 → 解封 → 空数据固化」重演，而这正是本缺陷的核心杀伤链。</p>
 *
 * <p><b>为什么读盘后「文件不存在」不能解除锁定：</b>读盘被判不可用时，{@link YamlFiles#load} 会把
 * 文件挪成 {@code *.corrupt}。此后 {@code load()} 只能得到 {@link YamlFiles.Status#ABSENT}
 * （文件确实不在了），而「不存在」在首次运行时是合法的。若照搬「usable 就可写」，那么损坏文件被隔离后，
 * <b>下一次写盘前的重读</b>就会看到 ABSENT 并放行，用一个空配置覆盖磁盘 —— 降级被自己的隔离动作绕过。
 * 因此本类把锁定做成一闩：只有「真的读到并校验通过」（{@code OK}）或显式 {@link #reset()} 才解封。</p>
 */
public final class YamlFileGate {

    private final File file;
    private final Function<YamlConfiguration, String> validator;
    private final Logger logger;

    /** 本进程内是否已锁定为只读。volatile：读盘在主线程，写盘可能来自异步路径。 */
    private volatile boolean readOnly;

    /** 只警告一次，避免每次拒写都刷一行。 */
    private final AtomicBoolean warned = new AtomicBoolean();

    /**
     * @param file      被守护的文件
     * @param validator 语义哨兵（见 {@link YamlFiles} 的各 {@code xxxValidator()}）；null = 不做语义校验
     * @param logger    日志出口
     */
    public YamlFileGate(File file, Function<YamlConfiguration, String> validator, Logger logger) {
        this.file = file;
        this.validator = validator;
        this.logger = logger;
    }

    /** 一次性读盘。失败即锁定，本进程后续所有写入都被拒绝。 */
    public YamlFiles.Loaded load() {
        YamlFiles.Loaded loaded = YamlFiles.load(file, validator, logger);
        if (!loaded.usable()) {
            readOnly = true;
        } else if (loaded.status() == YamlFiles.Status.OK) {
            // 真的读到并校验通过 → 解除锁定（管理员修好文件后的 reload 路径）
            readOnly = false;
        }
        // ABSENT 且此前已锁定 → 保持锁定，见类注释「为什么文件不存在不能解除锁定」
        return loaded;
    }

    /** 读盘是否已判为不可用（只读降级中）。不会触发警告。 */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * 本进程当前是否允许写盘。被拒时打一次 warning（含原因与恢复指引）。
     *
     * <p>指引里的 {@code /hilltop reloadconfig} 对每一份受本闸门守护的文件都成立：
     * 它先 {@link #reset()} 五个闸门，再真读一遍每份文件——{@code arenas.yml} 走
     * {@code MultiArenaManager#reloadDefinitions()}（重建 registry），其余走各自的
     * {@code loadFromFile()} / {@code reloadDisplay()} / {@code reload()}。
     * 不要说成「只能重启」，那会把一条本进程内可完成的恢复动作藏起来。</p>
     *
     * @return true = 可写
     */
    public boolean writable() {
        if (readOnly && warned.compareAndSet(false, true)) {
            logger.warning("Refusing to write " + file.getName()
                    + ": the file was unreadable at startup (see the SEVERE log above). "
                    + "Repair/restore it, then run /hilltop reloadconfig (this re-reads the file),"
                    + " or restart the server.");
        }
        return !readOnly;
    }

    /**
     * 写盘（受闸门约束）。
     *
     * @return true = 已落盘；false = 被闸门拒绝或写盘失败（失败时已打 SEVERE）
     */
    public boolean write(YamlConfiguration yaml) {
        if (!writable()) {
            return false;
        }
        try {
            YamlFiles.write(file, yaml);
            return true;
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Failed to save " + file.getName(), exception);
            return false;
        }
    }

    /** 仅由 /hilltop reloadconfig 或重启后的重新构造调用。 */
    public void reset() {
        readOnly = false;
        warned.set(false);
    }

    /**
     * 强制置为只读，不解封。
     *
     * <p>用于「已知文件处于隔离态，且调用方选择<b>不</b>调 {@link #load()}」的场景。
     * 正常情况下 {@code load()} 遇到不可用文件会自己上闩，但配置文件缺失的分支里
     * 调用方可能在 {@code load()} 之前就 return（因为绝不能 {@code saveResource} 重建）；
     * 此时若不显式上闩，{@link #writable()} 会返回 true，
     * 后续任何写路径都会以为可以写。</p>
     *
     * <p>与 {@link #reset()} 对称：这是唯一的另一个能改变 {@code readOnly} 的方法，
     * 且方向相反——只有它能把闸门<b>关上</b>而不需要先有一次失败的读盘。</p>
     */
    public void lock() {
        readOnly = true;
    }
}
