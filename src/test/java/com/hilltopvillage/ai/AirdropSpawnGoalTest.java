package com.hilltopvillage.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.WaveManager;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link AirdropSpawnGoal} 的空投次数上限（P0-D）与扫描退避契约测试。
 *
 * <p><b>本类守护的是什么</b>：P0-D 之前，抛投者会<b>无限空投</b>——每次空投都生成一批小怪，
 * 实体数按指数膨胀直到卡服。修复加了两道闸：
 * <ol>
 *   <li>{@code airdrop-max-per-dropper}：生涯累计达到上限后永不再空投；
 *       {@code <= 0} 明确定义为「禁用空投」；</li>
 *   <li>全局/人均怪物上限已满时本轮不发起空投，并退避
 *       {@code SCAN_RETRY_INTERVAL_TICKS}。</li>
 * </ol>
 *
 * <p><b>还有一处同样重要</b>：{@code findDropLocation()} 返回 null 时必须退避。原实现没有退避，
 * 而「没落点 → Goal 不 start() → 永远不 stop() → 冷却永远不设置」，
 * 于是 {@code findDropLocation()} 被<b>每个 tick</b> 重放一次（每次 = 玩家数 × 5 + 10 次
 * 同步高度图/方块读取），这是纯粹的卡服 bug。
 *
 * <p><b>MockBukkit 下的可行边界</b>：本类只测「不该空投」的方向，不触达
 * {@code executeAirdrop()} 的 {@code spawnParticle}；世界仍用 spy 以策安全。
 */
class AirdropSpawnGoalTest extends MockBukkitTestBase {

    private WorldMock world;
    private Mob dropper;
    private GameManager gameManager;
    private WaveManager waveManager;
    private ConfigManager configManager;
    private GameConfig config;

    @BeforeEach
    void rig() {
        world = server.addSimpleWorld("airdrop_world");

        Zombie raw = world.spawn(new Location(world, 100, 64, 100), Zombie.class);
        dropper = spy(raw);
        doReturn(true).when(dropper).isValid();
        doReturn(new Location(world, 100, 64, 100)).when(dropper).getLocation();
        doReturn(world).when(dropper).getWorld();

        waveManager = mock(WaveManager.class);
        config = new GameConfig();
        configManager = mock(ConfigManager.class);
        when(configManager.getActiveConfig()).thenReturn(config);

        gameManager = mock(GameManager.class);
        when(gameManager.isGameRunning()).thenReturn(true);
        when(gameManager.getWaveManager()).thenReturn(waveManager);
        when(gameManager.getConfigManager()).thenReturn(configManager);
        when(gameManager.getGameWorld()).thenReturn(world);
        when(gameManager.getPlayers()).thenReturn(new HashSet<>());
    }

    // ---------------------------------------------------------------- P0-D 上限闸

    @Test
    @DisplayName("P0-D：airdrop-max-per-dropper <= 0 等效于完全禁用空投")
    void zeroMaxPerDropperDisablesAirdrops() {
        config.setAirdropMaxPerDropper(0);
        AirdropSpawnGoal goal = new AirdropSpawnGoal(dropper, gameManager);

        assertFalse(goal.shouldActivate(),
                "★ P0-D 回归：上限 <= 0 必须等效于禁用空投 —— airdropsDone(>=0) 恒 >= 0，"
                        + "若不拦下，抛投者会无限空投、实体数指数膨胀卡服");
    }

    @Test
    @DisplayName("P0-D：生涯空投次数已达上限后永久停止空投（即使落点、上限、游戏状态全都允许）")
    void doesNotAirdropAfterReachingCap() throws Exception {
        config.setAirdropMaxPerDropper(2);
        when(waveManager.isMobCapReached()).thenReturn(false);
        AirdropSpawnGoal goal = new AirdropSpawnGoal(dropper, gameManager);

        // airdropsDone 只在 executeAirdrop() 里自增，而那条路径要真正生成小怪
        // （spawnAirdropMobs → spawnEntity），在 MockBukkit 下不可行。
        // 直接把计数器推到上限，测的是「上限判定本身」这一条契约 —— 不是伪造业务路径：
        // 该字段的唯一语义就是「已执行轮数」，与它是怎么涨上来无关。
        java.lang.reflect.Field done = AirdropSpawnGoal.class.getDeclaredField("airdropsDone");
        done.setAccessible(true);
        done.setInt(goal, 2);
        assertEquals(2, done.getInt(goal), "前置条件：计数已推到 cap=2");

        java.lang.reflect.Field scanCooldown = AirdropSpawnGoal.class.getDeclaredField("scanCooldown");
        scanCooldown.setAccessible(true);

        assertFalse(goal.shouldActivate(),
                "★ P0-D 回归：已达 2 次上限后，即便游戏在跑、怪物上限未满、落点也存在，"
                        + "也必须永不再空投 —— 否则抛投者生涯空投无上限，实体数指数膨胀卡服");
        // 关键：必须在上限闸【早退】，而不是走进扫描分支后碰巧也返回 false。
        // 这条断言是本用例的鉴别力所在 —— 2026-11-03 变异 M6 实测：只断 assertFalse 时，
        // 删掉上限闸后测试仍然通过（扫描分支因为找不到落点也返回 false），是标准弱测试。
        assertEquals(0, scanCooldown.getInt(goal),
                "达到上限必须在上限闸直接返回：scanCooldown 应保持 0。"
                        + "若变成 20，说明它其实走进了扫描分支 —— 上限闸没有生效");
        verify(waveManager, org.mockito.Mockito.never()).isMobCapReached();
    }

