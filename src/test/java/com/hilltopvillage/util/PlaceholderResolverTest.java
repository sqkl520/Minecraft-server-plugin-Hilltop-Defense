package com.hilltopvillage.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link PlaceholderResolver} 的行为，重点是<b>没有 PAPI</b> 的主路径。
 *
 * <p><b>为什么这是主路径而不是异常路径</b>：PAPI 是 {@code provided}，运行时不一定装。
 * 绝大多数服务器根本没有它，缺席时「安静降级、原样返回」是常态。</p>
 *
 * <p><b>装不了 PAPI 时哪条分支测不到</b>：本环境无法把真实的 PlaceholderAPI 装进 MockBukkit，
 * 所以「PAPI 在场且解析成功」这条分支这里测不到。但「残留占位符的剔除与告警去重」不依赖
 * PAPI 本体 —— PAPI 对未注册的 expansion 就是原样返回 —— 所以用包可见的
 * {@link PlaceholderResolver#stripResidual} 直接验证。</p>
 */
class PlaceholderResolverTest extends MockBukkitTestBase {

    private PlayerMock player;
    private Logger logger;
    private List<String> warnings;

    @BeforeEach
    void setUp() {
        player = server.addPlayer("ResolveTest");
        warnings = new ArrayList<>();
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    @Test
    @DisplayName("测试环境没有 PAPI → available() 为 false")
    void unavailableWithoutPapi() {
        assertFalse(new PlaceholderResolver(logger).available(),
                "MockBukkit 里没有 PlaceholderAPI；若这里是 true，说明探测走了别的路径");
    }

    @Test
    @DisplayName("★ PAPI 未安装 → 逐字原样返回，不吃掉 % 也不删字符")
    void passesTextThroughWhenUnavailable() {
        PlaceholderResolver resolver = new PlaceholderResolver(logger);
        String text = "&7[管理员] &f%UserPrefix_prefix%%player%";
        assertEquals(text, resolver.resolve(player, text),
                "没装 PAPI 时必须逐字返回。这里若被改动，说明降级路径在越权处理它不该碰的内容");
        assertEquals(List.of(), warnings, "没装 PAPI 不是错误，不该告警");
    }

    @Test
    @DisplayName("含正常百分号的文本不会被误伤")
    void doesNotEatPlainPercent() {
        PlaceholderResolver resolver = new PlaceholderResolver(logger);
        assertEquals("完成度 100%", resolver.resolve(player, "完成度 100%"));
    }

    @Test
    @DisplayName("null / 空串 / null 玩家都不抛")
    void handlesNullsQuietly() {
        PlaceholderResolver resolver = new PlaceholderResolver(logger);
        assertNull(resolver.resolve(player, null));
        assertEquals("", resolver.resolve(player, ""));
        assertEquals("%x%", resolver.resolve(null, "%x%"));
    }

    @Test
    @DisplayName("logger 为 null 时不抛")
    void toleratesNullLogger() {
        assertEquals("plain", new PlaceholderResolver(null).resolve(player, "plain"));
    }

    // ------------------------------------------------------------ 残留处理

    @Test
    @DisplayName("★ 残留占位符被剔除，100% 这类正常文本不受影响")
    void stripsOnlyPlaceholderShapedTokens() {
        PlaceholderResolver resolver = new PlaceholderResolver(logger);
        assertEquals("[管理员] Steve",
                resolver.stripResidual("[管理员] %UserPrefix_prefix%Steve"),
                "未解析的占位符不能发给客户端 —— 玩家会看到裸露的 %UserPrefix_prefix%");
        assertEquals("完成度 100% 和 50%",
                resolver.stripResidual("完成度 100% 和 50%"),
                "只该删 %identifier% 形状，不能把正常的百分号一起吃掉");
    }

    @Test
    @DisplayName("★ 同一段文本重复渲染只告警一次（每帧都跑，不去重会刷满控制台）")
    void warnsOncePerToken() {
        PlaceholderResolver resolver = new PlaceholderResolver(logger);
        String text = "前缀 %UserPrefix_prefix% 结尾";
        for (int i = 0; i < 5; i++) {
            resolver.stripResidual(text);
        }
        assertEquals(1, warnings.size(),
                "同一段文本渲染 5 次只能留 1 条 WARN；不去重会每帧一条，把真正的问题淹掉");
        assertTrue(warnings.get(0).contains("%UserPrefix_prefix%"),
                "告警必须点名是哪个 token；实际：" + warnings);
    }

    @Test
    @DisplayName("不同 token 各自告警一次")
    void warnsOncePerDistinctToken() {
        PlaceholderResolver resolver = new PlaceholderResolver(logger);
        resolver.stripResidual("%aaa_bbb% 和 %ccc%");
        assertEquals(2, warnings.size(), "两个不同 token 应各报一次；实际：" + warnings);
    }

    @Test
    @DisplayName("没有残留时不留告警")
    void noWarningWhenNothingResidual() {
        PlaceholderResolver resolver = new PlaceholderResolver(logger);
        assertEquals("纯文本 100%", resolver.stripResidual("纯文本 100%"));
        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("stripResidual 对 null 与无 % 文本原样返回")
    void stripResidualPassesThroughTrivialInput() {
        PlaceholderResolver resolver = new PlaceholderResolver(logger);
        assertNull(resolver.stripResidual(null));
        assertEquals("没有占位符", resolver.stripResidual("没有占位符"));
    }
}
