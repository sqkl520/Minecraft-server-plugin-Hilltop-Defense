package com.hilltopvillage.core;

public enum GameState {
    WAITING,
    STARTING,
    RUNNING,
    WAVE_INTERMISSION,
    VICTORY,
    DEFEAT,
    ENDING;  // 多竞技场模式：游戏结束后的清理阶段

    /**
     * 本状态的 i18n 消息键（fix6 Y3-M-11）：{@code "state." + 枚举名小写连字符化}。
     *
     * <p>与 messages.yml 的 state.* 键面精确对应（waiting/starting/running/
     * wave-intermission/victory/defeat/ending），供各显示出口统一本地化——
     * 曾有三条状态显示路径（deploy.menu-state 已本地化 / status-state 未 / tps.state 未）
     * 各自为政，直吐英文枚举名。</p>
     */
    public String i18nKey() {
        return "state." + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
