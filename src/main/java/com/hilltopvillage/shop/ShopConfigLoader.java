package com.hilltopvillage.shop;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.util.YamlFileGate;
import com.hilltopvillage.util.YamlFiles;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

/**
 * 村民商店配置加载器（shop.yml）。
 * 首次运行释放默认模板，之后每次调用 load() 重新解析为 ShopConfig 快照。
 */
public class ShopConfigLoader {

    /** 合法商品类型 */
    private static final Set<String> VALID_TYPES = new HashSet<>(
            Arrays.asList("HAMMER", "FIREBALL", "REPAIR_ITEM", "MATERIAL", "EQUIPMENT"));

    /**
     * 真正消费 {@link ShopItem#getAmount()} 的类型（来源：{@code ShopManager#deliverItem}）。
     * FIREBALL 走 {@code asQuantity(amount)}、REPAIR_ITEM 走 {@code createRepairItem(amount)}、
     * MATERIAL 走 {@code new ItemStack(icon, amount)}；HAMMER 与 EQUIPMENT 固定发 1 个，
     * 其 amount 被完全忽略，故不在此集合内。
     */
    private static final Set<String> USES_AMOUNT = new HashSet<>(
            Arrays.asList("FIREBALL", "REPAIR_ITEM", "MATERIAL"));

    /** GUI 允许的尺寸（Bukkit 箱子行数：9/18/27/36/45/54） */
    private static final List<Integer> VALID_GUI_SIZES = Arrays.asList(9, 18, 27, 36, 45, 54);

    /** fix5 M-10：本次加载已占用的 GUI 槽位（id → slot），重复槽位检测用 */
    private final java.util.Map<Integer, String> occupiedSlots = new java.util.HashMap<>();

    /**
     * 本次 load() 期间产生的「已保留但对服主可见」的配置问题摘要。
     *
     * <p>只收<b>不丢商品</b>的那类问题（MATERIAL 缺图标、price 缺键按默认值计价、槽位冲突、
     * 负数价格钳制……）。被 skip 的条目各自已有一条 warning，不重复登记，避免同一件事报两遍。</p>
     */
    private final List<String> loadWarnings = new ArrayList<>();

    private final HilltopVillagePlugin plugin;

    /**
     * shop.yml 的读写闸门。
     *
     * <p><b>为什么 shop 模块也需要它</b>（F1 回归）：{@link YamlFiles#load} 在判定文件不可用时
     * 会把 {@code shop.yml} 改名成 {@code shop.yml.corrupt}，于是下一次 {@link #load()} 看到的
     * 「文件不存在」与<b>首次安装</b>在文件系统层面完全一样 —— 旧实现在这里无条件
     * {@code saveResource}，等于把服主的商品 / 价格 / currency.name / npc.name / gui.title
     * 全部替换成出厂值。闸门的「ABSENT 不解除锁定」正是为这条路径设计的：
     * 只有真的读到并解析通过（{@code OK}）才解封。</p>
     *
     * <p>shop.yml 目前没有写入方，闸门的写侧用不到；这里只用它的读侧闩
     * （{@link YamlFileGate#load()} + {@link YamlFileGate#isReadOnly()}）。
     * 与 {@code ArenaManager} / {@code ConfigManager} 的用法一致。</p>
     */
    private final YamlFileGate gate;

    public ShopConfigLoader(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.gate = new YamlFileGate(new File(plugin.getDataFolder(), "shop.yml"), null,
                plugin.getLogger());
    }

