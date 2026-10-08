package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class NodeListener implements Listener {

    private final HilltopVillagePlugin plugin;
    private final LanguageManager lang;

    public NodeListener(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    // ---- 修复物品参数：实时从 activeConfig 读取，确保命令 / GUI 修改后立即生效（无需重启） ----

    /** 修复物品材质 */
    private Material repairItemMaterial() {
        return plugin.getConfigManager().getActiveConfig().getRepairCostItem();
    }

    /** 每次修复消耗的物品数量 */
    private int repairCostAmount() {
        return plugin.getConfigManager().getActiveConfig().getRepairCostAmount();
    }

    /** 每消耗一个物品恢复的节点生命值 */
    private double repairAmount() {
        return plugin.getConfigManager().getActiveConfig().getRepairAmountPerCost();
    }

    /** 修复物品显示名（配置留空则回退 messages.yml 的 item.repair-cost-name） */
    private String repairItemName() {
        String configuredName = plugin.getConfigManager().getActiveConfig().getRepairCostName();
        return ChatColor.translateAlternateColorCodes('&',
                (configuredName == null || configuredName.isEmpty())
                        ? lang.get("item.repair-cost-name")
                        : configuredName);
    }

    /** 解析方块所在世界的竞技场 GameManager（非竞技场世界返回 null） */
    private GameManager gm(org.bukkit.World world) {
        if (world == null) return null;
        var arena = plugin.getMultiArenaManager().getArenaByWorld(world.getName());
        return arena == null ? null : arena.getGameManager();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getEntity() == null || event.getEntity().getWorld() == null) return;
        GameManager gameManager = gm(event.getEntity().getWorld());
        if (gameManager == null) return;

        List<Block> blocksToRemove = new java.util.ArrayList<>();

        for (Block block : event.blockList()) {
            if (gameManager.getNodeSystem().isNodeBlock(block)) {
                blocksToRemove.add(block);
            }
        }

        event.blockList().removeAll(blocksToRemove);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;

        GameManager gameManager = gm(clickedBlock.getWorld());
        if (gameManager == null) return;

        if (!gameManager.getNodeSystem().isNodeBlock(clickedBlock)) return;

        Player player = event.getPlayer();

        // STORY-1.8：只在对局运行（含休整整备）时允许修节点，等待区/结算期拒绝并提示
        if (!gameManager.isGameRunning()) {
            ItemStack probe = player.getInventory().getItemInMainHand();
            if (isRepairItem(probe)) {
                event.setCancelled(true);
                player.sendMessage(lang.getFor(player, "node.repair-not-running"));
            }
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();

        if (isRepairItem(item)) {
            event.setCancelled(true);

            NodeSystem.NodeData node = gameManager.getNodeSystem().getNodeAt(clickedBlock.getLocation());
            if (node == null) return;

            boolean repaired = gameManager.getNodeSystem().repairNode(
                    clickedBlock.getLocation(), player, repairAmount());

            if (repaired) {
                int cost = repairCostAmount();
                if (item.getAmount() > cost) {
                    item.setAmount(item.getAmount() - cost);
                } else {
                    player.getInventory().setItemInMainHand(null);
                }

                gameManager.getDisplayEntityManager().spawnNodeRepairEffect(
                        clickedBlock.getLocation());
            }
        }
    }

    private boolean isRepairItem(ItemStack item) {
        if (item == null || item.getType() != repairItemMaterial()) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasDisplayName() && meta.getDisplayName().equals(repairItemName());
    }

    public ItemStack createRepairItem(int amount) {
        ItemStack item = new ItemStack(repairItemMaterial(), amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(repairItemName());

        List<String> lore = new java.util.ArrayList<>();
        lore.add(lang.get("node.repair-item-lore1"));
        lore.add(lang.get("node.repair-item-lore2", lang.getLocale(), repairAmount()));
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }
}
