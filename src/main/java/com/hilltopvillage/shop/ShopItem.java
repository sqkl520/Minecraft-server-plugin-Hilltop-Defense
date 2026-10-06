package com.hilltopvillage.shop;

import org.bukkit.Material;

import java.util.List;

/**
 * 村民商店的单个商品定义（来自 shop.yml 的 shop.items.<id> 节点）。
 * name / lore 为服主直接配置的展示文本（可为 null，走 i18n 或默认外观）。
 */
public class ShopItem {

    /** 商品 ID（shop.yml 中 items 下的 key） */
    private final String id;
    /** GUI 中的槽位号 */
    private final int slot;
    /** 价格（货币数量） */
    private final int price;
    /** 商品类型：HAMMER / FIREBALL / REPAIR_ITEM / MATERIAL */
    private final String type;
    /** 一次购买给的数量（HAMMER 固定 1，忽略此值） */
    private final int amount;
    /** GUI 图标材质 */
    private final Material icon;
    /** 商品显示名（可为 null） */
    private final String name;
    /** 商品 lore（可为 null） */
    private final List<String> lore;

    public ShopItem(String id, int slot, int price, String type, int amount,
                    Material icon, String name, List<String> lore) {
        this.id = id;
        this.slot = slot;
        this.price = price;
        this.type = type;
        this.amount = amount;
        this.icon = icon;
        this.name = name;
        this.lore = lore;
    }

    public String getId() { return id; }
    public int getSlot() { return slot; }
    public int getPrice() { return price; }
    public String getType() { return type; }
    public int getAmount() { return amount; }
    public Material getIcon() { return icon; }
    public String getName() { return name; }
    public List<String> getLore() { return lore; }
}