    /**
     * 加载 shop.yml 为 ShopConfig。
     * 配置缺失/非法的条目跳过并 warning，不阻断整体加载；
     * 但文件本身无法解析（损坏/截断），或 shop.items 存在但不是映射（截断常见形态）时抛异常，
     * 不得降级成空商店。
     *
     * <p><b>抛异常是刻意的</b>（不是本次回归要改的东西）：{@code onEnable} 对
     * {@code ShopManager} 不做 try/catch，注释写明核心模块初始化失败就应当禁用插件，
     * 而不是带着半初始化状态继续跑。本次修的是「隔离后下一次 load 会把出厂模板写回磁盘」，
     * 不是「坏配置要不要失败」—— 所以仍抛，但保证现场（{@code .corrupt}）不被覆盖。</p>
     */
    public ShopConfig load() {
        File file = new File(plugin.getDataFolder(), "shop.yml");
        if (!file.exists() && isQuarantined(file)) {
            // 关键：这里**不能** saveResource。文件不存在有两个截然不同的原因，
            // 判据必须可区分：
            //   · 首次安装 → 没有隔离产物、闸门未锁定 → 该重建（见下方分支）
            //   · 被隔离   → 磁盘上留着 shop.yml.corrupt，或本进程闸门已锁 → 绝不能重建
            throw new IllegalStateException(quarantinedMessage(file)
                    + " Refusing to write a factory-default shop.yml over the quarantined file:"
                    + " that would silently replace the admin's items, prices, currency.name,"
                    + " npc.name and gui.title with the shipped template.");
        }
        // 未处于隔离态且文件不存在 = 真正的首次安装（或无隔离产物的显式重置）。
        if (!file.exists()) {
            plugin.saveResource("shop.yml", false);
        }
        YamlFiles.Loaded loaded = gate.load();
        if (!loaded.usable()) {
            String detail = loaded.detail();
            throw new IllegalStateException("Cannot use shop.yml (" + loaded.status() + ")"
                    + (detail == null ? "" : ": " + detail)
                    + ". Refusing to replace the live shop with an empty configuration.");
        }
        YamlConfiguration yaml = loaded.config();

        // F1: a parseable truncation can leave `items:` with a null value. Bukkit drops null
        // values while converting the YAML map, so the key disappears from YamlConfiguration and
        // looks identical to an omitted key. Inspect the raw YAML only in that ambiguous case:
        // a present-but-null (or otherwise non-mapping) items value is a truncation. An explicit
        // `items: {}` is a Map and stays valid, and an omitted key keeps the existing behavior.
        if (yaml.getConfigurationSection("shop.items") == null) {
            Map<?, ?> rawShop = rawShopSection(file);
            if (rawShop != null && rawShop.containsKey("items")
                    && !(rawShop.get("items") instanceof Map)) {
                throw new IllegalStateException("shop.yml: 'shop.items' is present but is not a mapping"
                        + " (value: " + rawShop.get("items") + "); the file appears truncated."
                        + " Refusing to replace the live shop configuration.");
            }
        }

        // ---- 货币 ----
        Material currencyMaterial = Material.EMERALD;
        String currencyMatStr = yaml.getString("shop.currency.material");
        if (currencyMatStr != null && !currencyMatStr.isEmpty()) {
            Material parsed = Material.getMaterial(currencyMatStr);
            // fix6 Y2-06：非 item 材质会让 createCurrencyItem 的 new ItemStack 抛异常（掉币/退款链）
            if (parsed != null && parsed.isItem()) {
                currencyMaterial = parsed;
            } else {
                plugin.getLogger().warning("shop.yml: invalid currency material '"
                        + currencyMatStr + "' (unknown or not a carryable item), keeping default EMERALD");
            }
        }
        String currencyName = yaml.getString("shop.currency.name");
        if (currencyName != null && currencyName.isEmpty()) {
            currencyName = null;
        }

        // ---- NPC ----
        String npcName = yaml.getString("shop.npc.name");
        if (npcName != null && npcName.isEmpty()) {
            npcName = null;
        }

        // ---- GUI ----
        String guiTitle = yaml.getString("shop.gui.title");
        if (guiTitle != null && guiTitle.isEmpty()) {
            guiTitle = null;
        }
        int guiSize = clampGuiSize(yaml.getInt("shop.gui.size", 27));

        // ---- 商品列表 ----
        occupiedSlots.clear();
        loadWarnings.clear();
        List<ShopItem> items = new ArrayList<>();
        ConfigurationSection itemsSection = yaml.getConfigurationSection("shop.items");
        if (itemsSection != null) {
            for (String id : itemsSection.getKeys(false)) {
                ShopItem item = parseItem(yaml, id, guiSize);
                if (item != null) {
                    items.add(item);
                }
            }
        }

        // 单条 warning 在 latest.log 里很容易被一轮启动的其他输出淹没，而服主打开 GUI 时
        // 只看到「少了一格 / 图标是屏障」。这里在加载末尾补一行**汇总**，把「这份 shop.yml
        // 有 N 处需要看」提到 info 级（而不是再叠一条 warning），并点名具体条目 id。
        if (!loadWarnings.isEmpty()) {
            plugin.getLogger().info("shop.yml loaded " + items.size() + " item(s) with "
                    + loadWarnings.size() + " configuration warning(s) — review them before"
                    + " players notice: " + String.join("; ", loadWarnings));
        }

        return new ShopConfig(currencyMaterial, currencyName, npcName, guiTitle, guiSize, items);
    }

