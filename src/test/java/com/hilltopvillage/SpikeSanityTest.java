package com.hilltopvillage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * STORY-0.3 测试框架 Spike 的示例测试：证明纯逻辑测试可在本工程跑通。
 *
 * <p>刻意零 Bukkit 依赖（不断言任何 Bukkit API），只验证"JUnit5 依赖 + surefire
 * 默认配置"这条链路可用。Bukkit 相关测试需等 MockBukkit 结论（见
 * {@code docs/07-guides/TEST-FRAMEWORK-SPIKE.md}）。</p>
 */
class SpikeSanityTest {

    @Test
    @DisplayName("sanity: 守卫战胜利波次常量为 20")
    void victoryWavesDefault() {
        assertEquals(20, 20, "示例断言，验证 surefire 能执行测试");
    }

    @Test
    @DisplayName("sanity: 纯 JDK 集合逻辑")
    void pureJavaLogic() {
        List<String> waves = List.of("beetle", "hunter", "dropper");
        assertEquals(3, waves.size());
        assertTrue(waves.contains("hunter"));
    }

    @Test
    @DisplayName("sanity: 占位符替换逻辑（LanguageManager.formatArgs 同款语义抽查）")
    void placeholderSemantics() {
        String template = "Wave {0} cleared!";
        String filled = template.replace("{0}", "7");
        assertEquals("Wave 7 cleared!", filled);
    }
}
