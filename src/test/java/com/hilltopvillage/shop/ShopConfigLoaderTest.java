package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link ShopConfigLoader} 的解析契约 —— {@code shop.yml} 里每一条「配错了会静默毁掉商店」的路径。
 *
 * <p><b>为什么这个类值得逐条钉</b>：它不是「读几个字符串」那么无害。商品的
 * {@code type} / {@code icon.material} / {@code slot} 三者任意一个非法，后果都<b>不是抛异常</b>，
 * 而是「该商品从 GUI 里消失」或「整个商店 GUI 打不开」——两者在日志里都只有一行 warning，
 * 服主关掉日志就永远查不到。round1 / fix6 在这条链上改过三处：</p>
 *
 * <ul>
 *   <li><b>fix6 Y2-06（货币侧）</b>：{@code shop.currency.material} 配成非 item 材质（如 {@code WATER}）
 *       会让 {@code ShopManager#createCurrencyItem} 的 {@code new ItemStack} 抛异常 ——
 *       而该工厂同时服务「击杀掉币」与「购买退款」两条链，等于把两条链一起打死。
 *       修复是「非 item 一律回落到 EMERALD」，本类 {@link Currency#nonItemCurrencyMaterialFallsBackToEmerald} 钉住它。</li>
 *   <li><b>fix6 Y2-06（图标侧）</b>：{@code icon.material} 配成非 item 材质会放行到
 *       {@code ShopGUI} 的 {@code new ItemStack(icon)} 才抛，那时整个 GUI 打不开
 *       （MATERIAL / EQUIPMENT 的发货链同抛）。修复是「跳过该条目」。
 *       见 {@link Icons#nonItemIconMaterialSkipsTheItem}。</li>
 *   <li><b>fix5 M-10（槽位冲突）</b>：两个商品配同一 slot 时 {@code ShopGUI} 的 {@code setItem}
 *       是后者覆盖前者，先到的商品在 GUI 上完全消失且<b>零日志</b>。修复是加载期明确告警并点名双方 id。
 *       见 {@link Slots#duplicateSlotKeepsBothItemsButWarns}。</li>
 * </ul>
 *
 * <p><b>夹具代价</b>：{@code BareHost} 一次约 1 秒（{@code saveResource} + i18n），
 * 故本类把十余条断言压进一个 {@code @BeforeEach} 共享宿主，只在该批次要换配置时重写 {@code shop.yml}。</p>
 */
class ShopConfigLoaderTest extends MockBukkitTestBase {

    private BareHost host;
    private ShopConfigLoader loader;

    @BeforeEach
    void setUp() {
        host = loadBareHost();
        loader = new ShopConfigLoader(host);
    }

    // ---------------------------------------------------------------- 夹具

    /** 把给定 YAML 写进宿主数据目录，再走一次真实的 {@link ShopConfigLoader#load()}。 */
    private ShopConfig load(String yaml) throws IOException {
        TestPaths.write(new File(host.getDataFolder(), "shop.yml"), yaml);
        return loader.load();
    }

    private static ShopItem byId(ShopConfig config, String id) {
        Map<String, ShopItem> byId = config.getItems().stream()
                .collect(Collectors.toMap(ShopItem::getId, Function.identity()));
        return byId.get(id);
    }

    /** 一个只有单件商品的极简配置骨架，便于逐条替换其中的某一键。 */
    private static String singleItem(String itemBody) {
        return """
                shop:
                  currency:
                    material: EMERALD
                    name: "&aCrystal"
                  gui:
                    size: 54
                  items:
                    probe:
                """ + indent(itemBody, "      ");
    }

    private static String indent(String text, String prefix) {
        return text.lines().map(line -> prefix + line).collect(Collectors.joining("\n")) + "\n";
    }

    // ================================================================ 出厂模板

    @Test
    @DisplayName("出厂 shop.yml 能完整解析：16 件商品 / EMERALD 货币 / 54 格 GUI")
    void factoryTemplateParsesCleanly() {
        // 不写文件 —— 让 load() 自己 saveResource("shop.yml")，测的就是要发出去的那份模板。
        ShopConfig config = loader.load();

        assertEquals(Material.EMERALD, config.getCurrencyMaterial(),
                "出厂模板的货币材质是 EMERALD，解析结果必须一致");
        assertEquals(54, config.getGuiSize(),
                "1.1.0 的 54 格分区布局（护甲区在 27-53）依赖 gui.size: 54；读到 27 说明模板或解析退化了");
        assertEquals("&a能量水晶", config.getCurrencyName(),
                "出厂模板直配了货币名，应当原样读入（颜色码此时不翻译，留给 ShopManager 处理）");

        assertEquals(16, config.getItems().size(),
                "出厂模板共 16 件商品（5 消耗品 + 3 武器 + 4 铁甲 + 4 钻石甲）；"
                        + "少一件就意味着有商品被静默跳过，实际解析：" + config.getItems().stream()
                        .map(ShopItem::getId).toList());

        ShopItem bow = byId(config, "bow");
        assertNotNull(bow, "出厂模板里的 bow 必须被解析出来");
        assertEquals(0, bow.getSlot());
        assertEquals(15, bow.getPrice());
        assertEquals("MATERIAL", bow.getType());
        assertEquals(Material.BOW, bow.getIcon());
        assertEquals(1, bow.getAmount(), "未写 amount 时应取默认值 1");
        assertFalse(bow.isUnbreakable(), "未写 unbreakable 时应为 false（普通耐久）");

        ShopItem arrow = byId(config, "arrow");
        assertEquals(8, arrow.getAmount(), "arrow 显式配置了 amount: 8");

        ShopItem sword = byId(config, "netherite_sword");
        assertNotNull(sword, "EQUIPMENT 线（下界合金剑）必须被解析出来");
        assertEquals("EQUIPMENT", sword.getType());
        assertNotNull(sword.getLore(), "模板给了 lore，解析结果不应为 null");
        assertFalse(sword.getLore().isEmpty());
    }

    @Test
    @DisplayName("getItems() 返回不可变视图 —— 外部无法绕过加载器往商店里塞商品")
    void itemsListIsUnmodifiable() {
        ShopConfig config = loader.load();

        assertThrows(UnsupportedOperationException.class, () -> config.getItems().clear(),
                "getItems() 必须是不可变视图；否则 ShopGUI 的 bySlot 与配置会失去同步");
    }

    @Test
    @DisplayName("ShopConfig 构造器拷贝入参 —— 调用方事后改原 list 不能影响快照")
    void shopConfigCopiesItemList() {
        ShopItem item = new ShopItem("a", 0, 1, "MATERIAL", 1, Material.STONE, null, null, false);
        java.util.List<ShopItem> mutable = new java.util.ArrayList<>(List.of(item));

        ShopConfig config = new ShopConfig(Material.EMERALD, null, null, null, 54, mutable);
        mutable.clear();

        assertEquals(1, config.getItems().size(),
                "ShopConfig 构造期已 ArrayList 拷贝；若这里变 0，说明改成了直接持有入参引用");
    }

    // ================================================================ 货币

    @Nested
    @DisplayName("currency 段")
    class Currency {

        @Test
        @DisplayName("fix6 Y2-06：非 item 货币材质（WATER）回落到 EMERALD 并告警")
        void nonItemCurrencyMaterialFallsBackToEmerald() throws IOException {
            LogCapture logs = attachLogCapture(host.getLogger());
            ShopConfig config = load("""
                    shop:
                      currency:
                        material: WATER
                      gui:
                        size: 54
                    """);

            assertEquals(Material.EMERALD, config.getCurrencyMaterial(),
                    "WATER 不是可携带物品，new ItemStack(WATER, n) 会抛 IllegalArgumentException。"
                            + "加载器必须回落到 EMERALD —— 否则击杀掉币与购买退款两条链同时打死");
            assertTrue(logs.anyContains("invalid currency material 'WATER'"),
                    "回落必须是可观测的，否则服主永远不知道自己配错了。实际日志：" + logs.lines());
        }

        @Test
        @DisplayName("未知货币材质名同样回落到 EMERALD")
        void unknownCurrencyMaterialFallsBackToEmerald() throws IOException {
            LogCapture logs = attachLogCapture(host.getLogger());
            ShopConfig config = load("""
                    shop:
                      currency:
                        material: NOT_A_REAL_MATERIAL
                    """);

            assertEquals(Material.EMERALD, config.getCurrencyMaterial());
            assertTrue(logs.anyContains("invalid currency material"),
                    "未知材质名也必须留下日志：" + logs.lines());
        }

        @Test
        @DisplayName("合法货币材质原样采用（不是永远返回 EMERALD）")
        void validCurrencyMaterialIsHonored() throws IOException {
            ShopConfig config = load("""
                    shop:
                      currency:
                        material: DIAMOND
                    """);

            assertEquals(Material.DIAMOND, config.getCurrencyMaterial(),
                    "合法值必须原样采用 —— 否则上面两条回落断言在「永远返回 EMERALD」的实现下也会绿");
        }

        @Test
        @DisplayName("空字符串货币名归一化为 null（交给 i18n 回退）")
        void blankCurrencyNameBecomesNull() throws IOException {
            ShopConfig config = load("""
                    shop:
                      currency:
                        name: ""
                    """);

            assertNull(config.getCurrencyName(),
                    "空串必须归一化为 null —— ShopManager 用 `configured != null && !isEmpty()` 判断，"
                            + "留空串虽然也能走到 i18n，但契约上应统一为 null");
        }

        @Test
        @DisplayName("货币段整体缺失时取默认 EMERALD，且不抛异常")
        void missingCurrencySectionUsesDefault() throws IOException {
            ShopConfig config = load("""
                    shop:
                      gui:
                        size: 27
                    """);

            assertEquals(Material.EMERALD, config.getCurrencyMaterial());
            assertNull(config.getCurrencyName(), "没有 currency.name 时应为 null");
        }
    }

    // ================================================================ GUI

    @Nested
    @DisplayName("gui.size 钳制")
    class GuiSize {

        @Test
        @DisplayName("六个合法值原样通过")
        void validSizesPassThrough() throws IOException {
            for (int size : List.of(9, 18, 27, 36, 45, 54)) {
                ShopConfig config = load("shop:\n  gui:\n    size: " + size + "\n");
                assertEquals(size, config.getGuiSize(), "合法 GUI 尺寸 " + size + " 必须原样通过");
            }
        }

        @Test
        @DisplayName("非法尺寸钳到不超过原值的最大合法档（30 → 27，5 → 9）")
        void invalidSizesClampDown() throws IOException {
            assertEquals(27, load("shop:\n  gui:\n    size: 30\n").getGuiSize(),
                    "30 必须钳到 27（不能向上钳到 36 —— 那会让商品被塞进玩家看不见的行）");
            assertEquals(9, load("shop:\n  gui:\n    size: 5\n").getGuiSize(),
                    "小于 9 的尺寸没有更小的合法档，钳到 9");
            assertEquals(54, load("shop:\n  gui:\n    size: 60\n").getGuiSize(),
                    "超过 54 钳到 54（Bukkit 箱子最大 6 行）");
        }

        @Test
        @DisplayName("gui 段缺失时默认 27")
        void missingGuiSizeDefaultsTo27() throws IOException {
            assertEquals(27, load("shop:\n  currency:\n    material: EMERALD\n").getGuiSize());
        }

        @Test
        @DisplayName("钳制会留下 warning（含合法值清单）")
        void clampingWarns() throws IOException {
            LogCapture logs = attachLogCapture(host.getLogger());
            load("shop:\n  gui:\n    size: 30\n");

            assertTrue(logs.anyContains("invalid GUI size 30"),
                    "钳制必须告警，否则「为什么我的商品全被跳过」无从排查：" + logs.lines());
            assertTrue(logs.anyContains("9/18/27/36/45/54"),
                    "告警应列出合法值：" + logs.lines());
        }
    }

    // ================================================================ 商品类型

    @Nested
    @DisplayName("type 校验")
    class Types {

        @Test
        @DisplayName("未知 type 的条目被跳过并告警")
        void unknownTypeIsSkipped() throws IOException {
            LogCapture logs = attachLogCapture(host.getLogger());
            ShopConfig config = load(singleItem("""
                    slot: 0
                    price: 1
                    type: WEAPON
                    icon:
                      material: BOW
                    """));

            assertTrue(config.getItems().isEmpty(),
                    "type: WEAPON 不在 HAMMER/FIREBALL/REPAIR_ITEM/MATERIAL/EQUIPMENT 里，必须整条跳过");
            assertTrue(logs.anyContains("unknown type 'WEAPON'"),
                    "跳过必须点名类型，否则服主不知道是自己写错了还是插件坏了：" + logs.lines());
        }

        @Test
        @DisplayName("缺 type 键的条目被跳过（不会当成默认类型放行）")
        void missingTypeIsSkipped() throws IOException {
            ShopConfig config = load(singleItem("""
                    slot: 0
                    price: 1
                    icon:
                      material: BOW
                    """));

            assertTrue(config.getItems().isEmpty(),
                    "type 缺省是空串，不在 VALID_TYPES 里 —— 必须跳过，而不是按 MATERIAL 兜底");
        }

        @Test
        @DisplayName("五种合法 type 全部被接受")
        void allValidTypesAccepted() throws IOException {
            ShopConfig config = load("""
                    shop:
                      gui:
                        size: 54
                      items:
                        a:
                          slot: 0
                          price: 1
                          type: HAMMER
                          icon: { material: NETHERITE_AXE }
                        b:
                          slot: 1
                          price: 1
                          type: FIREBALL
                          icon: { material: FIRE_CHARGE }
                        c:
                          slot: 2
                          price: 1
                          type: REPAIR_ITEM
                          icon: { material: GHAST_TEAR }
                        d:
                          slot: 3
                          price: 1
                          type: MATERIAL
                          icon: { material: ARROW }
                        e:
                          slot: 4
                          price: 1
                          type: EQUIPMENT
                          icon: { material: IRON_SWORD }
                    """);

            assertEquals(5, config.getItems().size(),
                    "五种合法 type 必须全部通过；实际：" + config.getItems().stream()
                            .map(ShopItem::getId).toList());
        }
    }

    // ================================================================ 图标

    @Nested
    @DisplayName("icon.material 校验")
    class Icons {

        @Test
        @DisplayName("fix6 Y2-06：非 item 图标材质（WATER）跳过整条商品")
        void nonItemIconMaterialSkipsTheItem() throws IOException {
            LogCapture logs = attachLogCapture(host.getLogger());
            ShopConfig config = load(singleItem("""
                    slot: 0
                    price: 1
                    type: MATERIAL
                    icon:
                      material: WATER
                    """));

            assertTrue(config.getItems().isEmpty(),
                    "放行 WATER 会让 ShopGUI 的 new ItemStack(WATER) 先抛 —— 那时整个商店 GUI 都打不开，"
                            + "比跳过一件商品严重得多");
            assertTrue(logs.anyContains("invalid icon material 'WATER'"),
                    "跳过必须点名材质：" + logs.lines());
        }

        @Test
        @DisplayName("未知图标材质名同样跳过")
        void unknownIconMaterialSkipsTheItem() throws IOException {
            ShopConfig config = load(singleItem("""
                    slot: 0
                    price: 1
                    type: MATERIAL
                    icon:
                        material: TOTALLY_NOT_A_MATERIAL
                    """));
            assertTrue(config.getItems().isEmpty());
        }

        @Test
        @DisplayName("合法图标材质原样采用")
        void validIconMaterialIsHonored() throws IOException {
            ShopConfig config = load(singleItem("""
                    slot: 0
                    price: 1
                    type: MATERIAL
                    icon:
                      material: DIAMOND
                    """));

            assertEquals(1, config.getItems().size());
            assertEquals(Material.DIAMOND, config.getItems().get(0).getIcon(),
                    "合法值必须原样采用，否则上面两条「跳过」断言在「永远跳过」的实现下也会绿");
        }

        @Test
        @DisplayName("缺 icon 段的非 EQUIPMENT 条目回落到 BARRIER 图标（保留商品）")
        void missingIconFallsBackToBarrier() throws IOException {
            ShopConfig config = load(singleItem("""
                    slot: 0
                    price: 1
                    type: MATERIAL
                    """));

            assertEquals(1, config.getItems().size(),
                    "非 EQUIPMENT 条目缺图标时只是外观问题，不应整条丢掉");
            assertEquals(Material.BARRIER, config.getItems().get(0).getIcon(),
                    "缺省图标是 BARRIER（明确可见的「没配好」提示，而不是 AIR 隐形槽位）");
        }

        @Test
        @DisplayName("EQUIPMENT 缺 icon.material 被跳过（发货材质会变成屏障方块）")
        void equipmentWithoutIconIsSkipped() throws IOException {
            LogCapture logs = attachLogCapture(host.getLogger());
            ShopConfig config = load(singleItem("""
                    slot: 0
                    price: 1
                    type: EQUIPMENT
                    """));

            assertTrue(config.getItems().isEmpty(),
                    "EQUIPMENT 的发货材质就是 icon.material，缺省会发出屏障方块 —— 属明确误配，必须跳过");
            assertTrue(logs.anyContains("is missing icon.material"),
                    "跳过必须说明原因：" + logs.lines());
        }
    }

    // ================================================================ 槽位

    @Nested
    @DisplayName("slot 校验")
    class Slots {

        @Test
        @DisplayName("槽位越界（>= gui.size）的条目被跳过")
        void slotBeyondGuiSizeIsSkipped() throws IOException {
            LogCapture logs = attachLogCapture(host.getLogger());
            ShopConfig config = load(singleItem("""
                    slot: 54
                    price: 1
                    type: MATERIAL
                    icon:
                      material: BOW
                    """).replace("size: 54", "size: 27"));

            assertTrue(config.getItems().isEmpty(),
                    "slot 54 在 27 格 GUI 里越界 —— setItem 会抛 IndexOutOfBounds");
            assertTrue(logs.anyContains("out of GUI size 27"),
                    "跳过必须点名 GUI 尺寸：" + logs.lines());
            assertTrue(logs.anyContains("gui.size: 54"),
                    "gui.size 小于 54 时要附加「1.1.0 布局需要 54」的升级指引（fix5 M-17）");
        }

        @Test
        @DisplayName("负槽位被跳过")
        void negativeSlotIsSkipped() throws IOException {
            ShopConfig config = load(singleItem("""
                    price: 1
                    type: MATERIAL
                    icon:
                      material: BOW
                    """));
            assertTrue(config.getItems().isEmpty(), "缺 slot 键时默认 -1，必须跳过");
        }

        @Test
        @DisplayName("gui.size 已是 54 而槽位仍越界时，不再附加旧盘升级指引（避免误导）")
        void upgradeHintOmittedWhenGuiSizeIsAlready54() throws IOException {
            LogCapture logs = attachLogCapture(host.getLogger());
            load(singleItem("""
                    slot: 60
                    price: 1
                    type: MATERIAL
                    icon:
                      material: BOW
                    """));

            assertTrue(logs.anyContains("out of GUI size 54"),
                    "越界本身必须告警：" + logs.lines());
            assertFalse(logs.anyContains("gui.size: 54; check"),
                    "gui.size 已是 54 却 slot 越界是配置写错，不是旧盘升级场景；"
                            + "再提示「检查 gui.size」只会把服主往错方向带（fix5 锐评 C-18）");
        }

        @Test
        @DisplayName("fix5 M-10：两个商品配同一槽位时两件都保留，且告警点名双方 id")
        void duplicateSlotKeepsBothItemsButWarns() throws IOException {
            LogCapture logs = attachLogCapture(host.getLogger());
            ShopConfig config = load("""
                    shop:
                      gui:
                        size: 54
                      items:
                        first:
                          slot: 3
                          price: 1
                          type: MATERIAL
                          icon: { material: BOW }
                        second:
                          slot: 3
                          price: 2
                          type: MATERIAL
                          icon: { material: ARROW }
                    """);

            assertEquals(2, config.getItems().size(),
                    "冲突时不得丢弃任何一件 —— round1 的判定是「保留两件 + 告警」，"
                            + "GUI 的最终行为仍是后者覆盖（这一点由告警告知服主）");
            assertTrue(logs.anyContains("conflicts with item 'first'"),
                    "告警必须点名先到者，否则服主只知道有冲突、不知道和谁冲突：" + logs.lines());
            assertTrue(logs.anyContains("item 'second' slot 3"),
                    "告警必须点名后到者：" + logs.lines());
        }

        @Test
        @DisplayName("不同槽位不触发冲突告警（控制半：证明上面的告警不是恒真）")
        void distinctSlotsDoNotWarn() throws IOException {
            LogCapture logs = attachLogCapture(host.getLogger());
            load("""
                    shop:
                      gui:
                        size: 54
                      items:
                        first:
                          slot: 3
                          price: 1
                          type: MATERIAL
                          icon: { material: BOW }
                        second:
                          slot: 4
                          price: 2
                          type: MATERIAL
                          icon: { material: ARROW }
                    """);

            assertFalse(logs.anyContains("conflicts with item"),
                    "不同槽位绝不应报冲突 —— 否则上面那条测试在「无条件告警」的实现下也会绿："
                            + logs.lines());
        }
    }

    // ================================================================ 价格 / 数量 / 文本

    @Nested
    @DisplayName("price / amount / name / lore / unbreakable")
    class Values {

        @Test
        @DisplayName("负价格被钳到 0 并告警（否则 removeCurrency 直接放行 = 免费发货）")
        void negativePriceIsClampedToZero() throws IOException {
            LogCapture logs = attachLogCapture(host.getLogger());
            ShopConfig config = load(singleItem("""
                    slot: 0
                    price: -5
                    type: MATERIAL
                    icon:
                      material: BOW
                    """));

            assertEquals(1, config.getItems().size(),
                    "负价格是「误配」而不是「这一条无效」—— 钳到 0 保留商品，不整条跳过");
            assertEquals(0, config.getItems().get(0).getPrice(),
                    "必须钳到 0：ShopManager#removeCurrency 对 amount <= 0 直接返回 true 且不扣币，"
                            + "留负数等于白送商品");
            assertTrue(logs.anyContains("negative price -5"),
                    "钳制必须告警，并说明被当成「免费」处理：" + logs.lines());
        }

        @Test
        @DisplayName("缺 price 键默认 1")
        void missingPriceDefaultsToOne() throws IOException {
            ShopConfig config = load(singleItem("""
                    slot: 0
                    type: MATERIAL
                    icon:
                      material: BOW
                    """));
            assertEquals(1, config.getItems().get(0).getPrice());
        }

        @Test
        @DisplayName("price: 0 合法（有意免费），且不触发负价格告警")
        void zeroPriceIsLegalWithoutWarning() throws IOException {
            LogCapture logs = attachLogCapture(host.getLogger());
            ShopConfig config = load(singleItem("""
                    slot: 0
                    price: 0
                    type: MATERIAL
                    icon:
                      material: BOW
                    """));

            assertEquals(0, config.getItems().get(0).getPrice(),
                    "0 价是「有意免费」，与「负数误配」必须分得开");
            assertFalse(logs.anyContains("negative price"),
                    "0 不是负数，不应告警：" + logs.lines());
        }

        @Test
        @DisplayName("空 name 归一化为 null，空 lore 列表归一化为 null")
        void blankTextBecomesNull() throws IOException {
            ShopConfig config = load(singleItem("""
                    slot: 0
                    price: 1
                    type: MATERIAL
                    name: ""
                    lore: []
                    icon:
                      material: BOW
                    """));

            ShopItem item = config.getItems().get(0);
            assertNull(item.getName(), "空 name 归一化为 null，让 ShopGUI / ShopListener 走 i18n 回退");
            assertNull(item.getLore(), "空 lore 归一化为 null，而不是空列表");
        }

        @Test
        @DisplayName("name / lore / unbreakable 原样读入")
        void textAndUnbreakableAreReadVerbatim() throws IOException {
            ShopConfig config = load(singleItem("""
                    slot: 0
                    price: 7
                    amount: 3
                    type: EQUIPMENT
                    name: "&bDiamond Blade"
                    lore:
                      - "&7line one"
                      - "&7line two"
                    unbreakable: true
                    icon:
                      material: DIAMOND_SWORD
                    """));

            ShopItem item = config.getItems().get(0);
            assertEquals("&bDiamond Blade", item.getName(), "颜色码此时不翻译，原样存下");
            assertEquals(List.of("&7line one", "&7line two"), item.getLore());
            assertTrue(item.isUnbreakable(), "unbreakable: true 必须读进 ShopItem");
            assertEquals(3, item.getAmount());
            assertEquals(7, item.getPrice());
            assertEquals(Material.DIAMOND_SWORD, item.getIcon());
        }
    }

    // ================================================================ 重载

    @Test
    @DisplayName("同一个 loader 连续 load 两次：槽位占用表被清空，第二次不会误报冲突")
    void repeatedLoadClearsOccupiedSlots() throws IOException {
        String yaml = singleItem("""
                slot: 0
                price: 1
                type: MATERIAL
                icon:
                  material: BOW
                """);
        load(yaml);

        LogCapture logs = attachLogCapture(host.getLogger());
        ShopConfig second = loader.load();

        assertEquals(1, second.getItems().size());
        assertFalse(logs.anyContains("conflicts with item"),
                "occupiedSlots 是实例字段，load() 开头必须 clear() —— 不清的话第二次加载会把"
                        + "同一件商品和自己判成槽位冲突：" + logs.lines());
    }
}
