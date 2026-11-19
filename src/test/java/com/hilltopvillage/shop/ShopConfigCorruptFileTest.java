package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * F1 regression: an unparseable or parseable-truncated {@code shop.yml} must not become a valid empty shop.
 *
 * <p>{@code YamlConfiguration.loadConfiguration(File)} logs and swallows parse errors, returning an
 * empty configuration. Before the fix, {@link ShopConfigLoader#load()} therefore returned a normal
 * {@link ShopConfig} with no items, silently replacing a working shop after {@code /hilltop shop reload}.
 * A truncation can also be syntactically valid: if it ends at the {@code items:} header, Bukkit drops
 * the null value and the key looks omitted. Both forms must fail visibly, and
 * {@link ShopManager#reload()} must leave the previous snapshot untouched when that failure escapes.</p>
 */
class ShopConfigCorruptFileTest extends MockBukkitTestBase {

    /** YAML with an unclosed flow sequence: SnakeYAML must reject this, not parse it as empty. */
    private static final String CORRUPT_SHOP_YML = """
            shop:
              items:
                broken: [unclosed
            """;

    /**
     * Parseable truncation: the {@code items:} key still exists, but its value is null.
     * This is what a write cut off at the end of the items header produces.
     */
    private static final String TRUNCATED_AT_ITEMS = """
            shop:
              currency:
                material: EMERALD
              npc:
                name: "&aShop Keeper"
              gui:
                size: 54
              items:
            """;

    private BareHost host;
    private ShopConfigLoader loader;
    private File shopFile;

    @BeforeEach
    void setUp() {
        host = loadBareHost();
        loader = new ShopConfigLoader(host);
        shopFile = new File(host.getDataFolder(), "shop.yml");
    }

    @Test
    @DisplayName("F1: invalid shop.yml throws a descriptive failure instead of returning an empty ShopConfig")
    void corruptFileDoesNotReturnEmptyShop() throws IOException {
        TestPaths.write(shopFile, CORRUPT_SHOP_YML);

        IllegalStateException failure = assertThrows(IllegalStateException.class, loader::load,
                "A corrupt shop.yml must fail visibly; returning an empty ShopConfig silently replaces "
                        + "a working shop with nothing");

        String message = failure.getMessage();
        assertNotNull(message, "The parse failure must carry a diagnostic message");
        assertTrue(message.contains("shop.yml"),
                "The failure must identify the offending file so the admin can act on it: " + message);
    }

    @Test
    @DisplayName("F1: failed reload keeps the last valid ShopConfig snapshot")
    void corruptReloadKeepsPreviousConfig() throws IOException {
        TestPaths.write(shopFile, """
                shop:
                  currency:
                    material: DIAMOND
                  gui:
                    size: 27
                  items: {}
                """);
        ShopManager shop = new ShopManager(host);
        shop.reload();
        ShopConfig previous = shop.getShopConfig();

        TestPaths.write(shopFile, CORRUPT_SHOP_YML);

        // S09-F1：reload() 不再抛异常（商店是功能不是核心基础设施，坏文件只能让商店降级）。
        // 但「必须失败」的语义没变 —— 返回值变成 false，且旧快照原样保留。
        assertFalse(shop.reload(),
                "Reloading a corrupt shop.yml must report failure instead of replacing the live shop"
                        + " with an empty one (S09-F1: it must not throw out of onEnable either)");
        assertSame(previous, shop.getShopConfig(),
                "A failed load must not assign a new empty config; the last valid snapshot must survive");
        assertSame(Material.DIAMOND, shop.getShopConfig().getCurrencyMaterial(),
                "The preserved snapshot must still be the previously loaded one");
    }

    @Test
    @DisplayName("F1: parseable truncation at the items: header fails and preserves the last snapshot")
    void truncatedItemsHeaderFailsAndPreservesPreviousConfig() throws IOException {
        TestPaths.write(shopFile, """
                shop:
                  currency:
                    material: DIAMOND
                  gui:
                    size: 54
                  items:
                    probe:
                      slot: 0
                      price: 1
                      type: MATERIAL
                      icon:
                        material: BOW
                """);
        ShopManager shop = new ShopManager(host);
        shop.reload();
        ShopConfig previous = shop.getShopConfig();
        assertEquals(1, previous.getItems().size(),
                "The fixture must begin with one valid item so replacement is observable");

        TestPaths.write(shopFile, TRUNCATED_AT_ITEMS);

        IllegalStateException directFailure = assertThrows(IllegalStateException.class, loader::load,
                "A present-but-null shop.items key is a parseable truncation and must not load as empty");
        assertNotNull(directFailure.getMessage(), "The truncated items failure must be descriptive");
        assertTrue(directFailure.getMessage().contains("shop.yml"), directFailure.getMessage());
        assertTrue(directFailure.getMessage().contains("shop.items"), directFailure.getMessage());

        // S09-F1：reload() 改为返回 false（不抛），旧快照必须原样保留。
        assertFalse(shop.reload(),
                "Reloading the truncated shop.yml must report failure instead of replacing the live shop");
        assertSame(previous, shop.getShopConfig(),
                "The previous snapshot must survive when the items header is truncated");
        assertEquals(1, shop.getShopConfig().getItems().size(),
                "The preserved snapshot must still contain its original item");
    }

    @Test
    @DisplayName("F1: explicit empty items mapping and omitted items remain valid")
    void emptyOrOmittedItemsRemainValid() throws IOException {
        TestPaths.write(shopFile, """
                shop:
                  gui:
                    size: 27
                  items: {}
                """);
        assertTrue(loader.load().getItems().isEmpty(),
                "An explicit empty mapping is a legitimate empty shop");

        TestPaths.write(shopFile, """
                shop:
                  gui:
                    size: 27
                """);
        assertTrue(loader.load().getItems().isEmpty(),
                "Omitting items keeps the existing empty-item behavior");
    }
}
