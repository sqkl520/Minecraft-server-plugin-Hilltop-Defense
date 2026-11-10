package com.hilltopvillage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GameState#i18nKey()} 的契约测试。
 *
 * <p>这是整个 core 包最便宜的一类测试：枚举无 Bukkit 依赖，纯 JUnit 即可。
 * 但它守的是一个真实缺陷类别 —— 「新增了一个状态，忘了补 messages.yml 的键」，
 * 表现为管理 GUI / 记分板 / tps 状态行直接吐出 {@code state.paused} 这样的原始键名。</p>
 *
 * <p>同时钉住连字符化：{@code WAVE_INTERMISSION} 必须产出
 * {@code state.wave-intermission}，而不是 {@code wave_intermission} —— 后者在
 * messages.yml 里<b>不存在</b>，会让唯一带下划线的状态永久显示原始键。</p>
 */
class GameStateTest {

    /** 从 classpath（target/classes）读出厂 messages.yml。 */
    private static YamlConfiguration factoryMessages() throws Exception {
        try (InputStream in = GameStateTest.class.getClassLoader().getResourceAsStream("messages.yml")) {
            assertNotNull(in, "classpath 上找不到 messages.yml —— 资源未随测试复制");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    @Test
    @DisplayName("WAVE_INTERMISSION 连字符化，而不是下划线")
    void waveIntermissionUsesHyphen() {
        assertEquals("state.wave-intermission", GameState.WAVE_INTERMISSION.i18nKey(),
                "下划线未转连字符 —— messages.yml 里只有 state.wave-intermission，"
                        + "拼成 wave_intermission 会让该状态在所有出口显示原始键名");
    }

    @Test
    @DisplayName("全部 7 个状态映射到互不相同的键（防复制粘贴共用一键）")
    void allValuesProduceDistinctKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (GameState state : GameState.values()) {
            assertTrue(keys.add(state.i18nKey()),
                    "重复的 i18n 键：" + state.i18nKey() + " —— 两个状态会显示同一段文案");
        }
        assertEquals(GameState.values().length, keys.size());
    }

    @Test
    @DisplayName("键面形状统一：state. + 小写 + 无下划线")
    void keyShapeIsNormalized() {
        for (GameState state : GameState.values()) {
            String key = state.i18nKey();
            assertTrue(key.startsWith("state."), state + " 的键缺少 state. 前缀：" + key);
            String rest = key.substring("state.".length());
            assertEquals(rest.toLowerCase(java.util.Locale.ROOT), rest,
                    state + " 的键不是全小写：" + key);
            assertTrue(!rest.contains("_"), state + " 的键残留下划线：" + key);
            assertTrue(!rest.isEmpty(), state + " 的键为空");
        }
    }

    @Test
    @DisplayName("每个状态的键在 messages.yml 的 en 与 zh 下都存在")
    void everyStateHasMessageInBothLanguages() throws Exception {
        YamlConfiguration messages = factoryMessages();
        List<String> missing = new ArrayList<>();
        for (GameState state : GameState.values()) {
            String key = state.i18nKey();
            for (String lang : new String[] { "en", "zh" }) {
                String path = lang + "." + key;
                if (!messages.isString(path)) {
                    missing.add(path);
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "以下状态消息键缺失（新增 GameState 时必须同步补 messages.yml）：" + missing);
    }

    @Test
    @DisplayName("状态集恰好是这 7 个（增删需同步本测试与文案）")
    void stateSetIsExplicitlyPinned() {
        Set<GameState> actual = EnumSet.allOf(GameState.class);
        Set<GameState> expected = EnumSet.of(
                GameState.WAITING, GameState.STARTING, GameState.RUNNING,
                GameState.WAVE_INTERMISSION, GameState.VICTORY, GameState.DEFEAT,
                GameState.ENDING);
        assertEquals(expected, actual,
                "状态集合发生变化 —— 请同步 messages.yml 与所有 switch/位掩码口径，实测："
                        + Arrays.toString(GameState.values()));
    }
}
