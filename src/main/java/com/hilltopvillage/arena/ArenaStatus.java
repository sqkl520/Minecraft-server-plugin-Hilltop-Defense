package com.hilltopvillage.arena;

/**
 * 竞技场生命周期状态（对应 BedWars1058 的「启用队列」模型）。
 *
 * <p>与 {@code GameState} 的区别：{@code GameState} 描述的是<b>一局游戏</b>的阶段
 * （等待玩家 / 开局倒计时 / 进行中 …），而 {@code ArenaStatus} 描述的是
 * <b>竞技场本身是否可用</b>。一个 {@code READY} 的竞技场其 {@code GameState} 可能是 WAITING、RUNNING 等任意值。</p>
 *
 * <p>状态流转：</p>
 * <pre>
 *   DISABLED ──/hilltop enable──▶ QUEUED ──▶ LOADING ──▶ READY
 *       ▲                                                  │
 *       └──────────────/hilltop disable────────────────────┘
 * </pre>
 *
 * <p>只有 {@link #READY} 的竞技场才可被加入，也才可能自动开局 —— 这与 BedWars1058
 * 「只有经过 {@code /bw enable} 完成世界加载 + init 后的竞技场才会 {@code changeStatus(waiting)}」
 * 的语义一致。</p>
 */
public enum ArenaStatus {

    /** 已禁用：配置中 enabled=false，或被管理员用 /hilltop disable 关闭（用于维护） */
    DISABLED,

    /** 已在启用队列中排队，等待加载 */
    QUEUED,

    /** 正在加载世界 / 初始化中 */
    LOADING,

    /** 就绪：已加载并初始化完成，可以加入与开局 */
    READY;

    /** 是否处于「管理员可观察到的处理中」状态 */
    public boolean isPending() {
        return this == QUEUED || this == LOADING;
    }
}
