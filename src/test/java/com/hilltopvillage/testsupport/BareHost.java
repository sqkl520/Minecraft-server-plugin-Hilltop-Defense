package com.hilltopvillage.testsupport;

import com.hilltopvillage.HilltopVillagePlugin;

/**
 * 测试用轻量插件宿主：继承真实主类，但 {@code onEnable()} / {@code onDisable()} 留空。
 *
 * <p><b>为什么需要它</b></p>
 * <p>{@link HilltopVillagePlugin#onEnable()} 会串起 StatsManager / ShopManager /
 * MultiArenaManager / MapRestoreManager 等一整套启动链，其中
 * {@code ArenaManager.ensureWorldsLoaded()} 会调用
 * {@code Bukkit.getWorldContainer()}（MockBukkit 未实现，直接抛
 * {@code UnimplementedOperationException}）。想构造 {@code ConfigManager} /
 * {@code StatsManager} / {@code ShopManager} 这类只依赖
 * {@code getDataFolder()} / {@code getLogger()} / {@code saveResource()} 的共享管理器，
 * 又要付整套启动代价，是不划算的。</p>
 *
 * <p>本类只保留 {@code JavaPlugin} 的身份与资源加载能力，实测一次构造约 1 秒
 * （主要花在 {@code saveResource} 与 LanguageManager 的千余条 i18n 键），
 * 而全量 {@code MockBukkit.load(HilltopVillagePlugin.class)} 是 0.5–9 秒。</p>
 *
 * <p><b>不要用它做什么</b></p>
 * <ul>
 *   <li>不要拿它测 {@code MultiArenaManager} / {@code ArenaInstance} / {@code GameManager}
 *       的玩法逻辑 —— 那些需要真实 {@code onEnable()} 跑完的状态。</li>
 *   <li>它的 getter 全部返回 {@code null}（父类的字段从未被赋值）。凡生产代码里会解引用
 *       {@code plugin.getXxxManager()} 的路径，用 BareHost 都会 NPE，这是<b>预期行为</b>，
 *       不是测试环境的缺陷。</li>
 * </ul>
 *
 * <p><b>加载方式</b></p>
 * <pre>{@code
 * BareHost host = MockBukkit.loadWith(BareHost.class,
 *         new PluginDescriptionFile("BareHost", "1.0.0", BareHost.class.getName()));
 * }</pre>
 *
 * <p><b>返回的是代理，不是 BareHost 本身</b>（实测）：{@code loadWith} 会生成一个
 * {@code BareHostProxy extends BareHost} 的内存子类。它 {@code instanceof BareHost} 成立、
 * 可以直接强转、{@code getDataFolder()} 正常，所以调用侧无感。但它在
 * {@code getClass()} 上与原类不同 —— 不要写 {@code assertEquals(BareHost.class, host.getClass())}。</p>
 *
 * <p>{@code MockBukkit.loadWith(BareHost.class, "plugin.yml")} 也能跑通（实测），
 * 但读的是本工程 {@code src/main/resources/plugin.yml}，其 {@code main:} 指向真实主类。
 * MockBukkit 会无视该字段、按传入的 class 生成代理，所以"能用"是巧合而非契约。
 * 显式传 {@code PluginDescriptionFile} 语义更清楚，建议沿用。</p>
 */
public class BareHost extends HilltopVillagePlugin {

    /** 不做任何模块装配。父类字段保持 null。 */
    @Override
    public void onEnable() {
        // 刻意留空：见类注释。
    }

    /** 不做任何拆解。 */
    @Override
    public void onDisable() {
        // 刻意留空：见类注释。
    }
}
