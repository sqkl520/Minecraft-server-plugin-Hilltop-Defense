package com.hilltopvillage.arena;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「大厅出生点是否仍是出厂占位值」判定（部署向导第 8 步提醒 / setspawn 降级路径共用口径）。
 *
 * <p>纯函数测试：{@link ArenaManager#isFactoryDefaultSpawn(double, double, double)} 无 Bukkit 依赖，
 * 因此不引入 MockBukkit 即可覆盖边界。出厂模板的占位值是 {@code 0.5 / 100.0 / 0.5}。</p>
 */
class LobbySpawnDefaultTest {

    @Test
    @DisplayName("lobby spawn: 出厂占位值 0.5/100.0/0.5 判定为未自定义")
    void factoryPlaceholderIsDetected() {
        assertTrue(ArenaManager.isFactoryDefaultSpawn(0.5, 100.0, 0.5));
    }

    @Test
    @DisplayName("lobby spawn: 整数写法 0.5/100/0.5 与浮点写法等价（YAML 读出来是同一个 double）")
    void integerYamlLiteralIsEquivalent() {
        assertTrue(ArenaManager.isFactoryDefaultSpawn(0.5, 100, 0.5));
    }

    @Test
    @DisplayName("lobby spawn: 只要任一轴被改过就不算占位值")
    void anyAxisChangedIsCustom() {
        assertFalse(ArenaManager.isFactoryDefaultSpawn(0.5, 100.0, 0.6));
        assertFalse(ArenaManager.isFactoryDefaultSpawn(0.5, 101.0, 0.5));
        assertFalse(ArenaManager.isFactoryDefaultSpawn(1.5, 100.0, 0.5));
    }

    @Test
    @DisplayName("lobby spawn: 真实大厅坐标（如 12.5/65.0/-30.5）不算占位值")
    void realLobbyCoordinatesAreCustom() {
        assertFalse(ArenaManager.isFactoryDefaultSpawn(12.5, 65.0, -30.5));
        assertFalse(ArenaManager.isFactoryDefaultSpawn(-0.5, 100.0, 0.5));
    }

    @Test
    @DisplayName("lobby spawn: 浮点误差在容差内仍算占位值")
    void floatingPointNoiseStaysWithinTolerance() {
        assertTrue(ArenaManager.isFactoryDefaultSpawn(0.5 + 1.0e-9, 100.0 - 1.0e-9, 0.5));
    }
}
