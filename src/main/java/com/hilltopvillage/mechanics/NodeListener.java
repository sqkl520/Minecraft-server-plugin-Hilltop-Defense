package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public class NodeListener implements Listener {

    /** 统一道具类型 PDC 键（D-3 PDC 防伪）：STRING 类型，值与 {@link #KIND_REPAIR} 对应。 */
    private static final String ITEM_KIND_KEY = "item-kind";
    /** item-kind 取值：节点修复汁液 */
    private static final String KIND_REPAIR = "repair";

    private final HilltopVillagePlugin plugin;
    private final LanguageManager lang;
    /** 统一道具类型键（构造期建好复用，同 WaveManager.airdropKey 模式） */
    private final NamespacedKey itemKindKey;

    public NodeListener(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.itemKindKey = new NamespacedKey(plugin, ITEM_KIND_KEY);
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

    /**
     * 对局进行中（RUNNING / WAVE_INTERMISSION / STARTING）才拦节点方块破坏。
     *
     * <p>判定依据活动实例的 {@link GameState}：未部署场景（无实例）一律放行，
     * 保留管理员部署/重置时自由挖掘重生锚方块的能力。STARTING 也拦是必要的——
     * 开局倒计时不在 {@code MapRestoreManager} 的记账窗口内，此期间挖掉的方块不会被回滚。</p>
     */
    private boolean isMatchActive(org.bukkit.World world) {
        var arena = plugin.getMultiArenaManager().getArenaByWorld(world.getName());
        if (arena == null) return false;
        GameState state = arena.getState();
        return state == GameState.RUNNING
                || state == GameState.WAVE_INTERMISSION
                || state == GameState.STARTING;
    }

    // ========== P2-19：节点方块破坏 / 爆炸拦截 ==========

    /**
     * 对局进行中阻止玩家挖掉节点方块（P2-19）。
     *
     * <p>节点方块（重生锚）被挖后逻辑节点仍按坐标健在：Buff 照发、记分板计数照旧、
     * 怪物继续对空气结算伤害，而修复路径（右键节点方块）彻底断开——节点永久不可修复。
     * 非对局状态不拦：部署/重置场景管理员需要能自由挖掉重生锚方块。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        GameManager gameManager = gm(block.getWorld());
        if (gameManager == null || !gameManager.getNodeSystem().isNodeBlock(block)) return;
        if (!isMatchActive(block.getWorld())) return; // 非对局（部署/重置/未启用场）放行

        event.setCancelled(true);
        event.getPlayer().sendMessage(lang.getFor(event.getPlayer(), "node.block-protected"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filterNodeBlocks(event.blockList(), event.getEntity() == null ? null : event.getEntity().getWorld());
    }

    /**
     * P2-19：重生锚/床爆炸走的是方块源 {@link BlockExplodeEvent}（Paper 事实），
     * 原 EntityExplode 过滤覆盖不到——玩家给节点充能后空手右键即触发原版爆炸。
     * 同样从 blockList 剔除节点方块，保住「节点只能被怪物打空血」的设计。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterNodeBlocks(event.blockList(), event.getBlock() == null ? null : event.getBlock().getWorld());
    }

    /** 从爆炸 blockList 中剔除本竞技场世界的节点方块（EntityExplode / BlockExplode 共用） */
    private void filterNodeBlocks(List<Block> blockList, org.bukkit.World world) {
        if (world == null) return;
        GameManager gameManager = gm(world);
        if (gameManager == null) return;

        List<Block> blocksToRemove = new java.util.ArrayList<>();
        for (Block block : blockList) {
            if (gameManager.getNodeSystem().isNodeBlock(block)) {
                blocksToRemove.add(block);
            }
        }
        blockList.removeAll(blocksToRemove);
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

        // P2-20（M-19 同族）：非参赛玩家对节点右键时 playerDataMap 无此人，
        // 修复链下游 gameManager.getPlayerData(player).addNodeRepaired() 会 NPE。
        // 非成员直接静默忽略（含非成员持修复物品的右键），不进入任何修复分支。
        PlayerData data = gameManager.getPlayerData(player);
        if (data == null) return;

        // P2-13 结算期门控：结算倒计时（VICTORY/DEFEAT）期间拒绝修复。
        // 原判定 isGameRunning() 只看 GameManager 状态——haltGame() 刻意不改状态，
        // 失败结算倒计时里它仍为 true，导致结算后修复会虚增本局战报（addNodeRepaired 落库）。
        // 现口径：isGameRunning() 为 false（含胜利结算，endGame 已改状态）或
        // isSettlementPhase() 为 true（失败结算，haltGame + 状态保持）都拒绝。
        // STORY-1.8：只在对局运行（含休整整备）时允许修节点，等待区/结算期拒绝并提示
        if (!gameManager.isGameRunning() || gameManager.isSettlementPhase()) {
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

            // P2-20：满血节点不再吞材料。修复前先查满血（isNodeFull），
            // 满血时提示且不消耗物品；repairNode 对满血的"修复"不再有意义。
            if (gameManager.getNodeSystem().isNodeFull(node)) {
                player.sendMessage(lang.getFor(player, "node.repair-full"));
                return;
            }

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

    /**
     * 是否修复汁液：PDC {@code hilltopvillage:item-kind = repair} 命中 → true（材质不再参与判定，
     * 与蛋/锤口径一致；工厂产物材质恒为 {@link #repairItemMaterial()}，管理员热改材质后旧
     * PDC 道具仍可用）；否则回退旧路径「材质匹配 + 显示名精确比较」——
     * 迁移兼容：fix3 之前发放的旧汁液无 PDC，下个大版本可删此分支。
     */
    private boolean isRepairItem(ItemStack item) {
        if (item == null) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        // D-3：PDC 为准
        String kind = meta.getPersistentDataContainer().get(itemKindKey, PersistentDataType.STRING);
        if (KIND_REPAIR.equals(kind)) return true;
        // 迁移兼容（旧道具无 PDC：材质 + 精确显示名）
        if (item.getType() != repairItemMaterial()) return false;
        return meta.hasDisplayName() && meta.getDisplayName().equals(repairItemName());
    }

    public ItemStack createRepairItem(int amount) {
        ItemStack item = new ItemStack(repairItemMaterial(), amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(repairItemName());
        // D-3：PDC 防伪标记（识别器以 PDC 为准；显示名仅作旧道具迁移兼容）
        meta.getPersistentDataContainer().set(itemKindKey, PersistentDataType.STRING, KIND_REPAIR);

        List<String> lore = new java.util.ArrayList<>();
        lore.add(lang.get("node.repair-item-lore1"));
        lore.add(lang.get("node.repair-item-lore2", lang.getLocale(), repairAmount()));
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }
}
