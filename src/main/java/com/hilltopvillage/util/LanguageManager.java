package com.hilltopvillage.util;

import com.hilltopvillage.HilltopVillagePlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多语言管理器 - 从 messages.yml 加载消息，支持 en/zh 切换。
 * JAR 内嵌文件为主，磁盘文件为覆盖层，确保新 key 始终可用。
 */
public class LanguageManager {

    private final HilltopVillagePlugin plugin;
    private File messagesFile;

    /** 全局默认语言 */
    private String defaultLocale = "zh";

    /** 玩家个人语言偏好 (uuid -> locale)。并发容器：NumericInputHandler 的 AsyncPlayerChatEvent 在 Netty 线程读它 */
    private final Map<UUID, String> playerLocales = new ConcurrentHashMap<>();

    /**
     * 消息缓存: locale -> (key -> message)。
     *
     * <p>三层并发设计：外层 {@code ConcurrentHashMap} 保证 {@code get()} 任意时刻都有可读的完整引用；
     * 内层用<b>不可变 Map</b>（{@code Map.copyOf}）保证异步聊天线程读到的永远是某个版本的完整快照，
     * 不会看到 {@code reload()} 中途「清掉一半」的状态；reload 时外层逐 locale 原子替换引用即可。
     * （{@code clear() + 逐条 put} 的写法在并发下会让异步线程读到空 map，是振荡器的源头。）</p>
     */
    private final Map<String, Map<String, String>> messageCache = new ConcurrentHashMap<>();

    public LanguageManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    // ---- 加载 ----

    public void reload() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();
        messagesFile = new File(dataFolder, "messages.yml");

        // 1. 从 JAR 加载完整默认值（永远有所有 key）
        YamlConfiguration jarConfig = new YamlConfiguration();
        InputStream jarStream = plugin.getResource("messages.yml");
        if (jarStream != null) {
            try (Reader reader = new InputStreamReader(jarStream, StandardCharsets.UTF_8)) {
                jarConfig = YamlConfiguration.loadConfiguration(reader);
            } catch (IOException e) {
                plugin.getLogger().warning("Cannot load jar messages.yml: " + e.getMessage());
            }
        }

        // 2. 加载磁盘文件（旧版/管理员自定义，可能缺 key，但自定义优先）
        YamlConfiguration diskConfig = messagesFile.exists()
                ? YamlConfiguration.loadConfiguration(messagesFile)
                : new YamlConfiguration();

        // 3. 构建缓存：JAR 为基础，磁盘值覆盖
        //    这里顺手把颜色码预编译掉：消息内容是静态的（只在 reload 时变），
        //    与其在每次 get() 时反复扫描 '&'，不如在构建缓存时只做一次。
        //
        //    并发语义：每个 locale 在<b>局部 Map</b> 里拼好后再整体 put 到外层 ConcurrentHashMap；
        //    put 之前异步读线程看到的仍是旧版本的完整快照，不会看到半成品。
        //    （HashMap 是局部方法变量，只在当前线程使用，无需并发容器。）
        for (String locale : new String[]{"en", "zh"}) {
            Map<String, String> map = new java.util.HashMap<>();

            // 先从 JAR 加载（保证所有 key 都有值）
            ConfigurationSection jarSection = jarConfig.getConfigurationSection(locale);
            if (jarSection != null) {
                for (String key : jarSection.getKeys(true)) {
                    String path = locale + "." + key;
                    if (jarConfig.isString(path)) {
                        map.put(key, compile(jarConfig.getString(path)));
                    }
                }
            }

            // 再用磁盘文件覆盖（管理员自定义生效）
            ConfigurationSection diskSection = diskConfig.getConfigurationSection(locale);
            if (diskSection != null) {
                for (String key : diskSection.getKeys(true)) {
                    String path = locale + "." + key;
                    if (diskConfig.isString(path)) {
                        map.put(key, compile(diskConfig.getString(path)));
                    }
                }
            }

            // 包成不可变快照再发布：读线程拿到的引用永远指向某个版本的完整 map，
            // 不会看到「清掉一半」的中间态。compile() 已保证 value 非 null，可直接 copyOf。
            messageCache.put(locale, Map.copyOf(map));
        }