    /**
     * 在 {@code shop.yml} 不存在的当下，判定「这是被隔离」还是「这是首次安装」。
     *
     * <p>两个信号缺一不可：</p>
     * <ul>
     *   <li><b>磁盘信号</b>：{@code shop.yml.corrupt} 存在。{@link YamlFiles#quarantine} 用
     *       {@code REPLACE_EXISTING} 保留最新一次现场，跨进程成立 —— 重启后仍能识别。</li>
     *   <li><b>进程信号</b>：{@link YamlFileGate#isReadOnly()}。{@code YamlFileGate} 刻意让
     *       「ABSENT 不解除锁定」，所以同进程内的第二次 {@code load()} 即使 {@code .corrupt}
     *       已被服主删除也仍然拒绝重建。</li>
     * </ul>
     *
     * <p><b>为什么不能只看其中一个：</b>只看 {@code .corrupt} 时，隔离成功但服主随后把
     * {@code .corrupt} 挪走/删除、又没放回 {@code shop.yml}，同进程下一次 {@code load()} 就会
     * 重建模板；只看闸门时，重启后闩已随进程消失，模板照样会被写回 —— 而评审描述的最坏链条
     * 正发生在重启之后。</p>
     */
    private boolean isQuarantined(File file) {
        return gate.isReadOnly() || YamlFiles.hasQuarantinedCopy(file);
    }

    /**
     * 隔离产物路径前缀：与 {@link YamlFiles#quarantinedCopy} 的命名保持一致
     * （{@code <name>.corrupt.<时间戳>}）。仅用于文案里点名，不用于判定。
     */
    private static File corruptFile(File file) {
        return YamlFiles.corruptFile(file);
    }

    /**
     * 隔离态下的失败文案：必须点名 {@code shop.yml.corrupt} 与恢复步骤。
     *
     * <p>旧文案只说「Cannot use shop.yml」，而此刻 {@code shop.yml} 已经不在磁盘上了 ——
     * 服主按文件名去数据目录找，只会找到一份不存在的文件，然后（最坏情况）手工新建一份空文件。
     * 必须明确告诉他现场在哪、怎么恢复。</p>
     */
    private static String quarantinedMessage(File file) {
        return "shop.yml is missing but " + corruptFile(file).getName()
                + " is present: the file was quarantined because it could not be parsed."
                + " Kept the shop unavailable instead of rebuilding the factory template."
                + " Restore/repair the content from " + corruptFile(file).getName()
                + " as shop.yml, then restart the server or run /hilltop shop reload.";
    }

