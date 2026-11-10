package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.VillagerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@link ShopListener} 里 round3 三条<b>未测链</b>的回归测试：右键开商店 / NPC 无敌 / 退出清冷却。
 *
 * <p>已测的购买链（扣款 / 发货 / 退款 / 防刷）在 {@link ShopListenerTest} 里，本类不重复。</p>
 *
 * <p><b>每条链都配反向对照半</b>，因为这三条链的失败都不会抛异常，只会表现为
 * 「点了没反应」或「不该开的时候开了」—— 只有正反两半同时存在，
 * 断言才真的承重（守卫恒真 / 恒假的实现会被对照半打红）。</p>
 *
 * <p><b>夹具关键点（探针实测）</b>：</p>
 * <ul>
 *   <li>宿主必须名为 {@code "HilltopVillage"} —— {@code NpcManager} 的 PDC 键由
 *       {@code new NamespacedKey(plugin, "shop-npc")} 得出，namespace 取自插件名。</li>
 *   <li>{@code MockBukkit} 4.45.0 的 {@code LivingEntityMock#setRemoveWhenFarAway} 未实现，
 *       因此用 {@link SafeVillagerMock} 补一个可观测 no-op（见 {NpcManagerTest} 的说明）。</li>
 *   <li>{@code PlayerMock#sendActionBar(String)} <b>不会</b>进 {@code nextActionBar()} 队列
 *       （实测只有 {@code Component} 重载会）。断言改用 {@code spy(player)} +
 *       {@code verify(player).sendActionBar(anyString())}。</li>
 *   <li>{@code playSound} 走 {@link PlayerMock#getHeardSounds()}，可用且计数准确。</li>
 * </ul>
 */
@DisplayName("ShopListener 实体交互三条链")
class ShopListenerEntityTest extends MockBukkitTestBase {

    /** 只补 MockBukkit 4.45.0 未实现的方法，其余行为继承真实实现。 */
    static final class SafeVillagerMock extends VillagerMock {
        private boolean removeWhenFarAway = true;

        SafeVillagerMock(org.mockbukkit.mockbukkit.ServerMock server, UUID uuid) {
            super(server, uuid);
        }

        @Override
        public void setRemoveWhenFarAway(boolean value) {
            this.removeWhenFarAway = value;
        }

        @Override
        public boolean getRemoveWhenFarAway() {
            return removeWhenFarAway;
        }
    }

    private BareHost host;
    private ShopManager shop;
    private ShopListener listener;
    private LanguageManager lang;
    private WorldMock world;
    private Villager shopNpc;

    @BeforeEach
    void setUp() {
        host = spy(MockBukkit.loadWith(BareHost.class, new PluginDescriptionFile(
                "HilltopVillage", "1.0.0", BareHost.class.getName())));
        lang = mock(LanguageManager.class, invocation -> {
            if (invocation.getMethod().getReturnType() == String.class) return "msg";
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        when(host.getLanguageManager()).thenReturn(lang);
        when(host.getLogger()).thenReturn(Logger.getLogger("shop-listener-entity-test-" + System.nanoTime()));

        shop = new ShopManager(host, lang);
        shop.reload();
        when(host.getShopManager()).thenReturn(shop);
        listener = new ShopListener(shop, lang);

        world = spawnableWorld("shop-listener-world");
        assertTrue(shop.getNpcManager().spawnNpc(new Location(world, 0.5, 64, 0.5)),
                "夹具前提：商店 NPC 必须先成功生成，否则所有右键用例都打在守卫上");
        shopNpc = firstVillager();
    }

    @AfterEach
    void tearDown() {
        try {
            if (shop != null) shop.getNpcManager().despawnNpc();
        } catch (RuntimeException ignored) {
            // 收尾失败不掩盖真正的断言失败
        }
    }

    // ================================================================ 夹具

    private WorldMock spawnableWorld(String name) {
        WorldMock real = server.addSimpleWorld(name);
        WorldMock spied = spy(real);
        doAnswer(invocation -> {
            Location at = invocation.getArgument(0);
            SafeVillagerMock villager = new SafeVillagerMock(server, UUID.randomUUID());
            villager.setLocation(at);
            server.registerEntity(villager);
            return villager;
        }).when(spied).spawnEntity(any(Location.class), any(EntityType.class));
        server.removeWorld(real);
        server.addWorld(spied);
        return spied;
    }

    private Villager firstVillager() {
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Villager villager) return villager;
        }
        throw new AssertionError("夹具失败：世界里没有村民");
    }

    /** 世界里所有村民（排除 PlayerMock 等其它实体）。 */
    private java.util.List<Villager> villagers() {
        java.util.List<Villager> out = new java.util.ArrayList<>();
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Villager villager) out.add(villager);
        }
        return out;
    }

    /** GUI 是否真的打开了。MockBukkit 的 openInventory 在从未打开过时 topInventory 可能为 null。 */
    private static boolean guiOpened(PlayerMock player) {
        InventoryView view = player.getOpenInventory();
        if (view == null) return false;
        Inventory top = view.getTopInventory();
        return top != null && top.getHolder() instanceof ShopGUI;
    }

    private PlayerMock playerInWorld(String name) {
        PlayerMock player = server.addPlayer(name);
        player.teleport(new Location(world, 2.5, 64, 2.5));
        return player;
    }

    private PlayerInteractEntityEvent interact(PlayerMock player, Entity target, EquipmentSlot hand) {
        return new PlayerInteractEntityEvent(player, target, hand);
    }

    /** 造一只「不是商店 NPC」的普通村民（既非当前引用，也无 PDC 标记）。 */
    private Villager plainVillager() {
        SafeVillagerMock villager = new SafeVillagerMock(server, UUID.randomUUID());
        villager.setLocation(new Location(world, 10.5, 64, 10.5));
        server.registerEntity(villager);
        return villager;
    }

    // ================================================================ 链 1：onPlayerInteractEntity

    @Nested
    @DisplayName("链 1 onPlayerInteractEntity（:50）—— 右键开商店")
    class InteractChain {

        @Test
        @DisplayName("正向：主手右键商店 NPC → 取消事件 + 播交易音 + 发余额 ActionBar + 打开 ShopGUI")
        void handInteractionOpensTheShop() {
            PlayerMock player = spy(playerInWorld("buyer"));
            PlayerInteractEntityEvent event = interact(player, shopNpc, EquipmentSlot.HAND);

            listener.onPlayerInteractEntity(event);

            assertTrue(event.isCancelled(),
                    "必须取消，否则原版村民交易界面会同时弹出，玩家看到两个界面");
            assertEquals(1, player.getHeardSounds().size(),
                    "应恰好播一次 ENTITY_VILLAGER_TRADE；0 次是静默交互，多次是重复发包");
            // getSound() 返回的是 Bukkit 的 sound key（如 "entity.villager.trade"），
            // 不是枚举名；用 Sound#getKey().getKey() 对比才不会因大小写/命名风格失配
            assertEquals(Sound.ENTITY_VILLAGER_TRADE.getKey().getKey(),
                    player.getHeardSounds().get(0).getSound(),
                    "声音必须是交易音（交互反馈）；实际 " + player.getHeardSounds().get(0).getSound());
            verify(player).sendActionBar(anyString());
            assertTrue(guiOpened(player),
                    "这条链的终点就是打开 ShopGUI；没打开等于右键完全无效");
            assertTrue(player.getOpenInventory().getTopInventory().getHolder() instanceof ShopGUI,
                    "holder 必须是 ShopGUI，ShopListener#onInventoryClick 靠它拦购买");
        }

        @Test
        @DisplayName("反向对照 A：右键普通村民 → 不取消、不播声、不开 GUI（守卫必须真的拦住）")
        void plainVillagerIsIgnored() {
            PlayerMock player = spy(playerInWorld("passerby"));
            PlayerInteractEntityEvent event = interact(player, plainVillager(), EquipmentSlot.HAND);

            listener.onPlayerInteractEntity(event);

            assertFalse(event.isCancelled(),
                    "把普通村民也取消掉会让玩家无法与村民系统/原版村民交互 —— 守卫恒真的实现会在这里红");
            assertEquals(0, player.getHeardSounds().size(), "非商店实体不该有商店交互音");
            verify(player, never()).sendActionBar(anyString());
            assertFalse(guiOpened(player), "非商店实体绝不能打开商店");
        }

        @Test
        @DisplayName("反向对照 B：旁观者右键商店 → 不开 GUI，且<b>也不取消</b>（P2-30 的两件事分开断言）")
        void spectatorGetsNoShopAndNoCancellation() {
            PlayerMock player = spy(playerInWorld("spectator"));
            player.setGameMode(GameMode.SPECTATOR);
            PlayerInteractEntityEvent event = interact(player, shopNpc, EquipmentSlot.HAND);

            listener.onPlayerInteractEntity(event);

            assertFalse(guiOpened(player),
                    "旁观者（阵亡等待复活 / 对局外观战）不属于对局玩家，不能开商店");
            assertFalse(event.isCancelled(),
                    "P2-30 明确要求「也不取消其事件」—— 只断言「没开 GUI」会让"
                            + "「取消了但没开」这种错误实现通过（玩家会被静默剥夺旁观交互）");
            verify(player, never()).sendActionBar(anyString());
            assertEquals(0, player.getHeardSounds().size(), "旁观者连声音都不该有");
        }

        @Test
        @DisplayName("反向对照 C：副手右键 → <b>已取消</b>但不开 GUI、不播声、不发 ActionBar")
        void offHandCancelsButDoesNotOpen() {
            PlayerMock player = spy(playerInWorld("offhand"));
            PlayerInteractEntityEvent event = interact(player, shopNpc, EquipmentSlot.OFF_HAND);

            listener.onPlayerInteractEntity(event);

            // 这两条必须分开：setCancelled(true) 在 OFF_HAND 早退「之前」—— 语义是
            // 「原版交易界面依然不许弹」，但「商店不开」。
            assertTrue(event.isCancelled(),
                    "副手也要挡住原版交易界面；若这里 false，说明取消被挪到了 OFF_HAND 早退之后");
            assertFalse(guiOpened(player),
                    "副手不重复开界面（主手已经开过一次，双手各开一次会闪屏）");
            assertEquals(0, player.getHeardSounds().size(), "副手早退在播声之前，不应有声音");
            verify(player, never()).sendActionBar(anyString());
        }

        @Test
        @DisplayName("守卫优先于旁观者早退：旁观者右键<b>普通村民</b>同样无事发生")
        void shopGuardRunsBeforeSpectatorCheck() {
            PlayerMock player = spy(playerInWorld("spectator2"));
            player.setGameMode(GameMode.SPECTATOR);
            PlayerInteractEntityEvent event = interact(player, plainVillager(), EquipmentSlot.HAND);

            listener.onPlayerInteractEntity(event);

            assertFalse(event.isCancelled(), "非商店实体一律放行");
            assertFalse(guiOpened(player));
        }

        @Test
        @DisplayName("多次右键同一 NPC 只保留最后一次打开的 GUI（不叠加、不泄漏）")
        void repeatedInteractionsOpenAFreshGui() {
            PlayerMock player = playerInWorld("repeat");

            listener.onPlayerInteractEntity(interact(player, shopNpc, EquipmentSlot.HAND));
            Object first = player.getOpenInventory().getTopInventory().getHolder();
            listener.onPlayerInteractEntity(interact(player, shopNpc, EquipmentSlot.HAND));
            Object second = player.getOpenInventory().getTopInventory().getHolder();

            assertTrue(first instanceof ShopGUI && second instanceof ShopGUI);
            assertTrue(first != second,
                    "每次右键都 new ShopGUI（注释写死「无需注册/注销监听器」）——"
                            + "若复用同一个实例，reload 后玩家会继续看到旧价格");
        }
    }

    // ================================================================ 链 2：onNpcDamage

    @Nested
    @DisplayName("链 2 onNpcDamage（:74）—— 商店 NPC 无敌")
    class DamageChain {

        @Test
        @DisplayName("正向：任何伤害来源打商店 NPC 一律取消")
        void cancelsAllDamageToTheShopNpc() {
            for (EntityDamageEvent.DamageCause cause : java.util.List.of(
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                    EntityDamageEvent.DamageCause.PROJECTILE,
                    EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
                    EntityDamageEvent.DamageCause.VOID)) {
                EntityDamageEvent event = new EntityDamageEvent(shopNpc, cause, 4.0);

                listener.onNpcDamage(event);

                assertTrue(event.isCancelled(),
                        cause + " 也必须被取消 —— 村民被炸飞/推进虚空同样等于商店消失");
            }
        }

        @Test
        @DisplayName("反向对照：惩罚普通村民的伤害不被干涉（不影响村民系统与其它插件）")
        void leavesForeignEntitiesAlone() {
            Villager plain = plainVillager();
            EntityDamageEvent event = new EntityDamageEvent(plain,
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0);

            listener.onNpcDamage(event);

            assertFalse(event.isCancelled(),
                    "守卫若退化成「取消所有 EntityDamageEvent」，整服 PvP/PvE 都会失灵");
        }

        @Test
        @DisplayName("正向：只有 PDC 的孤儿实体同样受保护（崩溃重连后商店不会被打死）")
        void protectsPdcOnlyOrphan() {
            SafeVillagerMock orphan = new SafeVillagerMock(server, UUID.randomUUID());
            orphan.setLocation(new Location(world, 30.5, 64, 30.5));
            orphan.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("hilltopvillage", "shop-npc"),
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            server.registerEntity(orphan);
            EntityDamageEvent event = new EntityDamageEvent(orphan,
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0);

            listener.onNpcDamage(event);

            assertTrue(event.isCancelled(),
                    "重启后当前引用可能尚未指向该实体（或已重生成），PDC 判据是这一瞬间的唯一保护");
        }

        @Test
        @DisplayName("反向对照：非商店实体（玩家、掉落物等）一律不取消")
        void doesNothingForNonNpcEntities() {
            PlayerMock player = playerInWorld("victim");
            EntityDamageEvent event = new EntityDamageEvent(player,
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0);

            listener.onNpcDamage(event);

            assertFalse(event.isCancelled(), "玩家受伤必须原样放行");
        }
    }

    // ================================================================ 链 3：onPlayerQuit

    @Nested
    @DisplayName("链 3 onPlayerQuit（:219）—— 退出清理购买冷却")
    class QuitChain {

        /** 走真实购买链打一发冷却：给钱 → 点商品槽。 */
        private void clickBowSlot(PlayerMock player, ShopItem bow) {
            ShopGUI gui = new ShopGUI(player, shop, lang);
            gui.open();
            InventoryView view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(gui.getInventory());
            when(view.getBottomInventory()).thenReturn(player.getInventory());
            when(view.getPlayer()).thenReturn(player);
            when(view.getType()).thenReturn(InventoryType.CHEST);
            when(view.getInventory(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
                int rawSlot = invocation.getArgument(0);
                if (rawSlot < 0) return null;
                return rawSlot < gui.getInventory().getSize() ? gui.getInventory() : player.getInventory();
            });
            when(view.convertSlot(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
                int rawSlot = invocation.getArgument(0);
                return rawSlot < gui.getInventory().getSize()
                        ? rawSlot : rawSlot - gui.getInventory().getSize();
            });
            listener.onInventoryClick(new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                    bow.getSlot(), ClickType.LEFT, InventoryAction.PICKUP_ALL));
        }

        private ShopItem bow() {
            return shop.getShopConfig().getItems().stream()
                    .filter(item -> item.getId().equals("bow"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("出厂 shop.yml 里应有商品 bow"));
        }

        @Test
        @DisplayName("正向：退出后再点一次能立即成交（冷却记录已被清掉）")
        void quitClearsTheCooldownSoTheNextClickSells() {
            PlayerMock player = playerInWorld("leaver");
            ShopItem bow = bow();
            // 两次购买的量：1 次成交 + 1 次「退出后立刻再成交」
            player.getInventory().setItem(0, shop.createCurrencyItem(bow.getPrice() * 2));

            clickBowSlot(player, bow);
            assertEquals(bow.getPrice(), shop.countCurrency(player),
                    "前提：第一次购买应扣掉 price");

            // 250 ms 内的第二次点击必须被冷却挡住（否则下一条断言无法归因）
            clickBowSlot(player, bow);
            assertEquals(bow.getPrice(), shop.countCurrency(player),
                    "前提：同一时刻的第二次点击必须仍被冷却拦住");

            listener.onPlayerQuit(new PlayerQuitEvent(player, (String) null));
            clickBowSlot(player, bow);

            assertEquals(0, shop.countCurrency(player),
                    "退出时 buyCooldowns.remove 必须删掉该玩家的记录 —— 否则重登后 250 ms 内"
                            + "第一次点击会被静默吞掉（玩家侧表现为「刚上线点商店没反应」）。"
                            + "实际剩余 " + shop.countCurrency(player) + "，期望 0");
        }

        @Test
        @DisplayName("反向对照：不退出时第三次点击仍被冷却拦住（证明差异来自 quit 而非计时）")
        void withoutQuitTheCooldownStillBlocks() {
            PlayerMock player = playerInWorld("stayer");
            ShopItem bow = bow();
            player.getInventory().setItem(0, shop.createCurrencyItem(bow.getPrice() * 2));

            clickBowSlot(player, bow);
            clickBowSlot(player, bow);
            clickBowSlot(player, bow);

            assertEquals(bow.getPrice(), shop.countCurrency(player),
                    "没有 quit 时冷却记录仍在，第三次点击必须继续被拦。"
                            + "若这里变成 0，说明上一条用例的差异并非来自 onPlayerQuit（对照失效）");
        }

        @Test
        @DisplayName("反向：退出事件对<b>别的玩家</b>的冷却无影响（只删自己的记录）")
        void quittingDoesNotClearOtherPlayersCooldown() {
            PlayerMock buyer = playerInWorld("buyer2");
            PlayerMock other = playerInWorld("other2");
            ShopItem bow = bow();
            buyer.getInventory().setItem(0, shop.createCurrencyItem(bow.getPrice() * 2));

            clickBowSlot(buyer, bow);
            listener.onPlayerQuit(new PlayerQuitEvent(other, (String) null));
            clickBowSlot(buyer, bow);

            assertEquals(bow.getPrice(), shop.countCurrency(buyer),
                    "若实现写成 buyCooldowns.clear()，一个玩家退出就会放行全服冷却（可被用来刷物品）");
        }

        @Test
        @DisplayName("反向：从未购买过的玩家退出不抛（remove 对不存在的键是无害的）")
        void quitForAnUnseenPlayerIsHarmless() {
            PlayerMock player = playerInWorld("never-bought");

            listener.onPlayerQuit(new PlayerQuitEvent(player, (String) null));

            assertEquals(0, shop.countCurrency(player), "未购买过，余额应保持 0");
        }
    }

    // ================================================================ 交叉守卫

    @Nested
    @DisplayName("三条链的共同前提：isShopNpc 双判据")
    class SharedGuard {

        @Test
        @DisplayName("当前引用与 PDC 孤儿都能触发交互链，且行为一致")
        void bothIdentificationPathsOpenTheShop() {
            PlayerMock onReference = playerInWorld("ref");
            listener.onPlayerInteractEntity(interact(onReference, shopNpc, EquipmentSlot.HAND));
            assertTrue(guiOpened(onReference), "引用判据路径失效");

            SafeVillagerMock orphan = new SafeVillagerMock(server, UUID.randomUUID());
            orphan.setLocation(new Location(world, 40.5, 64, 40.5));
            orphan.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("hilltopvillage", "shop-npc"),
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            server.registerEntity(orphan);
            PlayerMock onPdc = spy(playerInWorld("pdc"));
            PlayerInteractEntityEvent event = interact(onPdc, orphan, EquipmentSlot.HAND);

            listener.onPlayerInteractEntity(event);

            assertTrue(event.isCancelled(), "PDC 判据路径失效：重启后重连的商店村民将完全失效");
            assertTrue(guiOpened(onPdc), "PDC 判据路径必须也能开出商店");
            assertEquals(2, villagers().size(), "夹具自检：NPC + 孤儿各一只");
        }

        @Test
        @DisplayName("没有任何 NPC 时，右键任意村民都不动作（未部署场景不产生副作用）")
        void noNpcMeansNoInteraction() {
            shop.getNpcManager().despawnNpc();
            Villager plain = plainVillager();
            PlayerMock player = spy(playerInWorld("no-npc"));
            PlayerInteractEntityEvent event = interact(player, plain, EquipmentSlot.HAND);

            listener.onPlayerInteractEntity(event);

            assertFalse(event.isCancelled());
            assertFalse(guiOpened(player));
            assertNull(shop.getNpcManager().getNpcLocation());
            assertNotNull(plain, "夹具自检：目标实体存在");
        }
    }
}
