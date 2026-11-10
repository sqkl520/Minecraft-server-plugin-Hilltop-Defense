package com.hilltopvillage.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link HammerListener} 的回归测试。
 *
 * <p><b>本轮瞄准的修复（round1）</b>：{@code onPlayerMove} 里「超时判定」被移到
 * 「空中早退」<b>之前</b>（{@code HammerListener.java:302-315}）。修复前，
 * {@code !isOnGroundNow} 会先 return，于是悬空（梯子 / 水 / 鞘翅 / 漂浮）中的玩家
 * 永不超时 —— 后果是三重且全部玩家可见：</p>
 * <ol>
 *   <li>蓄力 BossBar 常驻不消失；</li>
 *   <li>{@code onFallDamage} 的免摔落永久生效（等于无敌）；</li>
 *   <li>{@code isSmashActive} 挡住重新蓄力（再也开不了锤）。</li>
 * </ol>
 *
 * <p><b>本组必须成对断言</b>：「空中 + 已超时 → 必须失效」单独测的话，
 * 一个「干脆从不允许猛击」的实现也能通过。同方法内的对照组
 * 「空中 + 未超时 → 必须仍有效」才让这条测试有判别力。</p>
 */
@DisplayName("HammerListener 重锤猛击")
class HammerListenerTest extends MockBukkitTestBase {

    private static final String WORLD = "hilltop_arena1";

    private Logger logger;
    private WorldMock world;
    private GameManager gameManager;
    private PlayerMock player;
    private PlayerData data;
    private GameConfig config;
    private HammerListener listener;

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger("hammer-test-" + System.nanoTime());
        config = new GameConfig();
        HilltopVillagePlugin plugin = MechanicsFixtures.mockPlugin(logger, dataFolder.toFile(), config);

        world = server.addSimpleWorld(WORLD);

        ArenaInstance arena = mock(ArenaInstance.class);
        gameManager = mock(GameManager.class);
        when(arena.getGameManager()).thenReturn(gameManager);
        MultiArenaManager arenaManager = mock(MultiArenaManager.class);
        when(arenaManager.getPlayerArena(any(Player.class))).thenReturn(arena);
        when(plugin.getMultiArenaManager()).thenReturn(arenaManager);

        when(gameManager.getGameWorld()).thenReturn(world);
        when(gameManager.isGameRunning()).thenReturn(true);
        when(gameManager.isSettlementPhase()).thenReturn(false);

        player = server.addPlayer("Hammer");
        player.teleport(new Location(world, 8, 100, 8));

        data = new PlayerData(player.getUniqueId());
        // 两个重载都要桩：HammerListener 调的是 Player 版本，只桩 UUID 版本会让它
        // 拿到 Mockito 的默认 mock，测试因错误的原因通过。
        when(gameManager.getPlayerData(any(Player.class))).thenReturn(data);
        when(gameManager.getPlayerData(any(UUID.class))).thenReturn(data);

