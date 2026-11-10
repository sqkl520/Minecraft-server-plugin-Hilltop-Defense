package com.hilltopvillage.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.admin.AdminDeployManager;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.WaveManager;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link VillagerSystem} 的回归测试。
 *
 * <p><b>本轮瞄准的修复（round1）</b>：{@code getTotalCount()} 由
 * {@code villagers.size()} 改为读 {@code deployedTotal}
 * （{@code VillagerSystem.java:98,155,168,204-206}）。旧实现的失败形态是玩家可见的：
 * 记分板显示 {@code Villagers: 2/3} 时死一只变成 {@code 2/2} —— 分母跟着分子一起缩，
 * 玩家无从判断「总共要守几只」，也看不出还剩几只没死。</p>
 *
 * <p><b>断言策略（关键）</b>：分母不变量必须作为<b>三点性质</b>断言
 * （spawn → N，死一只 → 仍 N，clear → 0），不能只测一个数。
 * 单点断言在<b>有 bug 的实现</b>上同样会通过 —— 刚 spawn 完时
 * {@code villagers.size() == deployedTotal}，两者无法区分。</p>
 *
 * <p><b>为什么要 mock 每只 {@link Villager}</b>：{@code spawnAll()} 在
 * {@code setRemoveWhenFarAway(false)}（{@code :129}）处必抛
 * {@code UnimplementedOperationException}，实测那是 MockBukkit 4.45.0 在本方法里的
 * 第一个未实现调用。用 Mockito mock 掉整只村民即可绕开，且顺带让
 * {@code isValid()/isDead()} 可控 —— 这正是 {@code getAliveCount()} 的唯一输入。</p>
 */
@DisplayName("VillagerSystem 待保护村民")
class VillagerSystemTest extends MockBukkitTestBase {

    private static final String ARENA_ID = "arena1";
    private static final int DEPLOYED = 3;

    private Logger logger;
    private GameManager gameManager;
    private AdminDeployManager deployManager;
    private WaveManager waveManager;
    private MultiArenaManager arenaManager;
    private ArenaInstance arena;
    private WorldMock world;
    private VillagerSystem system;

    private final List<Villager> spawned = new ArrayList<>();

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger("villager-test-" + System.nanoTime());
        GameConfig config = new GameConfig();
        config.setVillagerHealth(20.0);
        HilltopVillagePlugin plugin = MechanicsFixtures.mockPlugin(logger, dataFolder.toFile(), config);
        world = server.addSimpleWorld(ARENA_ID);

        arena = mock(ArenaInstance.class);
        arenaManager = mock(MultiArenaManager.class);
        when(arenaManager.getArena(ARENA_ID)).thenReturn(arena);
        when(plugin.getMultiArenaManager()).thenReturn(arenaManager);

        deployManager = mock(AdminDeployManager.class);
        waveManager = mock(WaveManager.class);
        gameManager = mock(GameManager.class);
        when(gameManager.getAdminDeployManager()).thenReturn(deployManager);
        when(gameManager.getWaveManager()).thenReturn(waveManager);
        // 先取到局部变量再桩：直接在 thenReturn(...) 里调用 plugin.getConfigManager()
        // 会在桩未闭合时重入 plugin 的桩，抛 UnfinishedStubbingException。
        com.hilltopvillage.config.ConfigManager configManager = plugin.getConfigManager();
        when(gameManager.getConfigManager()).thenReturn(configManager);
        when(gameManager.getArenaId()).thenReturn(ARENA_ID);
        when(gameManager.getGameWorld()).thenReturn(world);
        when(gameManager.getPlugin()).thenReturn(plugin);
        when(waveManager.getActiveMobs()).thenReturn(new java.util.HashSet<>());