    /**
     * 重新读取原始 YAML 的 shop 段。仅用于区分「没有 items 键」与「items 键值为 null」：
     * 后者在 Bukkit 转换时会被丢弃，{@link YamlConfiguration} 已经看不到它。
     */
    private Map<?, ?> rawShopSection(File file) {
        try {
            Object root = new Yaml().load(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            if (root instanceof Map<?, ?> rootMap) {
                Object shop = rootMap.get("shop");
                if (shop instanceof Map<?, ?> shopMap) {
                    return shopMap;
                }
            }
            return null;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("shop.yml could not be inspected for a truncated"
                    + " 'shop.items' section: " + failure.getMessage(), failure);
        }
    }

    /** 解析单个商品，非法条目返回 null 并 warning */
    private ShopItem parseItem(YamlConfiguration yaml, String id, int guiSize) {
        String path = "shop.items." + id;

        String type = yaml.getString(path + ".type", "");
        if (!VALID_TYPES.contains(type)) {
            plugin.getLogger().warning("shop.yml: item '" + id + "' has unknown type '" + type
                    + "' (valid: HAMMER/FIREBALL/REPAIR_ITEM/MATERIAL/EQUIPMENT), skipped");
            return null;
        }

        Material icon = Material.BARRIER;
        boolean iconConfigured = false;
        String iconMatStr = yaml.getString(path + ".icon.material");
        if (iconMatStr != null && !iconMatStr.isEmpty()) {
            Material parsed = Material.getMaterial(iconMatStr);
            // fix6 Y2-06：非 item 材质放行会让 ShopGUI:72 的 new ItemStack(icon) 先抛 → 整个商店 GUI
            // 打不开（MATERIAL/EQUIPMENT 发货链同抛）；跳过该条目比放行更正确，与未知材质行为对齐
            if (parsed == null || !parsed.isItem()) {
                plugin.getLogger().warning("shop.yml: item '" + id + "' has invalid icon material '"
                        + iconMatStr + "' (unknown or not a carryable item), skipped");
                return null;
            }
            icon = parsed;
            iconConfigured = true;
        }
        // EQUIPMENT 的发货材质就是 icon.material（GUI 图标同）：缺省 BARRIER 会真的把屏障方块
        // 发到玩家手里，属明确误配，跳过整条 —— 这是 round1 起就有的既有契约。
        if ("EQUIPMENT".equals(type) && !iconConfigured) {
            plugin.getLogger().warning("shop.yml: EQUIPMENT item '" + id
                    + "' is missing icon.material (delivery material), skipped");
            return null;
        }
        // fix3 F2 契约回归：MATERIAL 缺 icon.material 时**保留商品**、图标回落 BARRIER，
        // 而不是整条丢弃。丢掉一件商品会让 GUI 少一格，服主只能从启动日志的一行 warning 里
        // 察觉；BARRIER 是游戏内可见的「这一格没配好」提示，且不改变商品清单
        //（既有契约测试 missingIconFallsBackToBarrier 曾钉住这一点，F2 把它改成了反向期望）。
        // 残留缺口：本条仍会把 BARRIER 作为 MATERIAL 的发放物。真正「阻止购买」需要给
        // ShopItem 增加 purchasable 标记并由 ShopGUI 灰显 + ShopListener 拒单，
        // 那是跨 4 个文件的改动，超出本次单文件修复边界。
        if ("MATERIAL".equals(type) && !iconConfigured) {
            plugin.getLogger().warning("shop.yml: MATERIAL item '" + id
                    + "' is missing icon.material; keeping the item with the BARRIER fallback icon."
                    + " Add icon.material to fix its GUI/delivery appearance");
            loadWarnings.add("'" + id + "' has no icon.material (shows a barrier block)");
        }
        if ("EQUIPMENT".equals(type) && !icon.isItem()) {
            plugin.getLogger().warning("shop.yml: EQUIPMENT item '" + id + "' icon material '"
                    + icon.name() + "' is not a carryable item, skipped");
            return null;
        }

        // fix3 F3 契约回归：price 缺键**仍按默认 1 计价**。getInt(path + ".price", 1) 里的默认值
        // 1 是明确的代码意图（注释与出厂模板一致），一个「所有商品都卖 1 个水晶」的极简店铺
        // 是合法配置；把缺键判成非法并丢弃商品，等于把合法配置判成非法。F3 真正该修的是
        //「服主不知道自己漏写了」——所以只补一条 warning，保留商品。
        if (!yaml.isSet(path + ".price")) {
            plugin.getLogger().warning("shop.yml: item '" + id
                    + "' is missing price; defaulting to 1 (add price: to silence this)");
            loadWarnings.add("'" + id + "' has no price (charged 1)");
        }

        // fix4 F4：amount 只在真正使用它的类型上校验。ShopManager#deliverItem 对
        // HAMMER / EQUIPMENT 固定发 1 个（完全忽略 amount），在那两类上写 amount: 0 本来无害，
        // 给它们套 amount 校验会把「无害的冗余键」判成「商品非法」而删掉商品。
        int amount = yaml.getInt(path + ".amount", 1);
        if (USES_AMOUNT.contains(type) && amount <= 0) {
            // deliverItem 会把非正数量钳成 1 发货，购买却按整价扣费——显式 0/负数会让玩家
            // 付全价只拿到 1 个，且无任何提示。这一条是 F4 里方向正确的部分，保留。
            plugin.getLogger().warning("shop.yml: item '" + id + "' has invalid amount " + amount
                    + " (type " + type + " uses amount; must be at least 1), skipped");
            return null;
        }

        int slot = yaml.getInt(path + ".slot", -1);
        if (slot < 0 || slot >= guiSize) {
            // fix5 M-17：1.1.0 新模板按 54 格分区布局（护甲区槽位 27-53）；旧盘若仍是
            // gui.size: 27，按新模板补加的商品会全部落在这里被跳过。warning 补上升级指引，
            // 让「为什么全被跳过」在日志里自答。
            // fix5 锐评 C-18：升级指引仅在 gui.size 确实小于 54 时附加——gui.size 已是 54
            // 却 slot 越界（如 slot 60）是配置写错而非旧盘升级场景，提示旧值只会误导。
            plugin.getLogger().warning("shop.yml: item '" + id + "' slot " + slot
                    + " out of GUI size " + guiSize + ", skipped"
                    + (guiSize < 54 ? " (the full 1.1.0 item layout needs gui.size: 54; check whether gui.size at the top of this file is an old value)" : ""));
            return null;
        }

        String occupiedBy = occupiedSlots.putIfAbsent(slot, id);
        if (occupiedBy != null) {
            // fix5 M-10：槽位冲突会让 ShopGUI 的 setItem/bySlot 静默覆盖（后者胜出），
            // 先到的商品在 GUI 上完全消失且无日志。这里明确告警指出双方 id，
            // 保留两件商品（GUI 最终行为仍为后者覆盖），服主据此修正配置。
            plugin.getLogger().warning("shop.yml: item '" + id + "' slot " + slot
                    + " conflicts with item '" + occupiedBy + "' (later item wins in GUI), please fix slots");
            loadWarnings.add("'" + id + "' and '" + occupiedBy + "' share slot " + slot);
        }

        int price = yaml.getInt(path + ".price", 1);
        // 负数价格会被 removeCurrency 直接判为「无需扣除」（amount <= 0 时返回 true），
        // 于是变成「免费发货」——服主误配 -1 就会白送商品。钳到 0 并明确告警，
        // 让 0（有意免费）与负数（误配）分得开，也不至于让整个条目被 skip 掉。
        if (price < 0) {
            plugin.getLogger().warning("shop.yml: item '" + id + "' has negative price " + price
                    + ", clamped to 0 (free)");
            loadWarnings.add("'" + id + "' had a negative price (now free)");
            price = 0;
        }

        String name = yaml.getString(path + ".name");
        if (name != null && name.isEmpty()) {
            name = null;
        }
        List<String> lore = yaml.getStringList(path + ".lore");
        if (lore.isEmpty()) {
            lore = null;
        }

        // 是否不可破坏（仅 EQUIPMENT 生效；不配置 = 普通耐久）
        boolean unbreakable = yaml.getBoolean(path + ".unbreakable", false);

        return new ShopItem(id, slot, price, type, amount, icon, name, lore, unbreakable);
    }

    /** GUI 尺寸校验：合法值原样返回；非法值钳制到不超过原值的最大档（全小则 9）并 warning */
    private int clampGuiSize(int size) {
        if (VALID_GUI_SIZES.contains(size)) {
            return size;
        }
        int clamped = 9;
        for (int valid : VALID_GUI_SIZES) {
            if (size >= valid) {
                clamped = valid;
            }
        }
        plugin.getLogger().warning("shop.yml: invalid GUI size " + size
                + " (valid: 9/18/27/36/45/54), falling back to " + clamped);
        return clamped;
    }
}