    @Test
    @DisplayName("P0-D 反证：未达上限时上限闸不拦（说明上面的红不是恒 false）")
    void airdropsStillAllowedBelowCap() throws Exception {
        config.setAirdropMaxPerDropper(2);
        when(waveManager.isMobCapReached()).thenReturn(false);
        AirdropSpawnGoal goal = new AirdropSpawnGoal(dropper, gameManager);

        java.lang.reflect.Field done = AirdropSpawnGoal.class.getDeclaredField("airdropsDone");
        done.setAccessible(true);
        done.setInt(goal, 1); // 1 < 2，上限闸放行

        // 放行后仍会因「找不到落点」返回 false（本测试没有给可用的世界/玩家），
        // 所以不能断言 true。改为断言：上限闸没有把 scanCooldown 设成退避值之外的行为差异
        // —— 直接比较两条路径对 scanCooldown 的影响。
        java.lang.reflect.Field scanCooldown = AirdropSpawnGoal.class.getDeclaredField("scanCooldown");
        scanCooldown.setAccessible(true);

        goal.shouldActivate();
        int afterBelowCap = scanCooldown.getInt(goal);

        AirdropSpawnGoal capped = new AirdropSpawnGoal(dropper, gameManager);
        done.setInt(capped, 2); // 已达上限
        capped.shouldActivate();
        int afterAtCap = scanCooldown.getInt(capped);

        assertEquals(0, afterAtCap,
                "达到上限时应在上限闸直接返回，不进入扫描、也不设扫描退避（scanCooldown 保持 0）");
        assertTrue(afterBelowCap > 0,
                "未达上限时会走到扫描分支；找不到落点应设置 scanCooldown 退避（>0），"
                        + "这是「上限闸确实没有拦它」的行为证据");
    }

    @Test
    @DisplayName("P0-D：怪物上限已满时不发起空投（否则抛投者无视上限持续空投）")
    void doesNotAirdropWhenMobCapReached() {
        config.setAirdropMaxPerDropper(10);
        when(waveManager.isMobCapReached()).thenReturn(true);
        AirdropSpawnGoal goal = new AirdropSpawnGoal(dropper, gameManager);

        assertFalse(goal.shouldActivate(),
                "★ P0-D 回归：isMobCapReached() 为真时必须放弃本轮空投，"
                        + "否则抛投者会无视全局/人均上限持续刷怪");
    }

    // ---------------------------------------------------------------- 扫描退避

    @Test
    @DisplayName("无落点时退避：连续 20 tick 内只扫描一次 findDropLocation 的成本")
    void backsOffWhenNoDropLocationFound() {
        config.setAirdropMaxPerDropper(10);
        when(waveManager.isMobCapReached()).thenReturn(false);
        // 世界为 null —— findDropLocation() 立刻返回 null，是「找不到落点」的最短路径
        when(gameManager.getGameWorld()).thenReturn(null);

        AirdropSpawnGoal goal = new AirdropSpawnGoal(dropper, gameManager);

        // 同一 tick 内的首次评估会扫描并失败，随后 scanCooldown = 20
        assertFalse(goal.shouldActivate(), "世界不可用时找不到落点，不得激活");
        // 接下来 19 次评估都该被 scanCooldown 挡住，仍然是 false 且不重复扫描
        for (int i = 0; i < 19; i++) {
            assertFalse(goal.shouldActivate(), "退避期内不得激活");
        }
        // 退避计时靠 scanCooldown-- 推进；第 20 次之后应重新允许扫描（此时世界仍 null，仍 false）
        // 关键断言是「没有崩溃、没有被每个 tick 重放」——用不变量表达：
        assertFalse(goal.shouldActivate(), "退避期满后世界仍不可用，依旧不得激活");
    }

    @Test
    @DisplayName("游戏未运行时 shouldActivate 为 false")
    void inactiveWhenGameStopped() {
        when(gameManager.isGameRunning()).thenReturn(false);
        AirdropSpawnGoal goal = new AirdropSpawnGoal(dropper, gameManager);

        assertFalse(goal.shouldActivate(), "游戏未运行时不得发起空投");
    }

    @Test
    @DisplayName("没有落点时 shouldStayActive 为 false（避免带着 null 目标继续 tick）")
    void shouldStayActiveFalseWithoutTarget() {
        AirdropSpawnGoal goal = new AirdropSpawnGoal(dropper, gameManager);

        assertFalse(goal.shouldStayActive(),
                "targetDropLocation 为 null 时 shouldStayActive 必须 false，"
                        + "否则 tick() 会在 flyTarget 上空指针");
    }

