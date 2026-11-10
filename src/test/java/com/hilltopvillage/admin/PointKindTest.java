package com.hilltopvillage.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PointKind} 的行为测试：五类点位「颜色 + 文案」配色体系（F-7）。
 *
 * <p>这个枚举没有逻辑分支，但它承载了两条<b>会被静默打破</b>的契约，而两条都只在运行时
 * 才暴露，且都不痛不痒 —— 正是最容易被"顺手重构"改坏、又最难在游戏里注意到的那一类：</p>
 * <ol>
 *   <li><b>两套预览系统共色</b>：{@code AdminDeployManager}（部署向导）与
 *       {@link LocationParticlePreview}（管理 GUI）都读同一个枚举。若某个常量的颜色被改成与
 *       另一个相同，管理员就再也分不出蓝图里的绿点是出生点还是村民。</li>
 *   <li><b>i18n 键必须真实存在</b>：{@code emptyKey()} / {@code showingKey()} 是裸字符串，
 *       编译期无法校验。键写错时 {@code LanguageManager} 只会回显键名，玩家看到的是
 *       {@code particles.showing-nodes} 这种原文。</li>
 * </ol>
 *
 * <p><b>不需要 MockBukkit</b>：{@link Color} 与 {@link Particle.DustOptions} 都是纯值对象
 * （已实测，见类注释末尾），所以本类保持零 Bukkit 单例依赖，毫秒级。</p>
 */
class PointKindTest {

    @Test
    @DisplayName("五类点位一个不少，且枚举常量名是既有 API（外部按名引用）")
    void allFiveKindsExist() {
        assertEquals(List.of("SPAWN", "MOB_SPAWN", "NODE", "VILLAGER", "SHOP"),
                Arrays.stream(PointKind.values()).map(Enum::name).toList(),
                "点位类别的集合是两套预览系统与 GUI 的公共契约；增删常量等于改对外 API");
    }

    @Test
    @DisplayName("五类颜色互不相同（否则管理员在预览里分不出点位类型）")
    void everyKindHasADistinctColor() {
        Set<Integer> seen = new HashSet<>();
        for (PointKind kind : PointKind.values()) {
            int rgb = kind.beamDust().getColor().asRGB();
            assertTrue(seen.add(rgb),
                    kind + " 的颜色 #" + String.format("%06X", rgb)
                            + " 与另一个类别重复 —— F-7 的核心就是「一眼分辨点位类型」，"
                            + "两色相同会让管理员把刷怪点当成出生点");
        }
        assertEquals(5, seen.size());
    }

    @Test
    @DisplayName("颜色与说明文档一致：出生点绿 / 刷怪点红 / 节点蓝 / 村民黄 / 商店金")
    void colorsMatchDocumentedPalette() {
        assertEquals(Color.fromRGB(0x3CFF5A), PointKind.SPAWN.beamDust().getColor());
        assertEquals(Color.fromRGB(0xFF5555), PointKind.MOB_SPAWN.beamDust().getColor());
        assertEquals(Color.fromRGB(0x4A8CFF), PointKind.NODE.beamDust().getColor());
        assertEquals(Color.fromRGB(0xFFDE59), PointKind.VILLAGER.beamDust().getColor());
        assertEquals(Color.fromRGB(0xFFAA00), PointKind.SHOP.beamDust().getColor());
    }

    @Test
    @DisplayName("螺旋与光柱同色，且螺旋尺寸严格更大")
    void spiralSharesColorButIsLarger() {
        for (PointKind kind : PointKind.values()) {
            assertEquals(kind.beamDust().getColor(), kind.spiralDust().getColor(),
                    kind + " 的螺旋必须与光柱同色，否则「同一点位两色」会误导分类");
            assertTrue(kind.spiralDust().getSize() > kind.beamDust().getSize(),
                    kind + " 的螺旋 DUST 尺寸（" + kind.spiralDust().getSize()
                            + "）必须大于光柱（" + kind.beamDust().getSize()
                            + "）—— 类注释承诺螺旋「略大，增强环绕装饰的可辨识度」");
        }
        assertEquals(1.0f, PointKind.SPAWN.beamDust().getSize(), 1e-6,
                "光柱尺寸与「与原 END_ROD 柱观感接近」的 1.0 约定绑定");
    }

    @Test
    @DisplayName("DustOptions 是共享常量（每次渲染都 new 会造垃圾，而每帧每点要调用数十次）")
    void dustOptionsAreReusedAcrossCalls() {
        for (PointKind kind : PointKind.values()) {
            Particle.DustOptions beam = kind.beamDust();
            Particle.DustOptions spiral = kind.spiralDust();
            assertEquals(beam, kind.beamDust(),
                    kind + " 每次调用都必须返回同一个 DustOptions 实例（类注释：枚举常量构造时一次性建好并全程复用）");
            assertEquals(spiral, kind.spiralDust());
            assertNotSame(beam, spiral,
                    "光柱与螺旋是两个不同尺寸的对象，不能共用同一个实例（否则尺寸无法区分）");
        }
    }

    @Test
    @DisplayName("i18n 键在出厂 messages.yml 的 en 与 zh 段落里都真实存在")
    void translationKeysExistInBothLanguages() {
        String messages = readFactoryMessages();
        String en = section(messages, "en:");
        String zh = section(messages, "zh:");

        for (PointKind kind : PointKind.values()) {
            for (String key : List.of(kind.emptyKey(), kind.showingKey())) {
                assertTrue(en.contains(key + ":"),
                        "en 段缺少 i18n 键 " + key + "（" + kind + " 会向玩家回显裸键名）");
                assertTrue(zh.contains(key + ":"),
                        "zh 段缺少 i18n 键 " + key + "（" + kind + " 会向玩家回显裸键名）");
            }
        }
    }

    @Test
    @DisplayName("五类的 emptyKey / showingKey 两两互不重复")
    void keysAreNotSharedBetweenKinds() {
        Set<String> empties = new HashSet<>();
        Set<String> showings = new HashSet<>();
        for (PointKind kind : PointKind.values()) {
            assertNotNull(kind.emptyKey());
            assertTrue(empties.add(kind.emptyKey()),
                    kind.emptyKey() + " 被两个类别共用 —— 玩家会看到错误的类别名");
            assertTrue(showings.add(kind.showingKey()),
                    kind.showingKey() + " 被两个类别共用");
        }
    }

    @Test
    @DisplayName("键名落在 particles.* 命名空间内（与 messages.yml 的分组一致）")
    void keysLiveInTheParticlesNamespace() {
        for (PointKind kind : PointKind.values()) {
            assertTrue(kind.emptyKey().startsWith("particles."),
                    kind + ".emptyKey() 必须以 particles. 开头，实际 " + kind.emptyKey());
            assertTrue(kind.showingKey().startsWith("particles."),
                    kind + ".showingKey() 必须以 particles. 开头，实际 " + kind.showingKey());
        }
    }

    // ------------------------------------------------------------------ 夹具

    private static String readFactoryMessages() {
        try (InputStream in = PointKindTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(in, "出厂 messages.yml 不在 test classpath 上");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new AssertionError("读取 messages.yml 失败", failure);
        }
    }

    /** 截取顶层语言段落（{@code en:} / {@code zh:}）的正文，避免跨语言误命中。 */
    private static String section(String messages, String header) {
        int start = messages.indexOf("\n" + header);
        assertTrue(start >= 0, "messages.yml 缺少顶层段落 " + header);
        int next = messages.indexOf("\nzh:", start + header.length());
        if (next < 0) next = messages.length();
        return messages.substring(start, next);
    }
}
