package com.hilltopvillage.core;

public enum GameState {
    WAITING,
    STARTING,
    RUNNING,
    WAVE_INTERMISSION,
    VICTORY,
    DEFEAT,
    ENDING  // 多竞技场模式：游戏结束后的清理阶段
}