        system = new VillagerSystem(plugin, gameManager);
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 让下一次 {@code spawnAll()} 生成 {@code count} 只可控村民。
     *
     * <p>三件事缺一不可：</p>
     * <ol>
     *   <li>部署位置列表（否则 {@code spawnAll} 早退，日志说「没有部署村民」）；</li>
     *   <li>{@code world.spawnEntity} 的 spy 替身 —— 用 {@code doReturn} 而非
     *       {@code when(...).thenReturn(...)}，因为 {@code spawnEntity} 是泛型方法，
     *       {@code when} 形式在桩求值时就会真的把实体生成进世界；</li>
     *   <li>每只村民的 PDC —— 未桩时返回 {@code null}，
     *       {@code :150} 的 {@code pdc.set(...)} 立刻 NPE。</li>
     * </ol>
     */
    private void deployVillagers(int count) {
        spawned.clear();
        List<Location> positions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            positions.add(new Location(world, i * 2, 64, 0));
        }
        when(deployManager.getVillagers()).thenReturn(positions);

        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        List<Villager> mocks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Villager villager = mock(Villager.class);
            when(villager.isValid()).thenReturn(true);
            when(villager.isDead()).thenReturn(false);
            when(villager.getPersistentDataContainer()).thenReturn(pdc);
            mocks.add(villager);
        }
        spawned.addAll(mocks);

        World spyWorld = spy(world);
        // 用 doAnswer 逐个返回：spawnEntity 是泛型方法 <T extends Entity> T spawnEntity(...)，
        // 直接传数组会被当成「返回 Object[]」，Mockito 抛
        // WrongTypeOfReturnValue: Object[] cannot be returned by spawnEntity()。
        // 取模而不是自增：spawnAll 可被调用多次（幂等测试），游标必须能回绕。
        java.util.concurrent.atomic.AtomicInteger cursor = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> mocks.get(cursor.getAndIncrement() % mocks.size()))
                .when(spyWorld).spawnEntity(any(Location.class), eq(EntityType.VILLAGER));
        for (int i = 0; i < count; i++) {
            positions.set(i, new Location(spyWorld, i * 2, 64, 0));
        }
    }

    /** 伪造一只村民的死亡事件（实体仍在列表中，但已不再 valid）。 */
    private void killVillager(int index) {
        Villager villager = spawned.get(index);
        when(villager.isValid()).thenReturn(false);

        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(villager);
        when(event.getDrops()).thenReturn(new ArrayList<>());
        system.handleVillagerDeath(event);
    }

    // ---------------------------------------------------------------- 1. 核心修复

    @Test
    @DisplayName("分母不随阵亡递减：部署 3 只、死 1 只后仍是 3，存活 2")
    void totalCountDoesNotShrinkWhenVillagerDies() {
        deployVillagers(DEPLOYED);
        assertEquals(0, system.getTotalCount(), "spawnAll 之前分母应为 0，否则上一局的总数会渗进下一局");

        system.spawnAll();

        assertEquals(DEPLOYED, system.getTotalCount(), "刚生成完时总数应等于部署数");
        assertEquals(DEPLOYED, system.getAliveCount(), "刚生成完时存活数应等于部署数");
        assertEquals(system.getTotalCount(), system.getAliveCount(),
                "刚生成完时分母与分子相等 —— 这条断言在两个实现下都成立，"
                        + "它的作用是把「此刻两者无法区分」这件事写进测试，说明下面那条才是判别性的");

        killVillager(0);

        assertEquals(DEPLOYED, system.getTotalCount(),
                "死一只后总数必须仍是 " + DEPLOYED + "。若显示成 "
                        + system.getAliveCount() + "/" + system.getTotalCount()
                        + " 中后者跟着变成 2，就是 round1 修掉的那个 bug（玩家看不出总共要守几只）");
        assertEquals(DEPLOYED - 1, system.getAliveCount(), "存活数应减 1");
    }

    @Test
    @DisplayName("clearAll 把分母归零，避免下一局沿用上一局的部署数")
    void clearAllResetsTotalCount() {
        deployVillagers(DEPLOYED);
        system.spawnAll();
        killVillager(0);
        assertEquals(DEPLOYED, system.getTotalCount());

        system.clearAll();

        assertEquals(0, system.getTotalCount(),
                "清理后分母必须归零 —— 否则下一局在 spawnAll 之前就会显示上一局的总数");
        assertEquals(0, system.getAliveCount());
        assertFalse(system.hasAlive(), "没有村民时 hasAlive 必须为 false");
    }

    @Test
    @DisplayName("重复 spawnAll 只重算分母，不累加")
    void spawnAllIsIdempotent() {
        deployVillagers(DEPLOYED);
        system.spawnAll();
        system.spawnAll();

        assertEquals(DEPLOYED, system.getTotalCount(),
                "spawnAll 会先 clearAll，因此重复调用不得让分母翻倍");
        assertEquals(DEPLOYED, system.getAliveCount());
    }

    @Test
    @DisplayName("没有部署村民时总数为 0 且打 WARNING")
    void spawnAllWithoutDeploymentWarns() {
        when(deployManager.getVillagers()).thenReturn(new ArrayList<>());
        var capture = attachLogCapture(logger);

        system.spawnAll();

        assertEquals(0, system.getTotalCount());
        assertTrue(capture.anyMatch(line -> line.startsWith(Level.WARNING.getName() + "|")
                        && line.contains("no villagers deployed")),
                "没有村民时「全员阵亡即失败」永远不会触发 —— 这条日志是服主唯一的线索；"
                        + "实际捕获：" + capture.lines());
    }

    // ---------------------------------------------------------------- 2. 伤害白名单

    @Test
    @DisplayName("环境伤害被取消，怪物近战放行")
    void environmentalDamageCancelledButMonsterMeleeAllowed() {
        deployVillagers(1);
        system.spawnAll();
        Villager villager = spawned.get(0);

        EntityDamageEvent suffocation = damageEvent(villager, EntityDamageEvent.DamageCause.SUFFOCATION);
        system.handleVillagerDamage(suffocation);
        verify(suffocation).setCancelled(true);

        EntityDamageEvent lava = damageEvent(villager, EntityDamageEvent.DamageCause.LAVA);
        system.handleVillagerDamage(lava);
        verify(lava).setCancelled(true);

        Monster zombie = mock(Monster.class);
        EntityDamageEvent melee = damageEvent(villager, EntityDamageEvent.DamageCause.ENTITY_ATTACK, zombie);
        system.handleVillagerDamage(melee);
        verify(melee, never()).setCancelled(true);
    }

    /**
     * 玩家造成的「战斗类」伤害也必须被取消。
     *
     * <p>这条守的是白名单的<b>第二半</b>：只查 cause 不查来源，玩家一刀就能屠村。
     * 只测环境伤害的测试对「去掉来源判定」这个改动完全不敏感。</p>
     */
    @Test
    @DisplayName("玩家近战与玩家引爆的 ENTITY_EXPLOSION 被取消")
    void playerCausedCombatDamageCancelled() {
        deployVillagers(1);
        system.spawnAll();
        Villager villager = spawned.get(0);
        PlayerMock player = server.addPlayer();

        EntityDamageEvent playerMelee = damageEvent(villager,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, player);
        system.handleVillagerDamage(playerMelee);
        verify(playerMelee).setCancelled(true);

        // ENTITY_EXPLOSION 在白名单里（为放行甲虫自爆），但玩家引爆时必须被来源判定拦下
        EntityDamageEvent playerTnt = damageEvent(villager,
                EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, player);
        system.handleVillagerDamage(playerTnt);
        verify(playerTnt).setCancelled(true);
    }

    @Test
    @DisplayName("非本系统登记的村民不受影响")
    void unregisteredVillagerDamageIgnored() {
        deployVillagers(1);
        system.spawnAll();

        Villager stranger = mock(Villager.class);
        EntityDamageEvent event = damageEvent(stranger, EntityDamageEvent.DamageCause.LAVA);
        system.handleVillagerDamage(event);

        verify(event, never()).setCancelled(true);
    }

    // ---------------------------------------------------------------- 3. 全灭判负

    @Test
    @DisplayName("全部阵亡触发 triggerDefeat，部分阵亡不触发")
    void allDeadTriggersDefeat() {
        deployVillagers(2);
        system.spawnAll();

        killVillager(0);
        verify(arenaManager, never()).getArena(ARENA_ID);
        verify(arena, never()).triggerDefeat(anyString());

        killVillager(1);
        verify(arena).triggerDefeat("defeat-villagers-dead");
    }

    @Test
    @DisplayName("部分阵亡广播存活数，死亡掉落被清空")
    void partialDeathBroadcastsAliveCountAndClearsDrops() {
        deployVillagers(DEPLOYED);
        system.spawnAll();

        Villager villager = spawned.get(0);
        when(villager.isValid()).thenReturn(false);
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(villager);
        List<org.bukkit.inventory.ItemStack> drops = new ArrayList<>();
        drops.add(new org.bukkit.inventory.ItemStack(org.bukkit.Material.EMERALD, 5));
        when(event.getDrops()).thenReturn(drops);

        system.handleVillagerDeath(event);

        assertTrue(drops.isEmpty(),
                "村民死亡掉落必须被清空 —— 否则玩家/怪物会把 «待保护目标» 当资源点刷");
        verify(event).setDroppedExp(0);
        verify(gameManager).broadcastI18n(eq("villager-died"), eq(String.valueOf(DEPLOYED - 1)));
    }

    // ---------------------------------------------------------------- 工具

    private EntityDamageEvent damageEvent(Villager target, EntityDamageEvent.DamageCause cause) {
        return damageEvent(target, cause, null);
    }

    /**
     * 造一个可控的伤害事件。
     *
     * <p>用 mock 而不是真构造器：Paper 1.21.4 的 {@code EntityDamageEvent} 公开构造器
     * 已废弃，需要 {@code DamageSource}，而 {@code DamageSource} 只能在服务端构建。
     * mock 直接桩 {@code getDamageSource()} 反而更贴近被测代码的读取路径。</p>
     */
    private EntityDamageEvent damageEvent(Villager target, EntityDamageEvent.DamageCause cause,
                                          org.bukkit.entity.Entity source) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(target);
        when(event.getCause()).thenReturn(cause);

        org.bukkit.damage.DamageSource damageSource = mock(org.bukkit.damage.DamageSource.class);
        when(damageSource.getCausingEntity()).thenReturn(source);
        when(damageSource.getDirectEntity()).thenReturn(source);
        when(event.getDamageSource()).thenReturn(damageSource);
        return event;
    }
}
