package com.hilltopvillage.util;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI 软集成。
 *
 * <p>让 {@code display.yml} 与 {@code messages.yml} 的文本里可以写 PAPI 占位符，
 * 例如把玩家名前缀配成 {@code %UserPrefix_prefix%%player%}。</p>
 *
 * <p><b>为什么要把 PAPI 调用关在嵌套类里</b>：本项目以 {@code provided} 引入 PAPI，
 * 运行时<b>不打包</b>。若在本类的 {@code <clinit>} 或无条件分支里引用
 * {@code me.clip.placeholderapi.PlaceholderAPI}，PAPI 缺席时会在<b>类加载期</b>抛
 * {@code NoClassDefFoundError} —— 那会把持有本类的整个 {@code TabListManager} 一起炸掉，
 * 而不是安静降级。放进 {@link Papi} 这个嵌套静态类、且只在 {@code available == true}
 * 时首次触碰，链接期就被推迟到「确定要用」的那一刻。这是 PAPI 软依赖的标准写法。</p>
 *
 * <p><b>「装了 PAPI」不等于「能解析」</b>：PAPI 对未注册的 expansion 标识符<b>原样返回</b>
 * 输入串。所以装了 PAPI 却没装 UserPrefix 时，{@code %UserPrefix_prefix%} 会原封不动地
 * 回到结果里，必须按「未解析」处理，见 {@link #stripResidual}。</p>
 */
public final class PlaceholderResolver {

    /**
     * 形如 {@code %identifier%} 或 {@code %identifier_param%} 的片段。
     *
     * <p>刻意只匹配这个形状：「删掉所有含 % 的片段」会把 {@code 100%}、
     * {@code 50%折扣} 这类正常文本也吃掉。</p>
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("%[A-Za-z0-9_]+(?:_[A-Za-z0-9_]+)*%");

    private final boolean available;
    private final Logger logger;

    /**
     * 已经警告过的 {@code 来源 + "\u0000" + token} 组合。
     *
     * <p>{@code resolve} 跑在每条刷新周期的渲染路径上。不去重的话，一个没装 UserPrefix 的服
     * 会每帧刷一次同样的 WARN，把控制台冲满 —— 那时真正的问题反而看不见了。</p>
     */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * @param logger 用于报告残留占位符；传 {@code null} 则完全不打日志
     */
    public PlaceholderResolver(Logger logger) {
        this.logger = logger;
        this.available = safeProbe();
    }

    /**
     * 探测 PAPI 是否已启用。
     *
     * <p>用 {@code isPluginEnabled} 而不是 {@code getPlugin() != null}：被禁用的 PAPI
     * 不注册任何 expansion，那时「能解析」是假的。</p>
     */
    private static boolean safeProbe() {
        try {
            return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        } catch (RuntimeException e) {
            // 启动早期或测试环境下 PluginManager 可能不可用；静默降级为「没有 PAPI」
            return false;
        }
    }

    /** PAPI 是否可用。不可用时 {@link #resolve} 原样返回输入。 */
    public boolean available() {
        return available;
    }

    /**
     * 解析一段文本里的 PAPI 占位符。
     *
     * @param player 上下文玩家；解析结果依赖他的数据
     * @param text   可能含占位符的原始文本
     * @return 解析后的文本；PAPI 不可用、文本里没有 {@code %}、或 {@code player} 为 null 时原样返回
     */
    public String resolve(Player player, String text) {
        if (!available || player == null || text == null || text.isEmpty()) {
            return text;
        }
        // 出厂配置里一个 % 都没有：这一步让绝大多数调用只付一次长度扫描的代价
        if (text.indexOf('%') < 0) {
            return text;
        }
        return stripResidual(Papi.apply(player, text));
    }

    /**
     * 剔除结果里残留的 PAPI 占位符，并对每个 {@code (文本, token)} 告警一次。
     *
     * <p><b>为什么不能「退回原文」</b>：原文里就是那个占位符，退回等于什么都没做，
     * 客户端会看到裸露的 {@code %UserPrefix_prefix%}。</p>
     *
     * <p>包可见而非私有，是为了让测试能直接验证「剔除 + 去重」这段逻辑 ——
     * 它不依赖 PAPI 是否安装（PAPI 对未注册的 expansion 就是原样返回），
     * 而测试环境装不了真的 PAPI。</p>
     *
     * @param resolved 已过 PAPI 的文本；不含占位符形状时原样返回
     */
    String stripResidual(String resolved) {
        if (resolved == null || resolved.indexOf('%') < 0) {
            return resolved;
        }
        var matcher = PLACEHOLDER.matcher(resolved);
        if (!matcher.find()) {
            // 含 % 但不构成占位符形状（例如 "100%"）：不是未解析的占位符，原样保留
            return resolved;
        }
        if (logger != null) {
            matcher.reset();
            while (matcher.find()) {
                warnOnce(resolved, matcher.group());
            }
        }
        return PLACEHOLDER.matcher(resolved).replaceAll("");
    }

    /**
     * 每个 {@code (来源文本, 残留 token)} 只警告一次。
     *
     * <p>文案必须指向<b>解法</b>而不是现象：运维看到 {@code %UserPrefix_prefix%} 裸露时，
     * 第一反应通常是「PAPI 没装好」，而实际最常见的成因是「PAPI 装了但对应扩展没装」。</p>
     */
    private void warnOnce(String source, String token) {
        if (!warned.add(source + '\u0000' + token)) return;
        logger.warning("A text still contains the unresolved placeholder '" + token
                + "' after PlaceholderAPI resolution. If you expected a value here,"
                + " the plugin that registers '" + token + "' is probably not installed on this server"
                + " (PlaceholderAPI alone does not register other plugins' placeholders)."
                + " Source text: " + source);
    }

    /**
     * 唯一触碰 PAPI 类型的地方。
     *
     * <p>类名被引用即触发链接；只有 {@link #available} 为 {@code true} 时才会走到这里，
     * 所以 PAPI 缺席时这个类永远不会被加载。</p>
     */
    private static final class Papi {
        private static String apply(Player player, String text) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        }
    }
}