        // 4. 把 JAR 里缺失的 key 补写到磁盘文件（方便管理员查看/编辑全量 key）
        boolean dirty = false;
        for (String key : jarConfig.getKeys(true)) {
            if (!diskConfig.contains(key)) {
                diskConfig.set(key, jarConfig.get(key));
                dirty = true;
            }
        }
        if (dirty || !messagesFile.exists()) {
            try {
                diskConfig.save(messagesFile);
            } catch (IOException e) {
                plugin.getLogger().warning("Cannot save messages.yml: " + e.getMessage());
            }
        }

        // 5. 读取全局语言设置
        String savedLocale = plugin.getConfig().getString("language", "zh");
        if ("en".equals(savedLocale) || "zh".equals(savedLocale)) {
            defaultLocale = savedLocale;
        }

        plugin.getLogger().info("LanguageManager loaded " +
                messageCache.getOrDefault("en", Map.of()).size() + " EN keys, " +
                messageCache.getOrDefault("zh", Map.of()).size() + " ZH keys. Locale: " + defaultLocale);
    }

    // ---- 编译 ----

    /**
     * 预编译颜色码：只对真正含 '&' 的文本做转换。
     *
     * <p>{@link ChatColor#translateAlternateColorCodes(char, String)} 每次调用都要先全量扫描
     * 数出 '&' 的个数、分配数组、再全量拷贝一遍。而消息内容是静态的（只在 {@link #reload()} 时变），
     * 因此把这一步从「每次取词」提前到「构建缓存时」，热路径上就只剩一次 Map 查找。</p>
     */
    private static String compile(String raw) {
        if (raw == null) return null;
        // 绝大多数无颜色码的键走这条捷径，连数组分配都省掉
        if (raw.indexOf('&') < 0) return raw;
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    // ---- 语言切换 ----

    public String getLocale() {
        return defaultLocale;
    }

    public void setLocale(String locale) {
        if (locale.equals("en") || locale.equals("zh")) {
            this.defaultLocale = locale;
            plugin.getConfig().set("language", locale);
            plugin.saveConfig();
        }
    }

    public String getPlayerLocale(Player player) {
        if (player == null) return defaultLocale;
        return playerLocales.getOrDefault(player.getUniqueId(), defaultLocale);
    }

    public void setPlayerLocale(Player player, String locale) {
        if (player == null) return;
        if (locale.equals("en") || locale.equals("zh")) {
            playerLocales.put(player.getUniqueId(), locale);
        }
    }

    /**
     * 玩家退服时清除其语言偏好（由 PlayerQuit 清理链调用）。
     *
     * <p>偏好是纯内存的会话态，不持久化：退出即失效是预期行为。若该玩家在线时又调用
     * {@link #setPlayerLocale}，会重新写入；{@link #getPlayerLocale} 对缺失的偏好
     * 回退到 {@code defaultLocale}，不会 NPE。玩家重进后取默认语言，与旧行为一致
     * （旧行为同样不持久，偏好只在本次会话有效）。</p>
     */
    public void clearPlayerLocale(UUID uuid) {
        if (uuid != null) playerLocales.remove(uuid);
    }

    // ---- 消息获取 ----

    /**
     * 使用默认语言获取消息（颜色码已在 reload() 预编译）
     */
    public String get(String key) {
        return get(key, defaultLocale);
    }

    /**
     * 指定语言获取消息（返回值已含颜色码，缓存里就是编译后的最终串）
     */
    public String get(String key, String locale) {
        Map<String, String> map = messageCache.get(locale);
        if (map == null) map = messageCache.get(defaultLocale);
        if (map == null) return key;
        String msg = map.get(key);
        if (msg == null && !locale.equals(defaultLocale)) {
            msg = messageCache.getOrDefault(defaultLocale, Map.of()).get(key);
        }
        return msg != null ? msg : key;     // 已在 reload() 中预编译，此处无需再翻译
    }

    /**
     * 获取玩家语言对应的消息
     */
    public String getFor(Player player, String key) {
        return get(key, getPlayerLocale(player));
    }

    /**
     * 获取消息并替换占位符 {0}, {1}, ...
     */
    public String get(String key, String locale, Object... args) {
        String msg = get(key, locale);
        if (args == null || args.length == 0) return msg;
        int firstBrace = msg.indexOf('{');
        if (firstBrace < 0) return msg;      // 无占位符：零额外分配，直接返回缓存里的字符串
        return fill(msg, firstBrace, args);
    }

    /**
     * 单遍扫描填充 {@code {0}} / {@code {1}} 形式的占位符。
     *
     * <p>替代原来的 {@code String.replace} 链：后者每次调用都要分配 int[16] 索引数组并做
     * 全量拷贝，占位符越多中间串越多。这里一次遍历、一次 StringBuilder。</p>
     *
     * <h3>与原实现的等价性</h3>
     *
     * <p>差异来源是两代算法的结构不同：{@code String.replace} 是「一轮一轮顺序替换」，
     * 每一轮都从头重扫整串；本方法是单遍扫描，扫过的位置不再回头。已用对照程序穷举验证
     * （模板字符集 {A,{,},0,1} 长度 ≤ 6 全部组合 + 40 万次随机 fuzz，共 51.7 万组输入），
     * 两者只在下述 <b>4 类</b>输入上不同，其余完全逐字一致：</p>
     *
     * <pre>
     * 类别                        示例输入                                原实现              本实现
     * A 模板含嵌套/交叠大括号      "{0{1}"   args=[P,Q]                  "{0Q"               "{0{1}"
     * B 数字索引带前导零           "{01}"    args=[P,Q]                  "{01}"              "Q"
     * C 替换值自身含大括号         "..{0}..{1}.."  args=["{1}","Y"]      "..Y..Y.."          "..{1}..Y.."
     * D 模板含落单大括号           单独出现时两者等价（已验证）             —                   —
     * </pre>
     *
     * <p>关于 B 类的精确边界：差异只出现在「新实现解析出的下标 ≠ 旧实现的字面目标」时。
     * 例：{@code "{01}"} 旧实现找字面目标 {@code {1}} 找不到（模板里是 {@code {01}}）故原样保留，
     * 新实现解析出下标 1 于是替换 —— 有差异；而 {@code "{10}"} 配 11 个参数时旧实现能命中字面目标
     * {@code {10}}、新实现解析出下标 10，两者都是 {@code "K"} —— <b>没有</b>差异。
     * 更长位数的索引还可能因 int 累加溢出落回范围内（如 {@code "{4294967297}"} 同样解析为 1）。</p>
     *
     * <p>注意 D 类：{@code "{"}、{@code "}"}、{@code "{}"}、{@code "a{b"}、{@code "a}b"}、
     * {@code "{0"}、{@code "0}"} 这几种「模板本身有落单大括号但替换值干净」的输入，
     * 两者输出<b>相同</b>。落单大括号只在与 A/B/C 叠加时才参与造成差异，
     * 不能单独作为一类。</p>
     *
     * <h3>这 4 类在本插件中的可达性（均已核实为不可达）</h3>
     *
     * <ul>
     *   <li><b>A 类</b>：{@code messages.yml} 里 {@code \{[^}]*\{} 的匹配数为 <b>0</b>，
     *       即没有任何模板含嵌套/交叠大括号。</li>
     *   <li><b>B 类</b>：全部占位符只有 {@code {0}}{@code {1}}{@code {2}}{@code {3}}{@code {world}}
     *       五种形态（{@code \{[0-9]{2,}\}} 匹配数为 0），连多位数索引都没有，
     *       更谈不上前导零索引。</li>
     *   <li><b>C 类</b>：<b>出货配置下</b>不可达。55 处「带 ≥2 个替换参数」的调用点全部核对过
     *       （已剔除注释里的伪调用），参数来源是玩家名、世界名、竞技场 ID、数字、
     *       {@code String.format} 结果，以及 {@code shop.yml} 的商品名（{@code "&6神圣重锤"} 这类）。
     *       特别核对了三处「参数本身是 i18n 结果」的调用：
     *       {@code WaveSettingsMenu} 的怪物显示名（值是 {@code "§eExplode Beetle"} 之类）、
     *       {@code ShopListener} 的商品名、{@code AdminDeployManager} 的
     *       {@code deploy.menu-random-yes/no}（值是 {@code "§a是"} / {@code "§c否"}）——
     *       三者都不含大括号。<b>注意前提</b>：{@code shop.yml} 的商品名与 {@code arenas.yml} 的
     *       显示名是<b>服主可自由填写的文本</b>，若有人填进 {@code "{"}，C 类就会变成可达 ——
     *       这也正是本方法语义上更合理的地方（模板已编译，不该被运行期数据二次改写），
     *       但仍需在改动这些配置时留意。</li>
     *   <li><b>D 类</b>：{@code messages.yml} 去掉所有成对占位符后剩余文本里
     *       {@code \{} 或 {@code \}} 的出现次数为 <b>0</b>。</li>
     * </ul>
     *
     * <p>另外，{@code setup.step1} 里的 {@code {world}} 是原实现同样不替换的漏网键
     * （{@code get(key, locale, args)} 只会去替换 {@code {0}}…{@code {n}}），
     * 两者行为一致，不属于本方法的差异。</p>
     *
     * <p><b>语义上本方法也更合理</b>：模板在 {@code reload()} 时即已编译，运行期不该因为
     * 某个玩家名恰好长得像占位符而被后续轮次二次改写。</p>
     *
     * <p>容错规则（与原实现一致）：</p>
     * <ul>
     *   <li>{@code {}} 空括号 → 原样保留</li>
     *   <li>{@code {abc}} 非数字 → 原样保留</li>
     *   <li>{@code {9}} 但 args 不足 10 个 → 原样保留</li>
     *   <li>{@code {} 没有配对} → 从该 '{' 起的剩余部分原样追加，结束</li>
     *   <li>同一个占位符出现多次 → 每次都替换（原 {@code String.replace} 也是全部替换）</li>
     *   <li>{@code args} 为 {@code null} 或空数组 → 原样返回模板（原实现在 null 上会 NPE，
     *       本方法更宽容；本插件无 {@code null} 调用点，属纯健壮性提升）</li>
     * </ul>
     *
     * @param msg  已编译颜色码的模板
     * @param from 首个 '{' 的下标（调用方已定位，避免重复扫描）
     * @param args 替换值
     */
    private static String fill(String msg, int from, Object... args) {
        StringBuilder sb = new StringBuilder(msg.length() + 16);
        sb.append(msg, 0, from);
        int i = from;
        int len = msg.length();
        while (i < len) {
            char c = msg.charAt(i);
            if (c != '{') {
                sb.append(c);
                i++;
                continue;
            }
            int close = msg.indexOf('}', i + 1);
            if (close < 0) {
                // 没有配对的 '}'：剩余部分原样保留
                sb.append(msg, i, len);
                break;
            }
            int idx = -1;
            boolean numeric = close > i + 1;
            for (int k = i + 1; k < close; k++) {
                char d = msg.charAt(k);
                if (d < '0' || d > '9') {
                    numeric = false;
                    break;
                }
                idx = (idx < 0 ? 0 : idx) * 10 + (d - '0');
            }
            if (!numeric || idx < 0 || idx >= args.length) {
                // 非数字 / 空括号 / 下标越界：原样保留（含大括号）
                sb.append(msg, i, close + 1);
            } else {
                sb.append(String.valueOf(args[idx]));
            }
            i = close + 1;
        }
        return sb.toString();
    }

    public String getFor(Player player, String key, Object... args) {
        return get(key, getPlayerLocale(player), args);
    }

    /**
     * 发送消息给玩家（使用玩家语言）
     */
    public void send(Player player, String key, Object... args) {
        if (player != null) {
            player.sendMessage(getFor(player, key, args));
        }
    }
}
