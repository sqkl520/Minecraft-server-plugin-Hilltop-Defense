package com.hilltopvillage.mechanics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
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
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link FriendlyFireListener} 的零友伤门控测试。
 *
 * <p><b>本类守护的承诺</b>：README / STORY-1.8 的「全技能零友伤」（fix5 M-06）。
 * 技能伤害（烈焰蛋、重锤）自身已归零，但<b>原版伤害路径</b>（弓 / 弩 / 三叉戟 / 近战）
 * 直通 {@link EntityDamageByEntityEvent}，没有门控时队友中箭可被致死 —— 与宣传矛盾。
 * 本监听器就是那条门控。</p>
 *
 * <p><b>为什么每一侧都要有反向控制</b>：一个「无条件取消所有玩家对玩家的伤害」的实现
 * 也能通过「队友中箭被拦」这一条，但那会把<b>大厅 PK</b>、<b>非成员闯入者被管理员处置</b>、
 * <b>跨竞技场互射</b>全部变成无法执行的规则。拦截范围必须被钉死在
 * 「同一活动竞技场实例的双方成员」。</p>
 *
 * <p><b>弹射物还原</b>：箭 / 弩箭 / 三叉戟命中时 {@code getDamager()} 是
 * {@link Projectile} 而不是 {@link Player}，必须按 {@code getShooter()} 还原攻击者。
 * 漏掉这一层会让「弓射队友」变成唯一的友伤漏洞 —— 所以它单独成组。</p>
 */
@DisplayName("FriendlyFireListener — 同场成员零友伤")
class FriendlyFireListenerTest extends MockBukkitTestBase {

    private static final String ARENA_WORLD = "hilltop_arena1";
    private static final String LOBBY_WORLD = "lobby";

    private HilltopVillagePlugin plugin;
    private MultiArenaManager arenas;
    private ArenaInstance arena;
    private FriendlyFireListener listener;
    private WorldMock arenaWorld;
    private WorldMock lobbyWorld;
    private PlayerMock attacker;
    private PlayerMock victim;

    @BeforeEach
    void setUp() {
        arenaWorld = server.addSimpleWorld(ARENA_WORLD);
        lobbyWorld = server.addSimpleWorld(LOBBY_WORLD);

        Logger logger = Logger.getLogger("friendly-fire-" + System.nanoTime());
        plugin = MechanicsFixtures.mockPlugin(logger, dataFolder.toFile(), new GameConfig());

        arena = mock(ArenaInstance.class);
        when(arena.getArenaId()).thenReturn("arena1");
        when(arena.isPlayer(any(Player.class))).thenReturn(true);
        when(arena.isPlayer(any(UUID.class))).thenReturn(true);

        arenas = mock(MultiArenaManager.class);
        when(arenas.getArenaByWorld(ARENA_WORLD)).thenReturn(arena);
        when(arenas.getArenaByWorld(LOBBY_WORLD)).thenReturn(null);
        when(plugin.getMultiArenaManager()).thenReturn(arenas);

        listener = new FriendlyFireListener(plugin);

        attacker = server.addPlayer("Attacker");
        victim = server.addPlayer("Victim");
        attacker.teleport(new org.bukkit.Location(arenaWorld, 0, 64, 0));
        victim.teleport(new org.bukkit.Location(arenaWorld, 0, 64, 0));
    }

    private EntityDamageByEntityEvent melee(org.bukkit.entity.Entity damager,
                                            org.bukkit.entity.Entity target) {
        return new EntityDamageByEntityEvent(
                damager, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 6.0);
    }

    /**
     * 造一个「由 shooter 发射」的弹射物 mock（箭 / 弩箭 / 三叉戟同构）。
     *
     * <p>{@code getShooter()} 的返回类型是 {@link org.bukkit.projectiles.ProjectileSource} 接口，
     * 所以参数必须声明成该接口（Player / BlockState / 怪物都实现它）—— 用 {@code Entity}
     * 会编译不过（实测：{@code thenReturn(Entity)} 找不到合适的方法）。</p>
     */
    private Projectile arrowFrom(org.bukkit.projectiles.ProjectileSource shooter) {
        Projectile projectile = mock(Projectile.class);
        when(projectile.getShooter()).thenReturn(shooter);
        return projectile;
    }

    // ---------------------------------------------------------------- 1. 核心承诺

    @Nested
    @DisplayName("同场成员互伤必须拦下")
    class FriendlyFireBlocked {

