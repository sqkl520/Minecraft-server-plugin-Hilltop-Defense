package com.hilltopvillage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PlayerData} 的行为测试。
 *
 * <p>该类零 Bukkit 依赖（虽有 {@code Player} 的 import，实际从未使用），因此这里是
 * core 包里唯一可以纯 JUnit 覆盖的状态机。</p>
 *
 * <p>三条被守的缺陷类别：</p>
 * <ol>
 *   <li>{@code alive} 默认 false 会让 {@code GameManager#hasAlivePlayer()} 在开局第一 tick
 *       就误判全灭；</li>
 *   <li>{@code addCurrencyCollected} 接受负数会静默扣减本局收益；</li>
 *   <li>{@code activateSmash} 不播种 {@code lastSmashY} 会让锤子的首 tick 落差从 0 起算，
 *       算出一次巨大的假下落距离。</li>
 * </ol>
 */
class PlayerDataTest {

    private static PlayerData fresh() {
        return new PlayerData(UUID.randomUUID());
    }

    @Test
    @DisplayName("新玩家默认存活 —— 否则开局第一 tick 就判全灭")
    void aliveDefaultsToTrue() {
        assertTrue(fresh().isAlive(),
                "PlayerData 初始 alive=false 会让 hasAlivePlayer() 在开局即返回 false，"
                        + "每局开局立刻走全灭判负");
    }

    @Test
    @DisplayName("addCurrencyCollected 忽略 0 与负数")
    void currencyIgnoresNonPositive() {
        PlayerData data = fresh();
        data.addCurrencyCollected(-5);
        assertEquals(0, data.getCurrencyCollected(), "负数被计入 —— 本局水晶收益会被静默扣减");
        data.addCurrencyCollected(0);
        assertEquals(0, data.getCurrencyCollected(), "0 不应改变计数");
        data.addCurrencyCollected(3);
        assertEquals(3, data.getCurrencyCollected());
        data.addCurrencyCollected(-1);
        assertEquals(3, data.getCurrencyCollected(), "累加过程中的负数同样必须被忽略");
    }

    @Test
    @DisplayName("activateSmash 用 startY 播种 lastSmashY")
    void activateSmashSeedsLastSmashY() {
        PlayerData data = fresh();
        data.activateSmash(78.5, 1234L);
        assertTrue(data.isSmashActive());
        assertEquals(78.5, data.getSmashStartY(), 1e-9);
        assertEquals(1234L, data.getSmashActivateTick());
        assertEquals(78.5, data.getLastSmashY(), 1e-9,
                "lastSmashY 未从 startY 播种 —— 首 tick 落差会按 (当前Y - 0) 计算，得到一次巨大的假下落");
    }

    @Test
    @DisplayName("deactivateSmash 把四个 smash 字段全部归零")
    void deactivateSmashResetsEverything() {
        PlayerData data = fresh();
        data.activateSmash(90.0, 55L);
        data.setLastSmashY(42.0);
        data.deactivateSmash();

        assertFalse(data.isSmashActive(), "smashActive 未复位");
        assertEquals(0.0, data.getSmashStartY(), 1e-9, "smashStartY 未复位");
        assertEquals(0L, data.getSmashActivateTick(), "smashActivateTick 未复位 —— 跨局残留会错算下一次锤击的计时");
        assertEquals(0.0, data.getLastSmashY(), 1e-9, "lastSmashY 未复位 —— 上一局的坐标会被当成这一局的下落起点");
    }

    @Test
    @DisplayName("计数器各自独立累加")
    void countersIncrementIndependently() {
        PlayerData data = fresh();
        assertEquals(0, data.getKills());
        assertEquals(0, data.getDeaths());
        assertEquals(0, data.getNodesRepaired());

        data.addKill();
        data.addKill();
        data.addDeath();
        data.addNodeRepaired();

        assertEquals(2, data.getKills());
        assertEquals(1, data.getDeaths());
        assertEquals(1, data.getNodesRepaired());
        assertEquals(0, data.getCurrencyCollected(), "统计计数不应污染货币计数");
    }

    @Test
    @DisplayName("setAlive 可往返，且与 smash 状态互不影响")
    void aliveFlagTogglesIndependently() {
        PlayerData data = fresh();
        data.setAlive(false);
        assertFalse(data.isAlive());
        data.activateSmash(64.0, 1L);
        assertFalse(data.isAlive(), "activateSmash 不应改变存活标志");
        data.setAlive(true);
        assertTrue(data.isAlive());
        assertTrue(data.isSmashActive(), "setAlive 不应改变 smash 状态");
    }
}
