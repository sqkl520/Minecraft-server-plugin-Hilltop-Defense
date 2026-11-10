package com.hilltopvillage.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@link LobbyProtectionListener} 的行为测试。
 *
 * <p><b>本轮瞄准的 round1 修复（{@code slice-10-logic-002}）</b>：{@code onEntityDamage} 原先只在
 * 伤害来源是玩家时才拦，于是怪物近战 / 箭 / 火焰 / 岩浆可以拆掉大厅里的盔甲架与物品展示框，
 * 而大厅不在 {@code MapRestoreManager} 的回滚范围内 —— 装饰被拆就永久没了。修复后改成
 * 「按受击实体所在世界判定，只有玩家伤害源保留建造模式豁免，其余一律取消」。</p>
 *
 * <p><b>为什么这条修复必须配一个「反向」控制组</b>：如果只断言「大厅里盔甲架被僵尸打 → 取消」，
 * 一个「无条件取消所有 EntityDamageByEntityEvent」的实现也能通过，而那会让竞技场里的村民
 * 免疫怪物攻击 —— 直接废掉核心玩法。所以下面同时把「竞技场村民照常挨打」「建造模式管理员
 * 仍可拆」写成断言。</p>
 *
 * <p><b>测试手法</b>：监听器只通过 {@code getWorld().getName()} / {@code instanceof} /
 * {@code getUniqueId()} 触碰实体，所以所有受伤者与伤害源都用 Mockito 接口 mock 构造，
 * 不需要为每种实体真实生成（MockBukkit 的 {@code spawn} 对 ArmorStand 支持不稳）。
 * 「block 位于哪个世界」用真实的 {@link WorldMock#getBlockAt} 表达，因为方块世界是
 * 监听器判定入口的核心输入，不能靠 mock 假装。</p>
 *
 * <p><b>语言桩</b>：本类不用 {@link MechanicsFixtures#mockLanguageManager()}，而是在建 mock 时
 * 挂默认 {@code Answer} 把 {@code send} 的 key 记进列表 —— 事件里 {@code send} 是 void，
 * 而 Mockito 的 varargs 匹配在「零个可变参数」时并不可靠（见 round2-08 §2.1 陷阱 B）。
 * 挂 Answer 完全绕开参数匹配。</p>
 */
@DisplayName("LobbyProtectionListener — 大厅保护与 round1 非玩家伤害修复")
class LobbyProtectionListenerTest extends MockBukkitTestBase {

    private static final String LOBBY = "lobby";
    private static final String ARENA = "hilltop_arena1";
    private static final String DENIED_KEY = "lobby-protected";

    private Logger logger;
    private HilltopVillagePlugin plugin;
    private MultiArenaManager arenas;
    /** 监听器发出去的全部 i18n key（按发送顺序）。 */
    private final List<String> sentKeys = new ArrayList<>();

    private WorldMock lobbyWorld;
    private WorldMock arenaWorld;
    private LobbyProtectionListener listener;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        lobbyWorld = server.addSimpleWorld(LOBBY);
        arenaWorld = server.addSimpleWorld(ARENA);

        logger = Logger.getLogger("lobby-protection-" + System.nanoTime());
        plugin = MechanicsFixtures.mockPlugin(logger, dataFolder.toFile(), new GameConfig());

        arenas = mock(MultiArenaManager.class);
        when(arenas.isLobbyWorld(LOBBY)).thenReturn(true);
        when(arenas.isLobbyWorld(ARENA)).thenReturn(false);
        when(plugin.getMultiArenaManager()).thenReturn(arenas);

        // send 是 void 且带 varargs：用默认 Answer 记录，避免 any() 匹配零参数时的静默失败。
        // ⚠️ 必须显式转成 Object 再交给 String.valueOf：getArgument(1) 的返回类型由目标方法
        // 推断，直接写 String.valueOf(...) 会被编译器选成 valueOf(char[])，运行时 ClassCast。
        LanguageManager lang = mock(LanguageManager.class, invocation -> {
            if ("send".equals(invocation.getMethod().getName())) {
                sentKeys.add(String.valueOf((Object) invocation.getArgument(1)));
            }
            return null;
        });
        when(plugin.getLanguageManager()).thenReturn(lang);

        listener = new LobbyProtectionListener(plugin);
        player = server.addPlayer("Builder");
        player.teleport(new Location(lobbyWorld, 0, 64, 0));
    }

    // ---------------------------------------------------------------- 辅助

    /** 造一个「世界已知、UUID 唯一」的实体 mock；监听器只用这两项。 */
    private <T extends Entity> T entityIn(World world, Class<T> type) {
        T entity = mock(type);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        return entity;
    }

    private BlockBreakEvent breakIn(World world) {
        return new BlockBreakEvent(world.getBlockAt(0, 64, 0), player);
    }

    private void movePlayerTo(World world) {
        player.teleport(new Location(world, 0, 64, 0));
    }

    // ---------------------------------------------------------------- 1. 方块 / 桶 / 掉落

    @Nested
    @DisplayName("方块与容器交互")
    class Blocks {

        @Test
        @DisplayName("大厅内破坏方块 → 取消并提示")
        void lobbyBreakIsCancelledAndDenied() {
            BlockBreakEvent event = breakIn(lobbyWorld);

            listener.onBlockBreak(event);

            assertTrue(event.isCancelled(), "大厅内玩家破坏方块必须被取消");
            assertEquals(List.of(DENIED_KEY), sentKeys,
                    "被拦的玩家必须收到 lobby-protected 提示，否则玩家只会觉得「点了没反应」");
        }

        @Test
        @DisplayName("竞技场世界破坏方块 → 放行（大厅保护不得越界到对局世界）")
        void arenaBreakIsAllowed() {
            movePlayerTo(arenaWorld);
            BlockBreakEvent event = breakIn(arenaWorld);

            listener.onBlockBreak(event);

            assertFalse(event.isCancelled(),
                    "竞技场世界不受大厅保护约束；若这里红，玩家在对局里挖不了任何方块");
            assertTrue(sentKeys.isEmpty(), "放行时不得发送任何提示");
        }

        @Test
        @DisplayName("建造模式管理员在大厅可破坏、可放置，退出后立即失效")
        void buildModeExemptsLobbyRestrictions() {
            assertTrue(listener.toggleBuildMode(player), "首次 toggle 应返回 true（开启）");
            assertTrue(listener.isBuildMode(player));

            BlockBreakEvent breakEvent = breakIn(lobbyWorld);
            listener.onBlockBreak(breakEvent);
            assertFalse(breakEvent.isCancelled(), "建造模式下大厅内应可破坏方块");

            BlockPlaceEvent placeEvent = new BlockPlaceEvent(
                    lobbyWorld.getBlockAt(1, 64, 1), lobbyWorld.getBlockAt(1, 64, 1).getState(),
                    lobbyWorld.getBlockAt(0, 64, 1), null, player, true, null);
            listener.onBlockPlace(placeEvent);
            assertFalse(placeEvent.isCancelled(), "建造模式下大厅内应可放置方块");

            assertFalse(listener.toggleBuildMode(player), "第二次 toggle 应返回 false（关闭）");
            assertFalse(listener.isBuildMode(player));

            BlockBreakEvent again = breakIn(lobbyWorld);
            listener.onBlockBreak(again);
            assertTrue(again.isCancelled(), "关闭建造模式后必须立即恢复大厅保护");
        }

        @Test
        @DisplayName("cleanupPlayer 摘掉建造模式标记（退出即失效，重进需重新 /hilltop build）")
        void cleanupPlayerDropsBuildMode() {
            listener.toggleBuildMode(player);
            assertTrue(listener.isBuildMode(player), "前置条件：建造模式已开启");

            listener.cleanupPlayer(player);

            assertFalse(listener.isBuildMode(player),
                    "退出后必须摘掉标记；否则管理员下次登录会莫名带着建造豁免");
            BlockBreakEvent event = breakIn(lobbyWorld);
            listener.onBlockBreak(event);
            assertTrue(event.isCancelled(), "退出后重进来（标记已清）必须重新受大厅保护");
        }

        @Test
        @DisplayName("大厅内实体爆炸 → 清空 blockList 并取消")
        void entityExplodeClearsAndCancelsInLobby() {
            Zombie bomber = entityIn(lobbyWorld, Zombie.class);
            List<Block> blocks = new ArrayList<>(List.of(
                    lobbyWorld.getBlockAt(1, 64, 1), lobbyWorld.getBlockAt(2, 64, 2)));
            EntityExplodeEvent event = new EntityExplodeEvent(
                    bomber, new Location(lobbyWorld, 1, 64, 1), blocks, 1.0f,
                    org.bukkit.ExplosionResult.DESTROY);

            listener.onEntityExplode(event);

            assertTrue(event.blockList().isEmpty(),
                    "取消事件不足以保住方块：其它插件若先 setCancelled(false) 就会真的炸掉，"
                            + "blockList 必须一并清空");
            assertTrue(event.isCancelled(), "大厅内爆炸必须取消");
        }

        @Test
        @DisplayName("大厅内方块源爆炸（重生锚/床）同样清空 blockList 并取消")
        void blockExplodeClearsAndCancelsInLobby() {
            Block source = lobbyWorld.getBlockAt(3, 64, 3);
            List<Block> blocks = new ArrayList<>(List.of(lobbyWorld.getBlockAt(3, 64, 3)));
            BlockExplodeEvent event = new BlockExplodeEvent(
                    source, source.getState(), blocks, 1.0f,
                    org.bukkit.ExplosionResult.DESTROY);

            listener.onBlockExplode(event);

            assertTrue(event.blockList().isEmpty(), "方块源爆炸也必须清空 blockList");
            assertTrue(event.isCancelled(), "大厅内方块源爆炸必须取消");
        }

        @Test
        @DisplayName("竞技场世界的爆炸不受影响")
        void explodeInArenaIsNotTouched() {
            Zombie bomber = entityIn(arenaWorld, Zombie.class);
            List<Block> blocks = new ArrayList<>(List.of(arenaWorld.getBlockAt(1, 64, 1)));
            EntityExplodeEvent event = new EntityExplodeEvent(
                    bomber, new Location(arenaWorld, 1, 64, 1), blocks, 1.0f,
                    org.bukkit.ExplosionResult.DESTROY);

            listener.onEntityExplode(event);

            assertFalse(event.isCancelled(), "竞技场爆炸不得被大厅保护拦下（怪物拆点依赖它）");
            assertEquals(1, event.blockList().size(), "竞技场爆炸的 blockList 不得被清空");
        }
    }

    // ---------------------------------------------------------------- 2. 火

    @Nested
    @DisplayName("引燃与烧毁")
    class Fire {

        @Test
        @DisplayName("自然引燃（无玩家源）在建造模式下也不给豁免")
        void naturalIgniteIsAlwaysBlocked() {
            Block target = lobbyWorld.getBlockAt(4, 64, 4);

            BlockIgniteEvent natural = new BlockIgniteEvent(
                    target, BlockIgniteEvent.IgniteCause.LIGHTNING, (Player) null);
            listener.onBlockIgnite(natural);

            assertTrue(natural.isCancelled(),
                    "闪电/烈焰人等无玩家源的引燃没有「建造模式」可言，必须一律拦下；"
                            + "大厅不在回滚范围内，烧毁不可恢复");
        }

        @Test
        @DisplayName("建造模式管理员可试火，普通玩家不可")
        void onlyBuildModeIgniterIsExempt() {
            Block target = lobbyWorld.getBlockAt(5, 64, 5);

            BlockIgniteEvent byNormalPlayer = new BlockIgniteEvent(
                    target, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, player);
            listener.onBlockIgnite(byNormalPlayer);
            assertTrue(byNormalPlayer.isCancelled(), "普通玩家不得在大厅点火");

            listener.toggleBuildMode(player);
            BlockIgniteEvent byBuilder = new BlockIgniteEvent(
                    target, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, player);
            listener.onBlockIgnite(byBuilder);
            assertFalse(byBuilder.isCancelled(), "建造模式管理员应可试点火（整修大厅需要）");
        }

        @Test
        @DisplayName("火焰烧毁方块：大厅一律拦（BlockBurnEvent 无玩家源，建造模式不适用）")
        void burnIsBlockedRegardlessOfBuildMode() {
            Block target = lobbyWorld.getBlockAt(6, 64, 6);

            BlockBurnEvent lobbyBurn = new BlockBurnEvent(target, null);
            listener.onBlockBurn(lobbyBurn);
            assertTrue(lobbyBurn.isCancelled(), "大厅内禁止火焰烧毁方块");

            Block arenaBlock = arenaWorld.getBlockAt(6, 64, 6);
            BlockBurnEvent arenaBurn = new BlockBurnEvent(arenaBlock, null);
            listener.onBlockBurn(arenaBurn);
            assertFalse(arenaBurn.isCancelled(), "竞技场世界的火焰不受大厅保护约束");
        }
    }

    // ---------------------------------------------------------------- 3. round1 修复

    @Nested
    @DisplayName("实体伤害：round1 slice-10-logic-002（非玩家伤害源）")
    class EntityDamageSources {

        /** ★ 本条就是 round1 修复本身：怪物拆大厅装饰必须被拦住。 */
        @Test
        @DisplayName("★ 怪物近战拆大厅盔甲架 → 取消")
        void mobDamageToArmorStandInLobbyIsCancelled() {
            ArmorStand stand = entityIn(lobbyWorld, ArmorStand.class);
            Zombie zombie = entityIn(lobbyWorld, Zombie.class);
            EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                    zombie, stand, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0);

            listener.onEntityDamage(event);

            assertTrue(event.isCancelled(),
                    "【round1 slice-10-logic-002】修复前本分支只在伤害源是玩家时才拦，"
                            + "怪物/箭/岩浆可以拆掉大厅的盔甲架与展示框 —— 而大厅不在 "
                            + "MapRestoreManager 的回滚范围内，拆了就是永久损失");
        }

        @Test
        @DisplayName("★ 环境伤害（无伤害源实体）拆大厅展示框 → 取消")
        void environmentDamageToItemFrameInLobbyIsCancelled() {
            ItemFrame frame = entityIn(lobbyWorld, ItemFrame.class);
            // 火焰/岩浆/仙人掌等无实体源：Paper 会以「伤害源 = 环境占位实体」派发，
            // 这里直接用非玩家实体表达「不是玩家干的」。
            Zombie magmaLike = entityIn(lobbyWorld, Zombie.class);
            EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                    magmaLike, frame, EntityDamageEvent.DamageCause.FIRE_TICK, 2.0);

            listener.onEntityDamage(event);

            assertTrue(event.isCancelled(), "非玩家来源伤害大厅展示框同样必须拦下");
        }

        /** 反向控制：竞技场里的真村民必须照常挨打，否则「村民全灭判负」永远触发不了。 */
        @Test
        @DisplayName("反向控制：竞技场村民被怪物攻击 → 照常放行（核心玩法）")
        void villagerInArenaStillTakesMobDamage() {
            Villager villager = entityIn(arenaWorld, Villager.class);
            Zombie zombie = entityIn(arenaWorld, Zombie.class);
            EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                    zombie, villager, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0);

            listener.onEntityDamage(event);

            assertFalse(event.isCancelled(),
                    "竞技场内的村民必须照常被怪物攻击。若这里红，说明有人把「大厅受保护的类型」"
                            + "当成了全局规则，村民将无敌、失败条件永远无法达成");
        }

        @Test
        @DisplayName("大厅村民被怪物攻击 → 取消（大厅装饰村民归大厅保护管）")
        void villagerInLobbyIsProtected() {
            Villager villager = entityIn(lobbyWorld, Villager.class);
            Zombie zombie = entityIn(lobbyWorld, Zombie.class);
            EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                    zombie, villager, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0);

            listener.onEntityDamage(event);

            assertTrue(event.isCancelled(), "大厅内的村民属于装饰，必须同样受保护");
        }

        /** 玩家伤害源保留建造模式豁免：修复不能把管理员的整修能力一并砍掉。 */
        @Test
        @DisplayName("玩家伤害源保留建造模式豁免；非建造模式玩家仍被拦")
        void playerDamagerKeepsBuildModeExemption() {
            ArmorStand stand = entityIn(lobbyWorld, ArmorStand.class);
            EntityDamageByEntityEvent byNormal = new EntityDamageByEntityEvent(
                    player, stand, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0);
            listener.onEntityDamage(byNormal);
            assertTrue(byNormal.isCancelled(), "非建造模式玩家不得伤害大厅盔甲架");

            listener.toggleBuildMode(player);
            EntityDamageByEntityEvent byBuilder = new EntityDamageByEntityEvent(
                    player, stand, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0);
            listener.onEntityDamage(byBuilder);
            assertFalse(byBuilder.isCancelled(),
                    "建造模式管理员必须仍能处置大厅装饰，否则「整修大厅」无从下手");
        }
    }

    // ---------------------------------------------------------------- 4. 玩家自身

    @Nested
    @DisplayName("玩家伤害 / 饥饿 / 天气")
    class PlayerState {

        @Test
        @DisplayName("大厅内玩家受伤与掉饥饿值 → 取消；竞技场放行")
        void playerDamageAndHungerAreCancelledInLobby() {
            EntityDamageEvent damage = new EntityDamageEvent(
                    player, EntityDamageEvent.DamageCause.FALL, 3.0);
            listener.onDamage(damage);
            assertTrue(damage.isCancelled(), "大厅内玩家不得受伤");

            FoodLevelChangeEvent hunger = new FoodLevelChangeEvent(player, 5);
            listener.onHunger(hunger);
            assertTrue(hunger.isCancelled(), "大厅内玩家不得掉饥饿值");

            movePlayerTo(arenaWorld);
            EntityDamageEvent arenaDamage = new EntityDamageEvent(
                    player, EntityDamageEvent.DamageCause.FALL, 3.0);
            listener.onDamage(arenaDamage);
            assertFalse(arenaDamage.isCancelled(), "竞技场内玩家必须照常受伤（对局核心难度）");

            FoodLevelChangeEvent arenaHunger = new FoodLevelChangeEvent(player, 5);
            listener.onHunger(arenaHunger);
            assertFalse(arenaHunger.isCancelled(), "竞技场内玩家必须照常掉饥饿值");
        }

        @Test
        @DisplayName("非玩家实体受伤不走 player 分支（该分支刻意只管玩家）")
        void nonPlayerVictimIsIgnoredByThePlayerHandler() {
            Zombie victim = entityIn(lobbyWorld, Zombie.class);
            EntityDamageEvent event = new EntityDamageEvent(
                    victim, EntityDamageEvent.DamageCause.FALL, 3.0);

            listener.onDamage(event);

            // 这是设计边界而非遗漏：大厅里的怪物伤害由其它机制处理，本监听器只保人。
            // 断言这一点可以挡住「有人顺手把 instanceof Player 去掉」造成的语义漂移。
            assertFalse(event.isCancelled(),
                    "onDamage 的契约是「只保玩家」；非玩家实体的伤害不归本分支处理");
        }

        @Test
        @DisplayName("转到下雨才拦；转晴不拦")
        void onlyRainInLobbyIsCancelled() {
            WeatherChangeEvent rain = new WeatherChangeEvent(lobbyWorld, true);
            listener.onWeatherChange(rain);
            assertTrue(rain.isCancelled(), "大厅禁止下雨");

            WeatherChangeEvent clear = new WeatherChangeEvent(lobbyWorld, false);
            listener.onWeatherChange(clear);
            assertFalse(clear.isCancelled(),
                    "转晴事件必须放行 —— 若连「雨停」都拦，大厅会永久锁在雨里");

            WeatherChangeEvent arenaRain = new WeatherChangeEvent(arenaWorld, true);
            listener.onWeatherChange(arenaRain);
            assertFalse(arenaRain.isCancelled(), "竞技场天气不受大厅保护约束");
        }
    }

    // ---------------------------------------------------------------- 5. 交互

    @Nested
    @DisplayName("交互拦截")
    class Interaction {

        @Test
        @DisplayName("大厅内右键箱子被拦；右键拉杆放行（无害装饰）")
        void containerIsBlockedButLeverIsHarmless() {
            Block chest = lobbyWorld.getBlockAt(7, 64, 7);
            chest.setType(Material.CHEST);
            PlayerInteractEvent openChest = new PlayerInteractEvent(
                    player, Action.RIGHT_CLICK_BLOCK, null, chest, BlockFace.NORTH);
            listener.onInteract(openChest);
            assertTrue(openChest.isCancelled(), "大厅内不得打开容器");
            assertTrue(sentKeys.contains(DENIED_KEY), "拦下容器交互时要提示玩家");

            Block lever = lobbyWorld.getBlockAt(8, 64, 8);
            lever.setType(Material.LEVER);
            PlayerInteractEvent pullLever = new PlayerInteractEvent(
                    player, Action.RIGHT_CLICK_BLOCK, null, lever, BlockFace.NORTH);
            listener.onInteract(pullLever);
            assertFalse(pullLever.isCancelled(),
                    "拉杆/按钮是本类白名单里的无害装饰，必须放行，否则大厅的机关全废");
        }

        @Test
        @DisplayName("大厅内右键盔甲架/展示框被拦；右键村民放行（商店 NPC 必须可用）")
        void armorStandInteractionBlockedButVillagerAllowed() {
            ArmorStand stand = entityIn(lobbyWorld, ArmorStand.class);
            PlayerInteractEntityEvent onStand = new PlayerInteractEntityEvent(player, stand);
            listener.onInteractEntity(onStand);
            assertTrue(onStand.isCancelled(), "大厅内不得操作盔甲架");

            ItemFrame frame = entityIn(lobbyWorld, ItemFrame.class);
            PlayerInteractEntityEvent onFrame = new PlayerInteractEntityEvent(player, frame);
            listener.onInteractEntity(onFrame);
            assertTrue(onFrame.isCancelled(), "大厅内不得操作物品展示框");

            Villager shopNpc = entityIn(lobbyWorld, Villager.class);
            PlayerInteractEntityEvent onVillager = new PlayerInteractEntityEvent(player, shopNpc);
            listener.onInteractEntity(onVillager);
            assertFalse(onVillager.isCancelled(),
                    "村民交互必须放行 —— 本分支只拦盔甲架与展示框，把村民一并拦下会废掉商店 NPC");
        }
    }

    // ---------------------------------------------------------------- 6. 虚空保护

    @Nested
    @DisplayName("虚空保护")
    class VoidProtection {

        @Test
        @DisplayName("大厅内掉到 Y<=0 → 传送回大厅出生点并清坠落距离")
        void fallingBelowVoidTeleportsToLobbySpawn() {
            Location spawn = new Location(lobbyWorld, 12, 70, 12);
            when(arenas.getLobbySpawn()).thenReturn(spawn);

            PlayerMoveEvent event = new PlayerMoveEvent(player,
                    new Location(lobbyWorld, 0, 5, 0),
                    new Location(lobbyWorld, 0, -1, 0));
            player.setFallDistance(9.0f);

            listener.onMove(event);

            assertEquals(spawn.getWorld().getName(), player.getLocation().getWorld().getName(),
                    "应被送回大厅出生点所在世界");
            assertEquals(12.0, player.getLocation().getX(), 0.001, "应被送回大厅出生点 X");
            assertEquals(70.0, player.getLocation().getY(), 0.001, "应被送回大厅出生点 Y");
            assertEquals(0.0f, player.getFallDistance(), 0.001f,
                    "传送后必须清坠落距离，否则落地瞬间会吃到整段坠落伤害");
        }

        @Test
        @DisplayName("未配置大厅出生点时退回该世界出生点")
        void missingLobbySpawnFallsBackToWorldSpawn() {
            when(arenas.getLobbySpawn()).thenReturn(null);

            PlayerMoveEvent event = new PlayerMoveEvent(player,
                    new Location(lobbyWorld, 0, 5, 0),
                    new Location(lobbyWorld, 0, -1, 0));
            listener.onMove(event);

            assertTrue(player.getLocation().getY() >= 0,
                    "没有配置 lobby.spawn 时也必须真的把玩家捞回来，不能只是什么都不做");
        }

        @Test
        @DisplayName("竞技场世界掉进虚空不归本监听器管（对局内由核心逻辑处理）")
        void fallingInArenaIsIgnored() {
            Location before = new Location(arenaWorld, 0, -1, 0);
            player.teleport(before);

            PlayerMoveEvent event = new PlayerMoveEvent(player,
                    new Location(arenaWorld, 0, 5, 0), before);
            listener.onMove(event);

            assertEquals(-1.0, player.getLocation().getY(), 0.001,
                    "竞技场内的虚空掉落不得被大厅保护悄悄传送走");
        }
    }

    // ---------------------------------------------------------------- 7. 构造

    @Test
    @DisplayName("监听器可构造，且初始不处于建造模式")
    void listenerStartsWithoutBuildMode() {
        assertNotNull(listener);
        assertFalse(listener.isBuildMode(player), "新玩家默认不应带建造模式豁免");
        assertTrue(sentKeys.isEmpty(), "构造阶段不应发送任何 i18n key");
    }
}
