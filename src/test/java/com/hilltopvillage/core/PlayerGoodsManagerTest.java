package com.hilltopvillage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link PlayerGoodsManager} 的「进竞技场前快照 / 离场归还」契约测试。
 *
 * <p>本类守的是<b>整个插件里最贵的一类缺陷：玩家物品永久丢失</b>。
 * 前一轮修掉的高危 bug 是 {@code restore()} 先把快照从 {@code goods} 移除、再把内容写回背包 ——
 * 中途任何一个 Bukkit 调用抛出，唯一的快照副本与玩家背包会同时消失，没有任何补偿路径。
 * 现在的实现是 {@code get()} 读快照 → 全部写回成功 → 才 {@code remove()}。</p>
 *
 * <p><b>夹具为什么这么写</b>：MockBukkit 4.45.0 的
 * {@code PlayerInventoryMock#setArmorContents(null)} 会抛
 * {@code NullPointerException: ItemStack was null}（真实 Bukkit 允许 null = 清空盔甲）。
 * 生产代码 {@code clearInventory()} 正是传 {@code null}，因此必须 spy 一份
 * {@link PlayerInventory} 把该方法置为 no-op，否则 {@code save()}/{@code restore()} 根本跑不到
 * 被断言的那几行 —— 那样写出来的测试是假绿。</p>
 *
 * <p>{@code lastSmashY} 式的"顺手记一笔"不在本类，本类只关心快照的生命周期。</p>
 */
class PlayerGoodsManagerTest extends MockBukkitTestBase {

    private BareHost host;
    private PlayerGoodsManager manager;
    private PlayerMock rawPlayer;
    private Player player;
    private PlayerInventory inventory;

    /** 让注入的 {@code setExp} 失败可以开关，以便「失败 → 重试成功」两步走。 */
    private final AtomicBoolean failOnSetExp = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        host = loadBareHost();
        manager = new PlayerGoodsManager(host);

        rawPlayer = server.addPlayer("GoodsHolder");
        PlayerInventory realInventory = rawPlayer.getInventory();

        // B-4：MockBukkit 的 setArmorContents(null) 会 NPE，而生产代码 clearInventory() 就传 null。
        inventory = spy(realInventory);
        doNothing().when(inventory).setArmorContents(any());
        // 兜底：mock 端 getItemInOffHand() 可能返回 null，生产代码会直接 .clone()
        doReturn(new ItemStack(Material.AIR)).when(inventory).getItemInOffHand();

        player = spy(rawPlayer);
        doReturn(inventory).when(player).getInventory();
        // setExp 既要做「注入失败」的开关，又要在正常情况下真的写进去 —— 因此
        // 非失败分支必须 callRealMethod()，否则 restore 的 setExp 变成空操作，
        // 「经验是否归还」这条断言会永久为假（假红/假绿两个方向都会踩）。
        doAnswer(invocation -> {
            if (failOnSetExp.get()) {
                throw new IllegalStateException("injected mid-restore failure (test)");
            }
            return invocation.callRealMethod();
        }).when(player).setExp(anyFloat());
    }

    /** 往玩家背包第 0 格放一份物品。 */
    private void giveItem(Material material, int amount) {
        inventory.setItem(0, new ItemStack(material, amount));
    }

    /** 读第 0 格物品；空格返回 null。 */
    private ItemStack slotZero() {
        ItemStack item = inventory.getContents()[0];
        return item == null || item.getType() == Material.AIR ? null : item;
    }

    // ------------------------------------------------------------------ (a)

    @Test
    @DisplayName("(a) save → restore 完整往返，物品原样归还")
    void saveThenRestoreRoundTrips() {
        giveItem(Material.DIAMOND, 7);
        // 注意：Mockito spy 是「拷贝字段」而非「委托代理」，因此在 spy 上设状态、
        // 也从 spy 上读状态 —— 读写 rawPlayer 只会碰到一份不再被生产代码使用的副本。
        player.setLevel(12);
        player.setExp(0.5f);

        assertTrue(manager.save(player), "首次 save 应新建快照并返回 true");
        assertTrue(manager.has(player), "save 后必须持有快照");
        assertEquals(null, slotZero(), "save 必须清空背包，否则玩家带着原物品进竞技场");
        assertEquals(0, player.getLevel(), "save 必须清零等级");

        assertTrue(manager.restore(player), "restore 应返回 true");
        ItemStack restored = slotZero();
        assertNotNull(restored, "restore 没有把物品写回背包 —— 这就是物品丢失");
        assertEquals(Material.DIAMOND, restored.getType());
        assertEquals(7, restored.getAmount());
        assertEquals(12, player.getLevel(), "等级未归还");
        assertEquals(0.5f, player.getExp(), 1e-6, "经验未归还");
        assertFalse(manager.has(player), "restore 成功后快照必须被消费掉");
    }

    // ------------------------------------------------------------------ (b)

    @Test
    @DisplayName("(b) 快照只被消费一次：归还后再次 restore 返回 false 且不动背包")
    void snapshotConsumedExactlyOnce() {
        giveItem(Material.EMERALD, 4);
        manager.save(player);

        assertTrue(manager.restore(player));

        assertFalse(manager.has(player), "首次归还后不应再持有快照");
        assertTrue(manager.pendingUuids().isEmpty(), "归还后待还清单必须为空：" + manager.pendingUuids());

        // 二次归还：必须走 null 快照的快速失败分支，不能重复发一份物品
        assertFalse(manager.restore(player), "第二次 restore 必须返回 false（快照已消费）");
        ItemStack after = slotZero();
        assertNotNull(after, "背包不该被第二次 restore 清空");
        assertEquals(4, after.getAmount(), "物品不该被二次归还放大成 8 个");
    }

    @Test
    @DisplayName("(c) 无快照时 restore 返回 false，且完全不碰背包")
    void restoreWithoutSnapshotIsNoOp() {
        giveItem(Material.GOLD_INGOT, 3);
        assertFalse(manager.restore(player), "无快照必须返回 false");
        ItemStack untouched = slotZero();
        assertNotNull(untouched, "无快照时 restore 不得清空背包");
        assertEquals(Material.GOLD_INGOT, untouched.getType());
        assertEquals(3, untouched.getAmount());
    }

    // ------------------------------------------------------------------ (c')

    @Test
    @DisplayName("(c') restore 中途抛异常时快照必须存活，且重试能成功归还")
    void snapshotSurvivesMidRestoreException() {
        giveItem(Material.DIAMOND, 7);
        manager.save(player);

        failOnSetExp.set(true);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> manager.restore(player),
                "注入的 setExp 异常必须原样上抛，不能被吞掉");
        assertTrue(thrown.getMessage().contains("injected mid-restore"),
                "抛出的不是注入的那个异常，测试夹具失效：" + thrown.getMessage());

        // 核心断言：写回失败时快照必须还在内存里。旧实现（先 remove 再写回）此处为 false，
        // 玩家的 7 个钻石从此既不在背包、也没有快照 —— 永久丢失。
        assertTrue(manager.has(player),
                "restore 中途失败后快照被销毁 —— 玩家物品永久丢失（这正是本轮要守的 bug）");
        assertTrue(manager.pendingUuids().contains(player.getUniqueId()),
                "待还清单里必须仍有该玩家：" + manager.pendingUuids());

        // 重试路径必须真的可用（快照存活才有意义）
        failOnSetExp.set(false);
        assertTrue(manager.restore(player), "异常消除后重试归还必须成功");
        ItemStack restored = slotZero();
        assertNotNull(restored, "重试归还后背包仍为空");
        assertEquals(Material.DIAMOND, restored.getType());
        assertEquals(7, restored.getAmount());
        assertFalse(manager.has(player), "重试成功后快照必须被消费");
    }

    // ------------------------------------------------------------------ (d)

    @Test
    @DisplayName("(d) 二次 save 不覆盖原始快照")
    void doubleSaveKeepsOriginalSnapshot() {
        giveItem(Material.DIAMOND, 3);
        assertTrue(manager.save(player), "首次 save 应成功");

        // 玩家在本局里获得了别的东西，再次触发 save（例如重连 / 重复加入）
        inventory.clear();
        giveItem(Material.COAL, 9);
        assertFalse(manager.save(player), "已有快照时 save 必须返回 false（明确未新建）");
        assertEquals(null, slotZero(), "二次 save 仍应清空当前背包");

        assertTrue(manager.restore(player));
        ItemStack restored = slotZero();
        assertNotNull(restored, "原始快照丢失");
        assertEquals(Material.DIAMOND, restored.getType(),
                "归还的是本局捡到的煤，而不是进竞技场前的钻石 —— 原始快照被覆盖了");
        assertEquals(3, restored.getAmount());
    }

    @Test
    @DisplayName("(d') 二次 save 返回 false 时也不改变快照数量")
    void doubleSaveDoesNotDuplicateSnapshot() {
        giveItem(Material.DIAMOND, 1);
        manager.save(player);
        manager.save(player);
        manager.save(player);
        assertEquals(1, manager.pendingUuids().size(),
                "重复 save 产生了多份快照：" + manager.pendingUuids());
    }
}