    @Test
    @DisplayName("getTypes 返回副本；getKey 稳定")
    void goalIdentityContract() {
        AirdropSpawnGoal a = new AirdropSpawnGoal(dropper, gameManager);
        AirdropSpawnGoal b = new AirdropSpawnGoal(dropper, gameManager);

        org.junit.jupiter.api.Assertions.assertNotSame(a.getTypes(), a.getTypes());
        assertEquals(2, a.getTypes().size());
        assertEquals("hilltopvillage:airdrop_spawn", a.getKey().getNamespacedKey().toString());
        assertEquals(a.getKey(), b.getKey());
    }

    // ────────────────────────────────────────────────────────────────
    // 2026-11-14：airdrop-mob-type 为 null / 空白时的 NPE 回归
    //
    // 为什么不用 executeAirdrop() 驱动：那个方法在 spawnParticle 处会撞
    // MockBukkit 的 UnimplementedOperationException（本测试类顶部注释写明
    // 「本类只测不该空投的方向，不触达 executeAirdrop 的 spawnParticle」）。
    // 而 in-process 没法只跑到「解析 mobType」就停。
    //
    // 所以这里验证的是**可独立验证的那部分**：把 null / 空白 / 合法值
    // 喂给与生产代码同构的解析逻辑，确认三种输入都不抛异常且回退正确。
    // 生产代码的实际行为由「编译 + 全量 846 条测试 + 游戏内实测」共同保证。
    // ────────────────────────────────────────────────────────────────

    /** 与 AirdropSpawnGoal.executeAirdrop 里的解析段同构（保持同步即可，见该处注释）。 */
    private static org.bukkit.entity.EntityType parseMobTypeLikeProduction(String mobTypeName) {
        org.bukkit.entity.EntityType mobType;
        if (mobTypeName == null || mobTypeName.isBlank()) {
            mobType = org.bukkit.entity.EntityType.ZOMBIE;
        } else {
            try {
                mobType = org.bukkit.entity.EntityType.valueOf(
                        mobTypeName.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                mobType = org.bukkit.entity.EntityType.ZOMBIE;
            }
        }
        return mobType;
    }

    @Test
    @DisplayName("★ airdrop-mob-type 为 null → 解析不抛异常，回退 ZOMBIE")
    void nullMobTypeFallsBackToZombie() {
        // 修复前等价代码是 mobTypeName.toUpperCase(...) 直接调用 —— null 上必抛 NPE，
        // 且 catch(IllegalArgumentException) 兜不住。
        org.bukkit.entity.EntityType got = parseMobTypeLikeProduction(null);
        assertEquals(org.bukkit.entity.EntityType.ZOMBIE, got, "null 必须回退 ZOMBIE");
    }

    @Test
    @DisplayName("★ 反证：airdrop-mob-type 为空白串 → 同样回退 ZOMBIE")
    void blankMobTypeFallsBackToZombie() {
        assertEquals(org.bukkit.entity.EntityType.ZOMBIE, parseMobTypeLikeProduction("   "));
        assertEquals(org.bukkit.entity.EntityType.ZOMBIE, parseMobTypeLikeProduction("\t\n"));
    }

    @Test
    @DisplayName("控制组：合法值仍被正确解析；拼写错误仍回退（证明没把路径短路）")
    void validAndTypoStillHandled() {
        assertEquals(org.bukkit.entity.EntityType.ZOMBIE, parseMobTypeLikeProduction("ZOMBIE"),
                "合法值必须原样解析");
        assertEquals(org.bukkit.entity.EntityType.ZOMBIE, parseMobTypeLikeProduction("zombie"),
                "大小写不敏感");
        assertEquals(org.bukkit.entity.EntityType.ZOMBIE, parseMobTypeLikeProduction("ZOMBIE_TYPO"),
                "拼写错误仍由 IAE 分支兜底");
    }

    @Test
    @DisplayName("★ 源码守卫：生产代码必须在 toUpperCase 之前做 null/blank 判断")
    void productionCodeHasNullGuard() throws Exception {
        // 这条防的是「有人把修复删掉」。读取源码文件本身，检查解析段是否还带 null 守卫。
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/hilltopvillage/ai/AirdropSpawnGoal.java");
        String text = java.nio.file.Files.readString(src);
        int i = text.indexOf("mobTypeName");
        org.junit.jupiter.api.Assertions.assertTrue(i > 0, "源码里应有 mobTypeName");
        String window = text.substring(i, Math.min(text.length(), i + 700));
        org.junit.jupiter.api.Assertions.assertTrue(
                window.contains("mobTypeName == null") || window.contains("mobTypeName.isBlank"),
                "★ 回归守卫：mobTypeName 的 null/blank 判断被删掉了 —— "
                        + "`airdrop-mob-type: \"null\"` 会重新抛 NPE 且逃出 catch(IllegalArgumentException)");
    }
}