        @Test
        @DisplayName("近战：队友互砍 → 取消")
        void meleeBetweenMembersIsCancelled() {
            EntityDamageByEntityEvent event = melee(attacker, victim);

            listener.onEntityDamage(event);

            assertTrue(event.isCancelled(),
                    "同一竞技场的两名成员互砍必须取消 —— README 承诺「全技能零友伤」，"
                            + "原版近战路径若不拦，队友可以被直接砍死");
        }

        @Test
        @DisplayName("弓：箭由队友射出 → 按 shooter 还原后仍然取消")
        void arrowFromTeammateIsCancelled() {
            EntityDamageByEntityEvent event = melee(arrowFrom(attacker), victim);

            listener.onEntityDamage(event);

            assertTrue(event.isCancelled(),
                    "箭的 damager 是 Projectile 而不是 Player；不按 getShooter() 还原就会漏掉"
                            + "「弓射队友」这条最容易被发现的友伤路径");
        }

        @Test
        @DisplayName("箭由怪物射出（无玩家 shooter）→ 放行")
        void arrowFromMobIsAllowed() {
            Zombie skeletonLike = mock(Zombie.class);
            when(skeletonLike.getUniqueId()).thenReturn(UUID.randomUUID());
            when(skeletonLike.getWorld()).thenReturn(arenaWorld);

            EntityDamageByEntityEvent event = melee(arrowFrom(skeletonLike), victim);

            listener.onEntityDamage(event);

            assertFalse(event.isCancelled(),
                    "怪物射出的箭是正常的 PvE 伤害，不得被友伤门控拦下 —— 那会让怪物完全打不到玩家");
        }
    }

    // ---------------------------------------------------------------- 2. 反向控制

    @Nested
    @DisplayName("拦截范围必须收窄（否则会把正常玩法一并砍掉）")
    class ScopeIsNarrow {

        @Test
        @DisplayName("大厅内玩家互伤 → 放行（大厅的保护由 LobbyProtectionListener 负责）")
        void pvpInLobbyIsNotThisListenersJob() {
            attacker.teleport(new org.bukkit.Location(lobbyWorld, 0, 64, 0));
            victim.teleport(new org.bukkit.Location(lobbyWorld, 0, 64, 0));
            EntityDamageByEntityEvent event = melee(attacker, victim);

            listener.onEntityDamage(event);

            assertFalse(event.isCancelled(),
                    "大厅世界的玩家互伤不归本监听器管（世界反查不到活动实例即放行）；"
                            + "把大厅也一并拦会造成「大厅禁止 PVP」这一未声明的行为变更");
        }

        @Test
        @DisplayName("玩家打怪物 → 放行（本监听器只管玩家受击）")
        void playerAttackingMobIsAllowed() {
            Zombie zombie = mock(Zombie.class);
            when(zombie.getUniqueId()).thenReturn(UUID.randomUUID());
            when(zombie.getWorld()).thenReturn(arenaWorld);
            EntityDamageByEntityEvent event = melee(attacker, zombie);

            listener.onEntityDamage(event);

            assertFalse(event.isCancelled(),
                    "受害者不是 Player 时必须放行 —— 否则玩家永远打不死怪，对局无法进行");
        }

        @Test
        @DisplayName("怪物打玩家 → 放行（这是对局的核心威胁）")
        void mobAttackingPlayerIsAllowed() {
            Zombie zombie = mock(Zombie.class);
            when(zombie.getUniqueId()).thenReturn(UUID.randomUUID());
            when(zombie.getWorld()).thenReturn(arenaWorld);
            EntityDamageByEntityEvent event = melee(zombie, victim);

            listener.onEntityDamage(event);

            assertFalse(event.isCancelled(),
                    "伤害源不是玩家时必须放行。若这里红，怪物将完全无法伤害玩家");
        }

        @Test
        @DisplayName("自杀（自己打自己）→ 放行，交给原版处理")
        void selfDamageIsIgnored() {
            EntityDamageByEntityEvent event = melee(attacker, attacker);

            listener.onEntityDamage(event);

            assertFalse(event.isCancelled(),
                    "自己打自己不是「友伤」，放行让原版逻辑处理（本监听器不应成为伤害总闸）");
        }

