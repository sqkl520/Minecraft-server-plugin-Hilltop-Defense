package com.hilltopvillage.shop;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 村民商店聚合配置（货币 / NPC / GUI / 商品列表）。
 * 由 ShopConfigLoader 从 shop.yml 解析而来，只读快照。
 */
public class ShopConfig {

    /** 货币物品材质 */
    private final Material currencyMaterial;
    /** 货币显示名（shop.yml 直配文本，null = 走 i18n 的 shop.currency.name） */
    private final String currencyName;
    /** 商店村民头顶名称（支持颜色码） */
    private final String npcName;
    /** GUI 标题（shop.yml 直配文本，null = 走 i18n） */
    private final String guiTitle;
    /** GUI 尺寸（9/18/27/36/45/54） */
    private final int guiSize;
    /** 商品列表 */
    private final List<ShopItem> items;

    public ShopConfig(Material currencyMaterial, String currencyName, String npcName,
                      String guiTitle, int guiSize, List<ShopItem> items) {
        this.currencyMaterial = currencyMaterial;
        this.currencyName = currencyName;
        this.npcName = npcName;
        this.guiTitle = guiTitle;
        this.guiSize = guiSize;
        this.items = new ArrayList<>(items);
    }

    public Material getCurrencyMaterial() { return currencyMaterial; }
    public String getCurrencyName() { return currencyName; }
    public String getNpcName() { return npcName; }
    public String getGuiTitle() { return guiTitle; }
    public int getGuiSize() { return guiSize; }

    public List<ShopItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
