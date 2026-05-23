package com.hilltopvillage.mechanics;

import com.hilltopvillage.core.GameManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class NodeListener implements Listener {

    private final GameManager gameManager;
    private final String repairItemName;
    private final Material repairItemMaterial;
    private final int repairCostAmount;
    private final double repairAmount;

    public NodeListener(GameManager gameManager) {
        this.gameManager = gameManager;
        this.repairItemName = ChatColor.translateAlternateColorCodes('&',
                gameManager.getPlugin().getConfig().getString("nodes.repair-cost-name", "&aWorld Tree Sap"));
        this.repairItemMaterial = Material.valueOf(
                gameManager.getPlugin().getConfig().getString("nodes.repair-cost-item", "SLIME_BALL"));
        this.repairCostAmount = gameManager.getPlugin().getConfig().getInt("nodes.repair-cost-amount", 1);
        this.repairAmount = gameManager.getPlugin().getConfig().getDouble("nodes.repair-amount-per-cost", 50.0);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!gameManager.isGameRunning()) return;

        List<Block> blocksToRemove = new java.util.ArrayList<>();

        for (Block block : event.blockList()) {
            if (gameManager.getNodeSystem().isNodeBlock(block)) {
                blocksToRemove.add(block);
            }
        }

        event.blockList().removeAll(blocksToRemove);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNodeDamage(EntityDamageByEntityEvent event) {
        if (!gameManager.isGameRunning()) return;

        if (!(event.getEntity() instanceof org.bukkit.entity.Item)) return;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!gameManager.isGameRunning()) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;

        if (!gameManager.getNodeSystem().isNodeBlock(clickedBlock)) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (isRepairItem(item)) {
            event.setCancelled(true);

            NodeSystem.NodeData node = gameManager.getNodeSystem().getNodeAt(clickedBlock.getLocation());
            if (node == null) return;

            boolean repaired = gameManager.getNodeSystem().repairNode(
                    clickedBlock.getLocation(), player, repairAmount);

            if (repaired) {
                if (item.getAmount() > repairCostAmount) {
                    item.setAmount(item.getAmount() - repairCostAmount);
                } else {
                    player.getInventory().setItemInMainHand(null);
                }

                gameManager.getDisplayEntityManager().spawnNodeRepairEffect(
                        clickedBlock.getLocation());
            }
        }
    }

    private boolean isRepairItem(ItemStack item) {
        if (item == null || item.getType() != repairItemMaterial) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasDisplayName() && meta.getDisplayName().equals(repairItemName);
    }

    public ItemStack createRepairItem(int amount) {
        ItemStack item = new ItemStack(repairItemMaterial, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(repairItemName);

        List<String> lore = new java.util.ArrayList<>();
        lore.add(ChatColor.GRAY + "右键能量节点来修复它");
        lore.add(ChatColor.GREEN + "恢复 " + repairAmount + " 点生命值");
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }
}
