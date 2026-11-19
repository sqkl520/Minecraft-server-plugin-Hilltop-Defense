package com.hilltopvillage.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 独立验证审计结论：「airdrop-mob-type 留空会导致 getString 返回 null」是否成立。
 *
 * <p>这个测试不是修 bug 用的，是**裁决两条互相矛盾的审计结论**用的：
 * <ul>
 *   <li>静态审计 R2-AI-002 主张：`contains()` 为 true 而 `getString()` 返回 null，
 *       于是 `AirdropSpawnGoal:237` 的 `mobTypeName.toUpperCase()` 抛 NPE</li>
 *   <li>批判复核主张：`MemorySection.contains(path)` 的实现就是 `get(path) != null`，
 *       而 `getString` 也是基于 `get`，所以两者互斥，NPE 不可达</li>
 * </ul>
 *
 * <p>谁对，跑一下就知道。这不是推演。
 */
class AirdropMobTypeNullabilityTest {

    private static YamlConfiguration parse(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }

    @Test
    @DisplayName("空值写法：contains 与 getString 必须同时表示「键不存在」")
    void emptyValue() {
        YamlConfiguration y = parse("monsters:\n  flying-dropper:\n    airdrop-mob-type:\n");
        String path = "monsters.flying-dropper.airdrop-mob-type";
        boolean contains = y.contains(path);
        String got = y.getString(path);

        System.out.println("[empty value] contains=" + contains + " getString=" + got);

        // 只要 contains 为 true，getString 就不能是 null——否则 NPE 路径成立
        if (contains) {
            assertNotNull(got, "contains=true 却 getString=null → NPE 路径成立，静态审计正确");
        } else {
            // contains=false：调用方根本不会走进 setter，走的是代码默认值 "ZOMBIE"
            assertNull(got, "contains=false 时 getString 应为 null（但调用方不会用它）");
        }
    }

    @Test
    @DisplayName("波浪号 ~ 写法")
    void tilde() {
        YamlConfiguration y = parse("monsters:\n  flying-dropper:\n    airdrop-mob-type: ~\n");
        String path = "monsters.flying-dropper.airdrop-mob-type";
        boolean contains = y.contains(path);
        String got = y.getString(path);
        System.out.println("[tilde]       contains=" + contains + " getString=" + got);
        if (contains) assertNotNull(got, "NPE 路径成立");
    }

    @Test
    @DisplayName("字面 null 写法")
    void literalNull() {
        YamlConfiguration y = parse("monsters:\n  flying-dropper:\n    airdrop-mob-type: null\n");
        String path = "monsters.flying-dropper.airdrop-mob-type";
        boolean contains = y.contains(path);
        String got = y.getString(path);
        System.out.println("[literal null] contains=" + contains + " getString=" + got);
        if (contains) assertNotNull(got, "NPE 路径成立");
    }

    @Test
    @DisplayName("键完全缺失")
    void absent() {
        YamlConfiguration y = parse("monsters:\n  flying-dropper:\n    other-key: 1\n");
        String path = "monsters.flying-dropper.airdrop-mob-type";
        boolean contains = y.contains(path);
        String got = y.getString(path);
        System.out.println("[absent]      contains=" + contains + " getString=" + got);
        assertFalse(contains, "缺键时 contains 必须为 false，调用方走默认值");
    }

    @Test
    @DisplayName("引号包裹的字符串 \"null\" 是合法值，不是 null")
    void quotedNull() {
        YamlConfiguration y = parse("monsters:\n  flying-dropper:\n    airdrop-mob-type: \"null\"\n");
        String path = "monsters.flying-dropper.airdrop-mob-type";
        boolean contains = y.contains(path);
        String got = y.getString(path);
        System.out.println("[quoted null] contains=" + contains + " getString=" + got);
        assertTrue(contains);
        assertNotNull(got, "带引号的 \"null\" 是字符串，不是 null");
        assertEquals("null", got);
    }

    @Test
    @DisplayName("汇总：任何 contains=true 的情形，getString 都不得为 null")
    void summary() {
        String[] cases = {
            "monsters:\n  x:\n    airdrop-mob-type:\n",
            "monsters:\n  x:\n    airdrop-mob-type: ~\n",
            "monsters:\n  x:\n    airdrop-mob-type: null\n",
            "monsters:\n  x:\n    airdrop-mob-type: ''\n",
            "monsters:\n  x:\n    airdrop-mob-type: []\n",
            "monsters:\n  x:\n    airdrop-mob-type: {}\n",
            "monsters:\n  x:\n    airdrop-mob-type: 123\n",
        };
        String path = "monsters.x.airdrop-mob-type";
        int npeCapable = 0;
        for (String src : cases) {
            YamlConfiguration y = parse(src);
            boolean contains = y.contains(path);
            String got = y.getString(path);
            String firstLine = src.replace("\n", "\\n");
            if (contains && got == null) {
                npeCapable++;
                System.out.println("  !! NPE 路径: " + firstLine);
            }
        }
        System.out.println("可触发 NPE 的写法数 = " + npeCapable + " / " + cases.length);
        // 这个断言是本测试的核心结论。
        // 如果它失败，说明批判 agent 的「互斥」结论是错的，静态审计 R2-AI-002 成立。
        assertEquals(0, npeCapable,
            "若不为 0，则 R2-AI-002 的 NPE 路径成立，需要修 AirdropSpawnGoal");
    }
}
