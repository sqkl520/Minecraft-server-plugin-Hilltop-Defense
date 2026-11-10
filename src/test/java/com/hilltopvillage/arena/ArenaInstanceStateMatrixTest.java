package com.hilltopvillage.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import com.hilltopvillage.core.GameState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * fix-critical D2 单测：无快照离场时「是否必须清背包」的状态矩阵。
 *
 * <p>覆盖 {@link ArenaInstance#shouldClearInventoryOnMissingSnapshot(GameState)} 的
 * 归属判定。下方 {@link #matrixCoversEveryState()} 用<b>集合相等</b>（而不是计数）
 * 对照 {@link GameState#values()}：新增一个 {@link GameState} 时，
 * 「实际清背包的状态集」与「显式期望集」必然不等，测试立刻红，
 * 迫使作者显式决定新状态的归属。</p>
 *
 * <p>注意上方两个 {@code @EnumSource} 用的是<b>显式 {@code names}</b>，不会自动纳入
 * 新枚举值 —— 它们只用来给已知状态钉住一条可读的、带解释消息的用例；
 * 「全量」这层保证由 {@link #matrixCoversEveryState()} 承担。</p>
 *
 * <p>纯逻辑测试，不触碰 Bukkit API（只引用枚举）。</p>
 */
class ArenaInstanceStateMatrixTest {

    /**
     * 期望「无快照离场必须清背包」的状态集合 —— 这个集合是<b>人工决定</b>的规格，
     * 不是从实现里读出来的。新增状态必须在此显式增删。
     */
    private static final Set<GameState> EXPECTED_CLEARING =
            EnumSet.of(GameState.STARTING, GameState.RUNNING, GameState.WAVE_INTERMISSION);

    /**
     * 期望「不得清背包」的状态集合。<b>两侧都必须显式列举</b>：
     * 只断言清背包那一侧时，一个被静默归入「不清」的新状态会让两侧都「看起来对」。
     */
    private static final Set<GameState> EXPECTED_KEEPING =
            EnumSet.of(GameState.WAITING, GameState.VICTORY, GameState.DEFEAT, GameState.ENDING);

    @ParameterizedTest(name = "{0} → 必须清背包")
    @EnumSource(value = GameState.class, names = {"STARTING", "RUNNING", "WAVE_INTERMISSION"})
    @DisplayName("成因 A：对局中的真异常离场 → 清空游戏物品")
    void inGameStatesMustClear(GameState state) {
        assertTrue(ArenaInstance.shouldClearInventoryOnMissingSnapshot(state),
                state + " 属于对局中（已发 starter-kit / 持有局内物品），无快照必须清背包");
    }

    @ParameterizedTest(name = "{0} → 不得清背包")
    @EnumSource(value = GameState.class, names = {"WAITING", "VICTORY", "DEFEAT", "ENDING"})
    @DisplayName("成因 B：非对局窗口的幽灵成员 → 背包保持原样")
    void nonGameStatesMustNotClear(GameState state) {
        assertFalse(ArenaInstance.shouldClearInventoryOnMissingSnapshot(state),
                state + " 不是对局中（WAITING 未发套装 / 结算与重置窗口的幽灵成员），清背包会毁掉玩家自己的物品");
    }

    @Test
    @DisplayName("成因 B：null 状态按未知处理，保守地不清背包")
    void nullStateMustNotClear() {
        assertFalse(ArenaInstance.shouldClearInventoryOnMissingSnapshot(null),
                "状态未知时应选择破坏性更小的一侧（不清背包）");
    }

    @Test
    @DisplayName("状态矩阵完整性：清背包 / 不清背包两个集合都与显式期望集完全相等")
    void matrixCoversEveryState() {
        Set<GameState> actualClearing = EnumSet.noneOf(GameState.class);
        Set<GameState> actualKeeping = EnumSet.noneOf(GameState.class);
        for (GameState state : GameState.values()) {
            if (ArenaInstance.shouldClearInventoryOnMissingSnapshot(state)) {
                actualClearing.add(state);
            } else {
                actualKeeping.add(state);
            }
        }

        // 集合相等，而不是计数相等：计数对「多一个 false / 少一个 true」不敏感，
        // 而集合相等对任何增删改名都敏感，且失败消息直接点名差异。
        assertEquals(EXPECTED_CLEARING, actualClearing,
                "清背包的状态集合必须恰好是 " + EXPECTED_CLEARING + "；实际 " + actualClearing
                        + "。若新增了 GameState，请显式决定其归属并更新 EXPECTED_CLEARING");
        // 关键的第二条：只断言「清背包那 3 个」时，一个新增的 PAUSED 被静默归入
        // 「不清」一侧，第一条照旧成立。必须把「不清」一侧也钉死。
        assertEquals(EXPECTED_KEEPING, actualKeeping,
                "不清背包的状态集合必须恰好是 " + EXPECTED_KEEPING + "；实际 " + actualKeeping
                        + "。新增 GameState 的默认归属必须由作者显式决定，不得静默落入「不清」一侧");
        // 两侧互补且覆盖全部枚举值：任何新增状态都不可能从两个集合之间漏掉。
        Set<GameState> covered = EnumSet.copyOf(actualClearing);
        covered.addAll(actualKeeping);
        assertEquals(EnumSet.allOf(GameState.class), covered,
                "两个集合的并集必须覆盖全部 GameState 值");
    }
}
