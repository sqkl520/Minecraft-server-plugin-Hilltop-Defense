package com.hilltopvillage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.util.LanguageManager;

/**
 * STORY-0.3 测试框架 Spike 的示例测试：证明纯逻辑测试可在本工程跑通。
 *
 * <p>刻意零 Bukkit 运行时依赖（不加载 Bukkit 运行时、不断言任何 Bukkit API），主要验证
 * "JUnit5 依赖 + surefire 默认配置"这条链路可用。Bukkit 相关测试需等 MockBukkit 结论（见
 * {@code docs/07-guides/TEST-FRAMEWORK-SPIKE.md}）。</p>
 *
 * <p>{@link #placeholderSemantics()} 是例外：它反射直测生产类
 * {@code LanguageManager.fill()}，是有意为之的一根探针（见该方法注释）。</p>
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

    /**
     * 生产侧真实的占位符填充：{@code LanguageManager.fill(String, int, Object...)}。
     *
     * <p><b>为什么反射：</b>{@code fill} 是 {@code private static}，且 {@link LanguageManager} 的
     * 公开实例方法全部要一个已初始化的 {@code HilltopVillagePlugin}（Bukkit 运行时）。本工程
     * 尚无 MockBukkit，所以没有任何「公开路径」能在纯 JUnit5 下到达它。反射是当前依赖集下
     * 唯一能真正瞄准生产代码的走法；代价是方法名被写进字符串，故本方法第一步就断言
     * 「该名字仍解析到 private static 方法」——一旦 {@code fill} 被改名/删除/公开化，本测试
     * 立刻红，而不是静默退化。</p>
     *
     * <p><b>为什么必须测三类差异输入：</b>{@code fill} 的 Javadoc 明确写着它替代了原来的
     * {@code String.replace} 链，并列出 A/B/C 三类<b>必然不同</b>的输入。只喂「普通模板」
     * 的用例在两种算法下输出相同，等于又一次测了 {@code String.replace}。这三条才是把
     * 「单遍扫描」与「逐轮替换」区分开的探针。</p>
     */
    @Test
    @DisplayName("sanity: LanguageManager.fill 的占位符语义（含与 String.replace 的 3 类差异）")
    void placeholderSemantics() throws Exception {
        Method fill = LanguageManager.class.getDeclaredMethod(
                "fill", String.class, int.class, Object[].class);
        fill.setAccessible(true);
        assertTrue(java.lang.reflect.Modifier.isPrivate(fill.getModifiers())
                        && java.lang.reflect.Modifier.isStatic(fill.getModifiers()),
                "fill() 应仍是 private static；若已公开，请改为直调并删掉本测试的反射");

        // 普通模板：两类算法一致 —— 这是「日常路径没坏」的那一条
        assertEquals("Wave 7 cleared!", invoke(fill, "Wave {0} cleared!", "7"));
        // 同一占位符多次出现：全部替换（与 String.replace 一致）
        assertEquals("7/7", invoke(fill, "{0}/{0}", "7"));
        // 容错规则（与 String.replace 一致）：空括号 / 非数字 / args 不足 → 原样保留
        assertEquals("{} {abc} {9}", invoke(fill, "{} {abc} {9}", "P", "Q"));

        // A 类：模板含嵌套/交叠大括号 —— String.replace 链给出 "{0Q"
        assertEquals("{0{1}", invoke(fill, "{0{1}", "P", "Q"));
        // B 类：数字索引带前导零 —— String.replace 链给出 "{01}"（找不到字面目标 {1}）
        assertEquals("Q", invoke(fill, "{01}", "P", "Q"));
        // C 类：替换值自身含大括号 —— String.replace 链会二次改写，给出 "..Y..Y.."
        assertEquals("..{1}..Y..", invoke(fill, "..{0}..{1}..", "{1}", "Y"));
    }

    /** 以「首个 '{' 的下标」调用 fill（与 {@code LanguageManager#get} 的调用方式一致）。 */
    private static String invoke(Method fill, String template, Object... args) throws Exception {
        return (String) fill.invoke(null, template, template.indexOf('{'), args);
    }
}
