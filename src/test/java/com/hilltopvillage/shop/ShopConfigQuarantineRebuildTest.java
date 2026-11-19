package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * F1 的修复（{@code !loaded.usable()} 时抛 {@link IllegalStateException}）引入的回归：
 * {@link ShopConfigLoader#load()} 第一行对「文件不存在」无条件 {@code saveResource}，
 * 而 {@code YamlFiles.load} 在隔离坏文件时已经把它改名成 {@code shop.yml.corrupt}。
 * 于是「被隔离」与「首次安装」在文件系统层面完全一样 —— 下一次 {@code load()}
 * （同一进程内再 reload，或重启后的首个 load）会静默写回出厂模板，
 * 把服主的商品 / 价格 / currency.name / npc.name / gui.title 全部覆盖。
 *
 * <p>本类钉住三件事，缺一不可：</p>
 * <ol>
 *   <li><b>被隔离后不得重建</b>（同进程第二次 load、以及「重启」＝新 loader 实例）；</li>
 *   <li><b>首次安装仍必须生成模板</b>（防「修过头」把正常路径也打死）；</li>
 *   <li><b>服主修好文件后必须能读</b>（人工修复路径不能被只读闩永久锁死）。</li>
 * </ol>
 *
 * <p>第 1 条与第 2 条的输入在文件系统上都是「{@code shop.yml} 不存在」，
 * 判据必须是可区分的：本实现用 {@code shop.yml.corrupt} 是否存在作为跨进程信号，
 * 用 {@code YamlFileGate} 的本进程只读闩作为同进程信号。</p>
 */
class ShopConfigQuarantineRebuildTest extends MockBukkitTestBase {

    /** SnakeYAML 必然拒绝的形态（未闭合的 flow sequence）。 */
    private static final String CORRUPT_SHOP_YML = """
            shop:
              items:
                broken: [unclosed
            """;

    /** 服主手写的一份合法配置，刻意与出厂模板的货币不同，便于断言「没被覆盖」。 */
    private static final String ADMIN_SHOP_YML = """
            shop:
              currency:
                material: DIAMOND
                name: "&bCustom Coin"
              npc:
                name: "&eCustom Keeper"
              gui:
                title: "&6Custom Shop"
                size: 27
              items:
                admin_probe:
                  slot: 0
                  price: 42
                  type: MATERIAL
                  icon:
                    material: BOW
            """;

    private BareHost host;
    private ShopConfigLoader loader;
    private File shopFile;
    private File corruptFile;

    @BeforeEach
    void setUp() {
        host = loadBareHost();
        loader = new ShopConfigLoader(host);
        shopFile = new File(host.getDataFolder(), "shop.yml");
        corruptFile = new File(host.getDataFolder(), "shop.yml.corrupt");
    }

    // ================================================================ 回归本体

    @Test
    @DisplayName("F1 回归：坏文件被隔离后，同进程第二次 load() 不得写回出厂模板")
    void secondLoadAfterQuarantineDoesNotRebuildFactoryTemplate() throws IOException {
        TestPaths.write(shopFile, CORRUPT_SHOP_YML);

        assertThrows(IllegalStateException.class, loader::load,
                "首次 load 必须失败并触发隔离（F1 的原意：不得降级成空商店）");
        assertFalse(shopFile.exists(), "坏文件必须已被隔离搬走（shop.yml 不应还在原地）");
        assertTrue(corruptFile.exists(), "隔离产物 shop.yml.corrupt 必须存在");

        // ---- 关键：第二次 load。此时 shop.yml 不存在 —— 与「首次安装」无法从文件系统区分 ----
        assertThrows(IllegalStateException.class, loader::load,
                "隔离后的 shop.yml 不存在，但这是「被搬走」而不是「首次安装」；"
                        + "此处若成功返回，说明 saveResource 写回了出厂模板，"
                        + "服主的商品/价格/货币名/NPC 名/GUI 标题已被出厂值覆盖");

        assertFalse(shopFile.exists(),
                "第二次 load 之后 shop.yml 仍不得被写出来 —— 重建出厂模板正是本回归的核心杀伤");
        assertTrue(corruptFile.exists(),
                "隔离产物必须原样保留，供服主对照修复");
    }

    @Test
    @DisplayName("F1 回归：重启（新 loader 实例）后 .corrupt 仍在时，不得写回出厂模板")
    void restartedLoaderDoesNotRebuildFactoryTemplateWhileCorruptSiblingExists() throws IOException {
        TestPaths.write(shopFile, CORRUPT_SHOP_YML);
        assertThrows(IllegalStateException.class, loader::load);
        assertTrue(corruptFile.exists(), "前置条件：隔离产物存在");

        // 新实例 = 新进程（YamlFileGate 的进程内闩全部重置）。跨重启只能靠磁盘信号判定。
        ShopConfigLoader afterRestart = new ShopConfigLoader(host);

        assertThrows(IllegalStateException.class, afterRestart::load,
                "重启后的首个 load 若成功，就是把出厂模板写回了被隔离的 shop.yml —— "
                        + "评审描述的「坏文件 → 插件禁用 + 文件被搬走 + 下次启动静默写回出厂模板」链条最后一步");

        assertFalse(shopFile.exists(), "重启路径同样不得重建出厂模板");
        assertTrue(corruptFile.exists(), "隔离产物必须保留到服主显式处理");
    }

    // ================================================================ 防「修过头」

    @Test
    @DisplayName("首次安装（无 shop.yml、无 .corrupt）仍必须写出出厂模板并解析成功")
    void firstInstallStillWritesFactoryTemplate() {
        assertFalse(shopFile.exists(), "前置条件：全新数据目录");
        assertFalse(corruptFile.exists(), "前置条件：没有隔离产物");

        ShopConfig config = loader.load();

        assertTrue(shopFile.exists(),
                "首次安装必须生成 shop.yml；否则商店在全新服务器上直接不可用");
        assertFalse(corruptFile.exists(), "正常路径不得凭空产生隔离产物");
        assertEquals(Material.EMERALD, config.getCurrencyMaterial());
        assertEquals(54, config.getGuiSize());
        assertEquals(16, config.getItems().size(),
                "出厂模板共 16 件商品；数量不符说明读到的不是模板："
                        + config.getItems().stream().map(ShopItem::getId).toList());
    }

    @Test
    @DisplayName("服主显式删掉 .corrupt 后重启，允许重置出厂模板（逃生口，不能被保护逻辑堵死）")
    void deletingCorruptSiblingAllowsFactoryResetAfterRestart() throws IOException {
        TestPaths.write(shopFile, CORRUPT_SHOP_YML);
        assertThrows(IllegalStateException.class, loader::load);
        assertTrue(corruptFile.exists());

        // 服主认账：「我就是要一份干净的 shop.yml」。删掉隔离产物 + 重启即是显式同意。
        // 必须用新实例：YamlFileGate 的进程内闩刻意不自动解封（同 ArenaManager 的约定），
        // 所以「删 .corrupt 但不重启」不会立刻放行——这是有意的，见类注释的风险说明。
        assertTrue(corruptFile.delete(), "测试前置：删除隔离产物失败");
        ShopConfigLoader afterRestart = new ShopConfigLoader(host);

        ShopConfig config = afterRestart.load();

        assertTrue(shopFile.exists(), "没有隔离产物时，「文件不存在」重新等于「首次安装」，必须重建");
        assertEquals(16, config.getItems().size(), "重建的必须是完整出厂模板");
    }

    @Test
    @DisplayName("同进程内删掉 .corrupt 但不重启：闸门仍拒绝重建（隔离不得被删文件动作绕过）")
    void deletingCorruptSiblingWithoutRestartStillRefusesRebuild() throws IOException {
        TestPaths.write(shopFile, CORRUPT_SHOP_YML);
        assertThrows(IllegalStateException.class, loader::load);
        assertTrue(corruptFile.delete(), "测试前置：删除隔离产物失败");

        assertThrows(IllegalStateException.class, loader::load,
                "闸门的「ABSENT 不解除锁定」是本回归的最后一道防线：删掉 .corrupt 不能等于同意覆盖");
        assertFalse(shopFile.exists(), "未重启时同样不得写回出厂模板");
    }

    // ================================================================ 人工修复路径

    @Test
    @DisplayName("隔离后服主手写回合法 shop.yml：load() 必须成功，且不得被模板覆盖")
    void adminRepairedFileLoadsAfterQuarantine() throws IOException {
        TestPaths.write(shopFile, CORRUPT_SHOP_YML);
        assertThrows(IllegalStateException.class, loader::load);
        assertTrue(corruptFile.exists(), "前置条件：坏文件已被隔离");

        // 服主从 .corrupt 取回内容、修好后放回 shop.yml（.corrupt 留在原地也无妨）
        TestPaths.write(shopFile, ADMIN_SHOP_YML);

        ShopConfig config = loader.load();

        assertEquals(Material.DIAMOND, config.getCurrencyMaterial(),
                "修好的文件必须被真的读进来，而不是被出厂模板（EMERALD）顶掉");
        assertEquals("&bCustom Coin", config.getCurrencyName(), "currency.name 必须保留");
        assertEquals("&eCustom Keeper", config.getNpcName(), "npc.name 必须保留");
        assertEquals("&6Custom Shop", config.getGuiTitle(), "gui.title 必须保留");
        assertEquals(1, config.getItems().size(),
                "服主自己的商品必须还在；16 件说明读的是出厂模板");
        assertEquals("admin_probe", config.getItems().get(0).getId());
        assertEquals(42, config.getItems().get(0).getPrice());
        assertEquals(ADMIN_SHOP_YML, TestPaths.read(shopFile),
                "load() 是只读路径，不得改写服主刚修好的文件");
    }

    @Test
    @DisplayName("修好后再被改坏：第二次隔离仍然生效，且仍不重建模板")
    void secondQuarantineStillThrowsAndStillDoesNotRebuild() throws IOException {
        TestPaths.write(shopFile, CORRUPT_SHOP_YML);
        assertThrows(IllegalStateException.class, loader::load);

        TestPaths.write(shopFile, ADMIN_SHOP_YML);
        assertEquals(Material.DIAMOND, loader.load().getCurrencyMaterial(),
                "前置条件：修好的文件必须先读成功（同时验证只读闩会被 OK 读盘解开）");

        TestPaths.write(shopFile, CORRUPT_SHOP_YML);
        assertThrows(IllegalStateException.class, loader::load, "再次被改坏必须再次失败");
        assertTrue(corruptFile.exists(), "第二次隔离同样要留下 .corrupt");
        assertFalse(shopFile.exists(), "第二次隔离会把 shop.yml 再次搬走");

        assertThrows(IllegalStateException.class, loader::load,
                "第二次隔离后的 load 同样不得重建模板");
        assertFalse(shopFile.exists(), "第二次隔离后也不得写回出厂模板");
    }

    // ================================================================ 诊断信息

    @Test
    @DisplayName("隔离态下的失败必须点名 shop.yml.corrupt 并给出恢复指引")
    void quarantineFailureMessageNamesTheQuarantineFile() throws IOException {
        TestPaths.write(shopFile, CORRUPT_SHOP_YML);
        assertThrows(IllegalStateException.class, loader::load);

        IllegalStateException failure = assertThrows(IllegalStateException.class, loader::load,
                "隔离态下继续拒绝加载");

        String message = failure.getMessage();
        assertNotNull(message, "失败必须带诊断信息");
        assertTrue(message.contains("shop.yml"), "必须点名文件：" + message);
        assertTrue(message.contains("corrupt"),
                "必须告诉服主现场已被搬到 shop.yml.corrupt，否则他会在数据目录里找不到原始配置：" + message);
    }
}
