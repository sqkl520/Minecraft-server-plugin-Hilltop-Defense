package com.hilltopvillage.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.mechanics.NodeSystem.NodeData;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.DisplayEntityManager;

/**
 * #1 修复结算：{@code repair-cost-amount} 是「最多消耗几个物品」，不是「每次固定扣几个」，
 * 且恢复量必须按实际消耗的物品数计算。
 *
 * <p>本类走真实 {@link NodeListener#onPlayerInteract}，只把节点系统/竞技场查表替换为 mock，
 * 因此红色失败对应的是生产结算代码本身，而不是测试里复制的一份算术。</p>
 *
 * <p>四种缺陷形态：cost=5 少恢复、cost=0 免费、cost&lt;0 物品反增、
 * 节点只差 30 血仍扣 5 个。第五种（持有不足）确保不会凭配置值透支。</p>
 */
class RepairSettlementTest extends MockBukkitTestBase {

    private World world;
    private Player player;
    private NodeListener listener;
    private GameConfig config;
    private NodeData node;
    private final AtomicReference<Double> restored = new AtomicReference<>(0.0);

    @BeforeEach
    void setUp() {
        config = new GameConfig();
        HilltopVillagePlugin plugin = MechanicsFixtures.mockPlugin(
                java.util.logging.Logger.getLogger("repair-settlement-" + System.nanoTime()),
                new File(dataFolder.toFile(), "plugin"), config);

        world = server.addSimpleWorld("repair_settlement_world");
        player = server.addPlayer("Repairer");

        ArenaInstance arena = mock(ArenaInstance.class);
        GameManager gameManager = mock(GameManager.class);
        MultiArenaManager arenaManager = mock(MultiArenaManager.class);
        NodeSystem nodeSystem = mock(NodeSystem.class);
        node = mock(NodeData.class);
        DisplayEntityManager display = mock(DisplayEntityManager.class);

        when(arena.getGameManager()).thenReturn(gameManager);
        when(arenaManager.getArenaByWorld(world.getName())).thenReturn(arena);
        when(plugin.getMultiArenaManager()).thenReturn(arenaManager);

        when(gameManager.isGameRunning()).thenReturn(true);
        when(gameManager.isSettlementPhase()).thenReturn(false);
        when(gameManager.getNodeSystem()).thenReturn(nodeSystem);
        when(gameManager.getPlayerData(any(Player.class)))
                .thenReturn(new PlayerData(UUID.randomUUID()));
        when(gameManager.getDisplayEntityManager()).thenReturn(display);

        when(nodeSystem.isNodeBlock(any(Block.class))).thenReturn(true);
        when(nodeSystem.getNodeAt(any())).thenReturn(node);
        when(nodeSystem.isNodeFull(node)).thenReturn(false);
        when(nodeSystem.repairNode(any(), any(Player.class), anyDouble())).thenAnswer(invocation -> {
            restored.set(invocation.getArgument(2));
            return true;
        });

        listener = new NodeListener(plugin);
    }

    private record Outcome(int remainingItems, double restoredHealth) {
    }

    private Outcome clickWith(int cost, double perItem, int held, double currentHealth,
            double maxHealth) {
        config.setRepairCostAmount(cost);
        config.setRepairAmountPerCost(perItem);
        when(node.getCurrentHealth()).thenReturn(currentHealth);
        when(node.getMaxHealth()).thenReturn(maxHealth);
        restored.set(0.0);

        ItemStack repairItem = listener.createRepairItem(held);
        assertEquals(held, repairItem.getAmount(), "前置条件：工厂必须真的给足持有量");
        player.getInventory().setItemInMainHand(repairItem);
        assertEquals(held, player.getInventory().getItemInMainHand().getAmount(),
                "前置条件：主手必须保留全部持有量");
        Block clicked = world.getBlockAt(8, 64, 8);
        clicked.setType(Material.RESPAWN_ANCHOR);
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, repairItem, clicked,
                BlockFace.NORTH, EquipmentSlot.HAND);
        listener.onPlayerInteract(event);

        ItemStack after = player.getInventory().getItemInMainHand();
        int remaining = after == null || after.getType().isAir() ? 0 : after.getAmount();
        return new Outcome(remaining, restored.get());
    }

    @Test
    @DisplayName("cost=5、节点只差 30 血：只消耗 1 个，恢复 30（不白扣另外 4 个）")
    void consumesOnlyWhatTheDeficitNeeds() {
        Outcome outcome = clickWith(5, 50.0, 10, 170.0, 200.0);

        assertEquals(9, outcome.remainingItems(),
                "差 30 血只需 ceil(30/50)=1 个物品；固定扣 5 个会让玩家白丢 4 个");
        assertEquals(30.0, outcome.restoredHealth(), 1e-9,
                "恢复量必须等于实际缺口，不能按「每物品恢复量」写死 50");
    }

    @Test
    @DisplayName("cost=5、缺口 250：消耗 5 个并恢复 5×50=250（不是只恢复 50）")
    void consumesConfiguredCostAndRestoresAllOfIt() {
        Outcome outcome = clickWith(5, 50.0, 10, 0.0, 250.0);

        assertEquals(5, 10 - outcome.remainingItems(),
                "缺口 250 / 每物品 50 需要 5 个，且不超过配置上限");
        assertEquals(250.0, outcome.restoredHealth(), 1e-9,
                "★ 消耗 5 个只恢复 1 份是 #1 的核心缺陷");
    }

    @Test
    @DisplayName("cost=0：不消耗也不修复（免费修复必须被拒绝）")
    void zeroCostDoesNothing() {
        Outcome outcome = clickWith(0, 50.0, 10, 0.0, 100.0);

        assertEquals(10, outcome.remainingItems(), "cost=0 不得消耗物品");
        assertEquals(0.0, outcome.restoredHealth(), 1e-9,
                "cost=0 不得凭空修复；#7 会把载入值钳到 1，纯函数仍需防御编程赋值");
    }

    @Test
    @DisplayName("cost=-3：不消耗也不修复（负数不得让物品反增）")
    void negativeCostDoesNothing() {
        Outcome outcome = clickWith(-3, 50.0, 10, 0.0, 100.0);

        assertEquals(10, outcome.remainingItems(), "负数 cost 不得把物品从 10 加到 13");
        assertEquals(0.0, outcome.restoredHealth(), 1e-9, "负数 cost 不得修复");
    }

    @Test
    @DisplayName("持有不足：cost=5 但只有 2 个 → 消耗 2 个、恢复 100，不透支")
    void neverConsumesMoreThanHeld() {
        Outcome outcome = clickWith(5, 50.0, 2, 0.0, 250.0);

        assertEquals(100.0, outcome.restoredHealth(), 1e-9,
                "恢复量必须按实际消耗的 2 个计算，而不是配置的 5 个");
        assertEquals(0, outcome.remainingItems(), "只能消耗实际持有的 2 个");
    }
}
