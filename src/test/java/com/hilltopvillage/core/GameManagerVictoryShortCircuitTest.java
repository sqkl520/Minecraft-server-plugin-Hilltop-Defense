package com.hilltopvillage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link GameManager} 的结算状态机测试：最后一波清空必须<b>当场判胜</b>。
 *
 * <p>被守的真实缺陷（round1 修复，本类是它的回归网）：{@code onWaveComplete()} 一旦
 * 在最后一波也先转 {@link GameState#WAVE_INTERMISSION}，休整期里任何一次
 * {@code /hilltop leave} 都会让 {@code removeOfflinePlayer()} 的 min-players 检查
 * 走 {@code endGame(false)} —— 已经通关的一局被记成失败，且随后的排队回调因状态已变
 * 不再开波，胜利<b>永久丢失</b>。</p>
 *
 * <p><b>夹具取舍</b>：用插件自己启用的真实 {@link ArenaInstance}（{@code arena1}），
 * 因此 {@code getArenaMinPlayers()} / {@code getArenaVictoryWaves()} 解析到
 * {@code arenas.yml} 的 per-arena 值（1 人开局、20 波）。全局 mob cap 置 0，
 * 让 {@code spawnWave} 走「已满容量」的同步早退分支 —— 这样既不需要 B-2
 * （{@code Bukkit.getMobGoals()} 抛异常）也不需要 B-3 的怪物 spy 夹具，
 * 同时还能天然断言 {@code waveSpawning} 的置位顺序。</p>
 */
class GameManagerVictoryShortCircuitTest extends MockBukkitTestBase {

    private HilltopVillagePlugin plugin;
    private ArenaInstance arena;
    private GameManager gameManager;
    private PlayerMock player;
    /** 记录 {@code onGameEndCallback} 收到的每次 victory 值。 */
    private final List<Boolean> endCalls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        plugin = loadFullPluginWithArenas();
        arena = plugin.getMultiArenaManager().getArena("arena1");
        assertNotNull(arena, "arena1 未被启用 —— 夹具失效（loadFullPluginWithArenas 的 20 tick 不够？）");
        gameManager = arena.getGameManager();
        assertNotNull(gameManager, "ArenaInstance 没有 GameManager");

        // 部署：arenas.yml 出厂模板的 spawn-points 是空的，startGame 会静默 return 保持 WAITING
        World world = server.getWorld("hilltop_arena1");
        assertNotNull(world, "hilltop_arena1 世界不存在");
        world.getChunkAt(0, 0).load();
        Location core = new Location(world, 0.5, 64, 0.5);
        List<Location> spawns = List.of(new Location(world, 3.5, 64, 3.5));
        gameManager.setCoreLocation(core);
        gameManager.setSpawnPoints(spawns);
        gameManager.setMobSpawnPoints(spawns);

        // 全局 mob cap 置 0：spawnWave 在「已满容量」处同步早退并内联调用 onComplete，
        // 于是本类完全不触达 Bukkit.getMobGoals()（MockBukkit 未实现）。
        GameConfig config = plugin.getConfigManager().getActiveConfig();
        config.setGlobalMobCap(0);

        gameManager.setOnGameEndCallback(endCalls::add);

        player = server.addPlayer("VictoryTester");
        gameManager.addPlayer(player);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void setWave(int wave) throws Exception {
        field(GameManager.class, "currentWave").setInt(gameManager, wave);
    }

    private void invokeOnWaveComplete() throws Exception {
        Method method = GameManager.class.getDeclaredMethod("onWaveComplete");
        method.setAccessible(true);
        method.invoke(gameManager);
    }

    private boolean waveSpawning() throws Exception {
        return field(GameManager.class, "waveSpawning").getBoolean(gameManager);
    }

    @Test
    @DisplayName("开局：spawnWave 同步早退后 waveSpawning 必须已复位为 false")
    void startLeavesWaveSpawningFalse() throws Exception {
        gameManager.startGame();

        assertEquals(GameState.RUNNING, gameManager.getState(),
                "startGame 未进入 RUNNING（人数/spawn 点夹具失效？）");
        assertEquals(1, gameManager.getCurrentWave(), "开局应推进到第 1 波");
        assertFalse(waveSpawning(),
                "waveSpawning 残留 true —— checkWaveCompletion 的清波判定会永久短路，波次永不结束");
    }

    @Test
    @DisplayName("最后一波清空当场判胜，不进入 WAVE_INTERMISSION")
    void finalWaveShortCircuitsToVictory() throws Exception {
        gameManager.startGame();
        int totalWaves = gameManager.getTotalWaves();
        assertTrue(totalWaves > 0, "totalWaves 异常：" + totalWaves);

        setWave(totalWaves);
        invokeOnWaveComplete();

        assertEquals(GameState.VICTORY, gameManager.getState(),
                "最后一波清空后状态应为 VICTORY，实际 " + gameManager.getState()
                        + "。若这里是 WAVE_INTERMISSION，休整期内任何一次离场都会把通关局判负");
        assertEquals(List.of(Boolean.TRUE), endCalls,
                "onGameEndCallback 必须以 true 被调用且只调一次，实际：" + endCalls);
        assertEquals(totalWaves, gameManager.getCurrentWave(),
                "胜利时不应重置波次号（重置是 stopGame 的职责）");
    }

    @Test
    @DisplayName("已判胜后 removeOfflinePlayer 不能把胜利翻成失败")
    void removeOfflinePlayerCannotFlipWonGame() throws Exception {
        gameManager.startGame();
        setWave(gameManager.getTotalWaves());
        invokeOnWaveComplete();
        assertEquals(GameState.VICTORY, gameManager.getState(), "前置条件：应当已判胜");
        endCalls.clear(); // 只观察胜利之后的调用

        gameManager.removeOfflinePlayer(player.getUniqueId());

        assertEquals(GameState.VICTORY, gameManager.getState(),
                "离场判负把已通关的一局改成了 " + gameManager.getState()
                        + " —— 这就是「胜利永久丢失」的历史 bug");
        assertTrue(endCalls.isEmpty(),
                "胜利后再触发了一次结算回调：" + endCalls + "（不应有 false）");
        assertEquals(0, gameManager.getPlayerCount());
    }

    @Test
    @DisplayName("非最后一波清空仍正常进入 WAVE_INTERMISSION（对照，防止守卫写宽）")
    void nonFinalWaveStillEntersIntermission() throws Exception {
        gameManager.startGame();
        assertTrue(gameManager.getTotalWaves() > 2, "本用例需要 totalWaves > 2");
        setWave(1);

        invokeOnWaveComplete();

        assertEquals(GameState.WAVE_INTERMISSION, gameManager.getState(),
                "中间波清空必须进入休整期，否则守卫被写成了「任何波都判胜」");
        assertTrue(endCalls.isEmpty(), "中间波不应触发结算回调：" + endCalls);
        assertEquals(1, gameManager.getCurrentWave(), "中间波清空不改波次号");
    }

    @Test
    @DisplayName("休整期内人数不足仍能判负（守卫只放行「已通关」这一种情形）")
    void intermissionStillAllowsDefeatOnPlayerLoss() throws Exception {
        gameManager.startGame();
        setWave(1);
        invokeOnWaveComplete();
        assertEquals(GameState.WAVE_INTERMISSION, gameManager.getState());
        endCalls.clear();

        gameManager.removeOfflinePlayer(player.getUniqueId());

        assertEquals(GameState.DEFEAT, gameManager.getState(),
                "休整期掉人应判负 —— 若这里是 VICTORY，说明 removeOfflinePlayer 的失败分支被写坏了");
        assertEquals(List.of(Boolean.FALSE), endCalls);
        assertFalse(endCalls.contains(Boolean.TRUE));
    }
}