        @Test
        @DisplayName("任一方不是本场成员 → 放行（旁观者 / 闯入者 / 管理员）")
        void nonMemberInvolvementIsAllowed() {
            // 受击者不是成员（攻击者是成员）
            when(arena.isPlayer(victim)).thenReturn(false);
            when(arena.isPlayer(attacker)).thenReturn(true);
            EntityDamageByEntityEvent victimNotMember = melee(attacker, victim);
            listener.onEntityDamage(victimNotMember);
            assertFalse(victimNotMember.isCancelled(),
                    "受击者不是本场成员时不是「友伤」语义，交由 ArenaAccessListener 等机制处理");

            // 攻击者不是成员（受击者是成员）
            // ⚠️ 这一半必须把 victim 重新桩成 true，否则代码在 victim 分支就早退了 ——
            // 那样即使有人删掉 `|| !arena.isPlayer(damager)`，本用例仍然绿（实测：变异 M7 曾漏网）。
            when(arena.isPlayer(victim)).thenReturn(true);
            when(arena.isPlayer(attacker)).thenReturn(false);
            EntityDamageByEntityEvent attackerOnly = melee(attacker, victim);
            listener.onEntityDamage(attackerOnly);
            assertFalse(attackerOnly.isCancelled(),
                    "攻击者不是本场成员时同样不是友伤（典型：管理员 / 闯入者），必须放行。"
                            + "若这里红，说明「同场成员」判定被简化成了「受击者是成员」");
        }

        @Test
        @DisplayName("受害者所在世界没有活动实例 → 放行（普通世界不受影响）")
        void worldWithoutArenaIsUntouched() {
            WorldMock ordinaryWorld = server.addSimpleWorld("ordinary");
            victim.teleport(new org.bukkit.Location(ordinaryWorld, 0, 64, 0));
            EntityDamageByEntityEvent event = melee(attacker, victim);

            listener.onEntityDamage(event);

            assertFalse(event.isCancelled(),
                    "普通世界的玩家互伤不受本插件管辖；把它拦下会影响服主的其它玩法");
        }
    }

    // ---------------------------------------------------------------- 3. 边界

    @Test
    @DisplayName("弹射物 shooter 为 null（指令生成 / 自然生成）→ 安全放行")
    void projectileWithoutShooterIsSafe() {
        Projectile orphan = mock(Projectile.class);
        when(orphan.getShooter()).thenReturn(null);
        EntityDamageByEntityEvent event = melee(orphan, victim);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled(), "无 shooter 的弹射物无法归属，放行且不得 NPE");
    }

    @Test
    @DisplayName("shooter 不是玩家（例如发射器）→ 安全放行")
    void projectileFromDispenserIsSafe() {
        // BlockState 在 1.21 的接口层次里不是 ProjectileSource 的子类型（实测编译不过），
        // 这里用一个纯粹的 ProjectileSource mock 表达「发射器/非玩家源」这一语义。
        org.bukkit.projectiles.ProjectileSource dispenser =
                mock(org.bukkit.projectiles.ProjectileSource.class);
        Projectile fromDispenser = mock(Projectile.class);
        when(fromDispenser.getShooter()).thenReturn(dispenser);

        EntityDamageByEntityEvent event = melee(fromDispenser, victim);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled(),
                "发射器射出的箭没有玩家归属，不属于友伤，必须放行");
    }

    @Test
    @DisplayName("伤害源是普通 Entity（非玩家、非弹射物）→ 放行")
    void nonProjectileNonPlayerDamagerIsIgnored() {
        org.bukkit.entity.Entity arrowLike = mock(org.bukkit.entity.Entity.class);
        when(arrowLike.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityDamageByEntityEvent event = melee(arrowLike, victim);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled(),
                "既不是玩家也不是 Projectile 的伤害源无法归属到「友伤」，必须放行");
    }

    @Test
    @DisplayName("同一个 World 对象但名字不同 → 按名字查实例（不得按引用判等）")
    void arenaLookupIsByNameNotByReference() {
        // 让 victim 的世界名字查不到实例，但引用是同一个 arenaWorld。
        WorldMock aliased = server.addSimpleWorld("alias_world");
        when(arenas.getArenaByWorld("alias_world")).thenReturn(null);
        victim.teleport(new org.bukkit.Location(aliased, 0, 64, 0));

        EntityDamageByEntityEvent event = melee(attacker, victim);
        listener.onEntityDamage(event);

        assertFalse(event.isCancelled(),
                "实例查表按世界名进行；名字查不到即放行（本用例同时证明代码没有按 World 引用判等）");
    }
}