        listener = new HammerListener(plugin);
    }

    /** 构造一个「同 Y、有位移」的移动事件：Y 不变 ⇒ 不触发续期，只走超时判定。 */
    private PlayerMoveEvent levelMove() {
        Location from = new Location(world, 8, 100, 8);
        Location to = new Location(world, 8, 100, 9);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
        assertTrue(event.hasChangedPosition(), "前置条件：事件必须被视为位置变化，否则监听器首行就 return");
        return event;
    }

    // ---------------------------------------------------------------- 1. 核心修复

    @Nested
    @DisplayName("超时判定先于空中早退（本轮修复）")
    class TimeoutOrdering {

        @Test
        @DisplayName("空中且已超时 → 猛击失效；空中但未超时 → 保持有效")
        void timeoutFiresWhileAirborne() {
            long timeout = config.getHammerSmashTimeoutTicks();
            assertTrue(timeout > 0, "前置条件：出厂配置的猛击超时应为正数");

            // 场景 A（修复目标）：起手于 tick 0，世界时间已越过超时线，玩家仍悬空
            data.activateSmash(100.0, 0L);
            world.setGameTime(timeout + 50);
            assertFalse(player.isOnGround(), "前置条件：玩家必须处于悬空状态，否则测不到这条路径");

            listener.onPlayerMove(levelMove());

            assertFalse(data.isSmashActive(),
                    "悬空且已超时的猛击必须被回收。若仍是 true，就是 round1 的 bug："
                            + "BossBar 常驻、免摔落永久生效、且玩家再也开不了锤");

            // 场景 B（对照）：同样的悬空状态，但起手时间在超时线之内
            data.activateSmash(100.0, world.getGameTime());
            listener.onPlayerMove(levelMove());

            assertTrue(data.isSmashActive(),
                    "对照：悬空但未超时【必须】保持有效。若这里也是 false，"
                            + "说明实现根本不允许猛击，上面那条断言其实什么都没证明");
        }

        /**
         * 续期必须先于超时判定：正在下降的玩家不能被判超时。
         *
         * <p>守的是 {@code :302-304} 与 {@code :309} 的<b>相对顺序</b>。
         * 若把超时判定挪到续期之前，一次长时间下落（例如从 Y=200 掉到 Y=50）
         * 会在半空被判超时，玩家眼看要落地却突然失去蓄力 —— 伤害归零、
         * 免摔落消失，直接摔死。这是一条只有在「顺序错」时才出现的 bug。</p>
         */
        @Test
        @DisplayName("下降中的玩家每帧续期，不会在半空被判超时")
        void descendingPlayerRenewsBeforeTimeoutCheck() {
            long timeout = config.getHammerSmashTimeoutTicks();
            world.setGameTime(timeout + 50);      // 世界时间已过超时线
            data.activateSmash(200.0, 0L);        // 起手时间很旧

            // 本帧 Y 明显低于上一次（触发续期）：100.0 < 200.0 - 0.01
            Location from = new Location(world, 8, 150, 8);
            Location to = new Location(world, 8, 100, 8);
            listener.onPlayerMove(new PlayerMoveEvent(player, from, to));

            assertTrue(data.isSmashActive(),
                    "正在下降的玩家必须被续期而不是判超时 —— 否则长距离下落会在半空失去蓄力，"
                            + "同时丢掉免摔落，直接把玩家摔死");
            assertEquals(world.getGameTime(), data.getSmashActivateTick(),
                    "续期必须把起手时间刷新到当前 tick");
        }

        @Test
        @DisplayName("结算窗口与旁观状态下猛击被清理")
        void settlementAndSpectatorClearSmash() {
            data.activateSmash(100.0, world.getGameTime());
            when(gameManager.isSettlementPhase()).thenReturn(true);
            listener.onPlayerMove(levelMove());
            assertFalse(data.isSmashActive(),
                    "结算窗口内不得继续蓄力 —— 否则满档猛击会砸在已经定局的对局上");

            when(gameManager.isSettlementPhase()).thenReturn(false);
            data.activateSmash(100.0, world.getGameTime());
            player.setGameMode(org.bukkit.GameMode.SPECTATOR);
            listener.onPlayerMove(levelMove());
            assertFalse(data.isSmashActive(), "旁观者不得保留蓄力状态（幽灵猛击主路径）");
        }
    }

    // ---------------------------------------------------------------- 2. 档位阶梯

    @Nested
    @DisplayName("伤害档位阶梯（纯逻辑）")
    class TierLadder {

        private Method method(String name, Class<?>... params) {
            try {
                Method m = HammerListener.class.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                throw new AssertionError("方法 " + name + " 不存在；若已改名/公开化，请同步本测试", e);
            }
        }

        @SuppressWarnings("unchecked")
        private <T> T invoke(Method m, Object... args) {
            try {
                return (T) m.invoke(listener, args);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("反射调用失败", e);
            }
        }

        /**
         * 边界必须是<b>闭区间下界</b>：{@code >= high → 2}，{@code >= medium → 1}。
         *
         * <p>测四个点而不是两个：{@code 4.99/5.0} 与 {@code 9.99/10.0} 各自成对，
         * 才能区分 {@code >=} 与 {@code >}。只测 5.0 的话，写成 {@code >} 的实现
         * 在 5.0 处返回 0 也会被抓到；但只测 4.99 就抓不到 —— 两端都要有。</p>
         */
        @Test
        @DisplayName("阈值边界为闭区间（4.99/5.0/9.99/10.0）")
        void tierThresholdsAreInclusive() {
            Method tier = method("getHammerTier", GameConfig.class, double.class);
            double medium = config.getHammerMediumCharge();
            double high = config.getHammerHighCharge();

            assertEquals(0, (int) invoke(tier, config, medium - 0.01),
                    "略低于中档阈值应仍是 0 档");
            assertEquals(1, (int) invoke(tier, config, medium),
                    "恰好等于中档阈值应为 1 档（闭区间下界）");
            assertEquals(1, (int) invoke(tier, config, high - 0.01),
                    "略低于高档阈值应仍是 1 档");
            assertEquals(2, (int) invoke(tier, config, high),
                    "恰好等于高档阈值应为 2 档（闭区间下界）");
        }

        @Test
        @DisplayName("三档的伤害与半径各自独立可配")
        void tierDamageAndRadiusComeFromConfig() {
            Method damage = method("getTierDamage", GameConfig.class, int.class);
            Method radius = method("getTierRadius", GameConfig.class, int.class);

            assertEquals(config.getHammerLowDamage(), (double) invoke(damage, config, 0));
            assertEquals(config.getHammerMediumDamage(), (double) invoke(damage, config, 1));
            assertEquals(config.getHammerHighDamage(), (double) invoke(damage, config, 2));

            assertEquals(config.getHammerLowRadius(), (double) invoke(radius, config, 0));
            assertEquals(config.getHammerMediumRadius(), (double) invoke(radius, config, 1));
            assertEquals(config.getHammerHighRadius(), (double) invoke(radius, config, 2));

            // 三档必须真的不同，否则「分级」是假的（配置被写成同一个值也应当被发现）
            assertTrue(config.getHammerLowDamage() < config.getHammerMediumDamage()
                            && config.getHammerMediumDamage() < config.getHammerHighDamage(),
                    "出厂配置的三档伤害应严格递增");
            assertTrue(config.getHammerLowRadius() < config.getHammerMediumRadius()
                            && config.getHammerMediumRadius() < config.getHammerHighRadius(),
                    "出厂配置的三档半径应严格递增");
        }
    }

    // ---------------------------------------------------------------- 3. PDC 防伪

    @Nested
    @DisplayName("重锤识别（PDC 唯一口径）")
    class Identification {

        @Test
        @DisplayName("工厂产物被识别为重锤")
        void createdHammerIsRecognised() {
            ItemStack hammer = listener.createHammer();
            assertNotNull(hammer, "createHammer 不应返回 null（无 ItemsAdder 时应回退原版物品）");
            assertTrue(listener.isHammer(hammer), "工厂产出的锤必须被 isHammer 识别");
        }

        /**
         * 仅同名、无 PDC 的仿制品必须被拒绝。
         *
         * <p>守的是 {@code :155-169} 的「PDC 为唯一口径」：fix5 M-01 删掉了显示名回退，
         * 因为显示名可被铁砧逐字符复现 —— 也就等于伪造入口。若回退被重新加回来，
         * 玩家用铁砧改个名字就能拿到无限锤。</p>
         */
        @Test
        @DisplayName("同名但无 PDC 的仿制品被拒绝")
        void lookalikeWithoutPdcIsRejected() {
            ItemStack genuine = listener.createHammer();
            ItemStack fake = new ItemStack(Material.IRON_AXE);
            ItemMeta meta = fake.getItemMeta();
            meta.setDisplayName(genuine.getItemMeta().getDisplayName());
            if (genuine.getItemMeta().hasLore()) {
                meta.setLore(genuine.getItemMeta().getLore());
            }
            fake.setItemMeta(meta);

            assertFalse(listener.isHammer(fake),
                    "仅复制显示名与 lore 的仿制品必须被拒绝 —— 否则铁砧改名就是无限锤");
            assertFalse(listener.isHammer(new ItemStack(Material.AIR)),
                    "空物品不得被识别为重锤");
            assertFalse(listener.isHammer(null), "null 必须安全返回 false");
        }
    }
}
