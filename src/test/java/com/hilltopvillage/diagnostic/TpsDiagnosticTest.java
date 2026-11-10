package com.hilltopvillage.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@link TpsDiagnostic} 的格式化逻辑测试。
 *
 * <p><b>为什么只测 {@code formatTps} 与配色阈值，不测 {@code printDiagnostic}</b>：
 * 本机 MockBukkit 4.45.0 的 {@code Bukkit.getTPS()} 与 {@code Bukkit.getAverageTickTime()}
 * <b>都抛 {@code UnimplementedOperationException}</b>（探针实测），而
 * {@code printDiagnostic} 的第一行就是 {@code Bukkit.getTPS()} —— 整段无法运行。
 * 若强行 spy 打桩，"测的"就变成了桩本身。因此本类只覆盖可真正观察的行为：
 * TPS 夹取（{@code Math.min(tps, 20)}）与三档配色阈值。这是本文件唯一的真实逻辑，
 * 其余是拼字符串。</p>
 *
 * <p><b>为什么要断言配色而不只是数字</b>：TPS 颜色是运维看屏幕时的第一信号。
 * 若 {@code >= 18} 被改成 {@code > 18}，18.0 TPS 会从绿变黄，运维会在服务器完全正常时
 * 收到"变黄了"的假警报 —— 边界必须被钉住。</p>
 */
@DisplayName("TpsDiagnostic — TPS 夹取与配色阈值")
class TpsDiagnosticTest extends MockBukkitTestBase {

    private TpsDiagnostic diagnostic;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger("tps-diagnostic-" + System.nanoTime());
        HilltopVillagePlugin plugin = mock(HilltopVillagePlugin.class);
        when(plugin.getLogger()).thenReturn(logger);

        LanguageManager lang = mock(LanguageManager.class);
        when(lang.getLocale()).thenReturn("en");
        when(lang.get(anyString())).thenReturn("x");
        when(lang.get(anyString(), anyString(), any())).thenReturn("x");

        diagnostic = new TpsDiagnostic(plugin, lang);
    }

    // ---------------------------------------------------------------- 1. 夹取

    @Nested
    @DisplayName("TPS 上限夹取")
    class Clamping {

        /**
         * ★ {@code Bukkit.getTPS()} 在未降频时会返回 &gt; 20 的值（Paper 的滚动平均），
         * 直接显示会出现 "20.3 TPS"。格式化必须夹到 20.0。
         */
        @Test
        @DisplayName("★ 超过 20 的 TPS 被夹到 20.0（Paper 的滚动平均会给出 20.1/20.4）")
        void tpsAboveTwentyIsClamped() {
            assertEquals(ChatColor.GREEN + "20.0", diagnostic.formatTps(20.4),
                    "20.4 必须显示为 20.0：Paper 的 getTPS() 是滚动平均，未降频时常态高于 20，"
                            + "不夹取会出现「20.4 TPS」这种玩家一眼就知道不对的数字");
            assertEquals(ChatColor.GREEN + "20.0", diagnostic.formatTps(100.0),
                    "任何超过 20 的值都夹到 20.0");
        }

        @Test
        @DisplayName("恰好 20.0 与 19.999 都显示 20.0（四舍五入后）")
        void roundingAroundTwenty() {
            assertEquals(ChatColor.GREEN + "20.0", diagnostic.formatTps(20.0));
            assertEquals(ChatColor.GREEN + "20.0", diagnostic.formatTps(19.999),
                    "19.999 经 %.1f 会四舍五入成 20.0；夹取不得把它压到 19.9");
        }

        @Test
        @DisplayName("正常区间的数值保留一位小数")
        void normalRangeKeepsOneDecimal() {
            assertEquals(ChatColor.YELLOW + "17.5", diagnostic.formatTps(17.54));
            assertEquals(ChatColor.RED + "12.3", diagnostic.formatTps(12.34));
            assertEquals(ChatColor.RED + "0.0", diagnostic.formatTps(0.0));
        }
    }

    // ---------------------------------------------------------------- 2. 配色阈值

    @Nested
    @DisplayName("配色阈值（运维看到的第一信号）")
    class ColorThresholds {

        @Test
        @DisplayName(">= 18 为绿（18.0 这个边界必须算绿）")
        void eighteenIsGreen() {
            assertTrue(diagnostic.formatTps(18.0).startsWith(ChatColor.GREEN.toString()),
                    "18.0 必须判绿。若判黄，服务器完全正常时运维会收到假警报");
            assertTrue(diagnostic.formatTps(20.0).startsWith(ChatColor.GREEN.toString()));
            assertTrue(diagnostic.formatTps(19.99).startsWith(ChatColor.GREEN.toString()));
        }

        @Test
        @DisplayName("[15, 18) 为黄")
        void fifteenToEighteenIsYellow() {
            assertTrue(diagnostic.formatTps(17.99).startsWith(ChatColor.YELLOW.toString()),
                    "17.99 必须判黄 —— 它已经低于 18 的健康线");
            assertTrue(diagnostic.formatTps(15.0).startsWith(ChatColor.YELLOW.toString()),
                    "15.0 是黄色档的下边界（含）");
        }

        @Test
        @DisplayName("< 15 为红")
        void belowFifteenIsRed() {
            assertTrue(diagnostic.formatTps(14.99).startsWith(ChatColor.RED.toString()),
                    "14.99 必须判红 —— 15 是黄色档的下边界（含），不到 15 就是红线");
            assertTrue(diagnostic.formatTps(1.0).startsWith(ChatColor.RED.toString()));
        }

        @Test
        @DisplayName("负数与 NaN 不会抛异常（仍在红档）")
        void negativeAndNaNAreSafe() {
            assertTrue(diagnostic.formatTps(-1.0).startsWith(ChatColor.RED.toString()),
                    "负数 TPS 不合理但不得抛异常 —— 它仍走红色分支");
            // Math.min(NaN, 20.0) == NaN，String.format("%.1f", NaN) == "NaN"（不抛）
            String nan = diagnostic.formatTps(Double.NaN);
            assertTrue(nan.startsWith(ChatColor.RED.toString()),
                    "NaN 也应走红色分支且不抛异常；实际：" + nan);
        }
    }

    // ---------------------------------------------------------------- 3. 构造

    @Test
    @DisplayName("构造器只持有依赖，不触碰 Bukkit 运行时（不得在字段初始化时读 TPS）")
    void constructionDoesNotTouchBukkit() {
        // 若构造器里有人加了 Bukkit.getTPS()，本类会在 setup 阶段就抛
        // UnimplementedOperationException —— 这条用例把那类改动挡在门外。
        assertEquals(ChatColor.GREEN + "20.0", diagnostic.formatTps(20.0),
                "构造后 formatTps 应立即可用，说明构造过程没有依赖 Bukkit 运行时");
    }

    // 注：{@code printDiagnostic} 本身<b>不测</b>（不是"未测"，是不可测）：
    // 它第一行即 {@code Bukkit.getTPS()}，而 MockBukkit 4.45.0 对该方法与
    // {@code getAverageTickTime()} 都是裸抛 UnimplementedOperationException（本类探针实测）。
    // 打桩后测的就变成桩本身，属于假绿，故不写。
}
