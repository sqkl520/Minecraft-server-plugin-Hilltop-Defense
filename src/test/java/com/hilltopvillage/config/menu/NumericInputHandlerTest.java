package com.hilltopvillage.config.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock;

import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@link NumericInputHandler} 的行为测试——<b>共享 clamp 路径</b>。
 *
 * <p>为什么这个类比 8 个菜单类都值得测：round1 的 001（SHIFT 聊天输入绕过 min/max）与
 * 008（小步长下声明上限不可达）根因都在这条路径上。菜单 GUI 只是把 {@code min}/{@code max}
 * 传进来；真正的收敛点只有这里。只要这条路径的 clamp 正确，<b>所有</b>菜单的聊天输入就都正确。</p>
 *
 * <p>每个用例都成对断言：既要证明「越界被收敛」，又要证明「区间内不被改动」。
 * 只测前者的话，一个恒返回 min 的实现也能通过。</p>
 *
 * <p><b>静态状态</b>：{@code pendingInputs} / {@code pendingStringInputs} 是静态 map，
 * 所以每个用例结束时都通过 {@link NumericInputHandler#onQuit} 把本用例的玩家清干净，
 * 避免条目泄漏到同 JVM 的下一个用例。</p>
 */
class NumericInputHandlerTest extends MockBukkitTestBase {

    private final NumericInputHandler handler = new NumericInputHandler();
    private final List<PlayerMock> players = new ArrayList<>();
    private LanguageManager lang;

    @org.junit.jupiter.api.BeforeEach
    void setUpLanguageMock() {
        lang = mock(LanguageManager.class);
        when(lang.getLocale()).thenReturn("en");
        when(lang.get(anyString())).thenReturn("x");
        when(lang.get(anyString(), anyString())).thenReturn("x");
        when(lang.getFor(any(), anyString())).thenReturn("x");
        when(lang.getFor(any(), anyString(), any())).thenReturn("x");
    }

    @AfterEach
    void clearPendingEntries() {
        for (PlayerMock player : players) {
            handler.onQuit(new PlayerQuitEvent(player, (String) null));
        }
        players.clear();
    }

    // ------------------------------------------------------------------ 夹具

    private PlayerMock player(String name) {
        PlayerMock player = server.addPlayer(name);
        players.add(player);
        return player;
    }

    private Plugin plugin() {
        return MockBukkit.createMockPlugin("HilltopVillage");
    }

    private AsyncPlayerChatEvent chat(PlayerMock player, String message) {
        return new AsyncPlayerChatEvent(true, player, message, Collections.emptySet());
    }

    /** 投递一条聊天消息并推进调度器，让 onChat 内的 runTask 回调真正执行。 */
    private AsyncPlayerChatEvent say(PlayerMock player, String message) {
        AsyncPlayerChatEvent event = chat(player, message);
        handler.onChat(event);
        server.getScheduler().performTicks(2);
        return event;
    }

    // ------------------------------------------------------------------ clamp 核心

    @Nested
    @DisplayName("声明区间的 clamp（slice-08-logic-001 的收敛点）")
    class Clamping {

        @Test
        @DisplayName("低于声明下界的聊天输入被抬到下界，而不是原样写入")
        void belowMinIsClampedUpToMin() {
            PlayerMock player = player("clamp-low");
            List<Double> got = new ArrayList<>();
            NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100, got::add, null, lang);

            say(player, "5");

            assertEquals(List.of(10.0), got,
                    "聊天输入 5 落在声明区间 10..100 之外，必须被钳到 10 —— "
                            + "round1 slice-08-logic-001 就是这条路径不 clamp，"
                            + "管理员能在 lore 写着 10..1000 的槽位里写进 0");
        }

        @Test
        @DisplayName("高于声明上界的聊天输入被压到上界")
        void aboveMaxIsClampedDownToMax() {
            PlayerMock player = player("clamp-high");
            List<Double> got = new ArrayList<>();
            NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100, got::add, null, lang);

            say(player, "5000");

            assertEquals(List.of(100.0), got,
                    "聊天输入 5000 超出声明上界 100，必须被压到 100");
        }

        @Test
        @DisplayName("区间内的值不被改动（防止恒返回 min/max 的实现通过）")
        void withinRangePassesThroughUnchanged() {
            PlayerMock player = player("clamp-mid");
            List<Double> got = new ArrayList<>();
            NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100, got::add, null, lang);

            say(player, "55");

            assertEquals(List.of(55.0), got,
                    "55 本来就在 10..100 内，clamp 不得改动它");
        }

        @Test
        @DisplayName("边界值本身可用（含 min 与 max）")
        void boundariesAreInclusive() {
            PlayerMock player = player("clamp-edge");
            List<Double> got = new ArrayList<>();

            NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100, got::add, null, lang);
            say(player, "10");
            NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100, got::add, null, lang);
            say(player, "100");

            assertEquals(List.of(10.0, 100.0), got,
                    "声明的是闭区间，两个端点都必须原样通过");
        }

        @Test
        @DisplayName("自由区间重载只保留 ±1e9 量程防护，不做业务 clamp")
        void freeRangeOverloadOnlyAppliesLooseBound() {
            PlayerMock player = player("clamp-free");
            List<Double> got = new ArrayList<>();

            NumericInputHandler.requestNumber(plugin(), player, "prompt", got::add, null, lang);
            say(player, "250000");

            assertEquals(List.of(250000.0), got,
                    "自由区间版本用于「新建波次编号」这类无声明范围的输入，"
                            + "不得擅自把值收进任何业务区间");
        }
    }

    @Nested
    @DisplayName("整数版(requestInteger) 的域防护（P2-32）")
    class IntegerDomain {

        @Test
        @DisplayName("整数版既遵守声明区间，也按 int 消费")
        void requestIntegerClampsToDeclaredRangeAndCasts() {
            PlayerMock player = player("int-clamp");
            List<Integer> got = new ArrayList<>();
            NumericInputHandler.requestInteger(plugin(), player, "prompt", 1, 20, got::add, null, lang);

            say(player, "99");

            assertEquals(List.of(20), got,
                    "声明 1..20，输入 99 必须收敛到 20 再转 int");
        }

        @Test
        @DisplayName("整数版区间内的小数按截断消费")
        void requestIntegerTruncatesInRangeDecimal() {
            PlayerMock player = player("int-trunc");
            List<Integer> got = new ArrayList<>();
            NumericInputHandler.requestInteger(plugin(), player, "prompt", 1, 20, got::add, null, lang);

            say(player, "7.9");

            assertEquals(List.of(7), got,
                    "7.9 在 1..20 内，(int) 截断后为 7");
        }
    }

    @Nested
    @DisplayName("非法输入")
    class InvalidInput {

        @Test
        @DisplayName("NaN / Infinity / 荒谬量级被拒，且不回调")
        void nonFiniteAndAbsurdValuesAreRejected() {
            PlayerMock player = player("invalid-num");

            for (String bad : List.of("NaN", "Infinity", "-Infinity", "1e300", "-1e300")) {
                List<Double> got = new ArrayList<>();
                NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100, got::add, null, lang);
                AsyncPlayerChatEvent event = say(player, bad);
                assertTrue(event.isCancelled(), bad + " 必须被 onChat 拦下（不让它进公屏）");
                assertTrue(got.isEmpty(),
                        bad + " 不得进入回调 —— 1e300 被 (int) 截断成 Integer.MAX_VALUE、"
                                + "NaN 被截断成 0 都会静默写坏配置");
            }
        }

        @Test
        @DisplayName("非法输入重挂后，声明区间必须还在（易漏点）")
        void invalidInputRenewsWithTheSameDeclaredRange() {
            PlayerMock player = player("invalid-renew");
            List<Double> got = new ArrayList<>();
            NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100, got::add, null, lang);

            say(player, "not-a-number");
            say(player, "5000");

            assertEquals(List.of(100.0), got,
                    "非法输入会把待处理条目换成新条目；若重挂时丢了 min/max，"
                            + "第二次合法但越界的输入就会原样写入 —— 这是 slice-08-logic-001 的复发点");
        }

        @Test
        @DisplayName("非法输入后仍能接受区间内的合法值")
        void invalidInputStillAcceptsLaterValidValue() {
            PlayerMock player = player("invalid-then-ok");
            List<Double> got = new ArrayList<>();
            NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100, got::add, null, lang);

            say(player, "???");
            say(player, "42");

            assertEquals(List.of(42.0), got, "重挂后的条目必须仍可正常消费");
        }
    }

    @Nested
    @DisplayName("取消 / 超时 / 失效出口")
    class ExitPaths {

        @Test
        @DisplayName("chat 'cancel' 只触发 onCancel，不写值")
        void cancelInvokesOnCancelOnly() {
            PlayerMock player = player("cancel");
            List<Double> got = new ArrayList<>();
            int[] cancelled = {0};
            NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100,
                    got::add, () -> cancelled[0]++, lang);

            AsyncPlayerChatEvent event = say(player, "cancel");

            assertTrue(event.isCancelled(), "cancel 指令本身不能进公屏");
            assertEquals(1, cancelled[0], "cancel 必须回调 onCancel（菜单据此重开 GUI）");
            assertTrue(got.isEmpty(), "cancel 之后不得再写入任何值");
        }

        @Test
        @DisplayName("30 秒（600 tick）无人输入则超时并回调 onCancel")
        void timeoutInvokesOnCancel() {
            PlayerMock player = player("timeout");
            List<Double> got = new ArrayList<>();
            int[] cancelled = {0};
            NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100,
                    got::add, () -> cancelled[0]++, lang);

            server.getScheduler().performTicks(601);

            assertEquals(1, cancelled[0],
                    "600 tick 的等待上限到点必须回调 onCancel，否则菜单停在「等待输入」不再回来");
            assertTrue(got.isEmpty(), "超时不写入任何值");
        }

        @Test
        @DisplayName("打开容器即丢弃待处理输入，之后聊天不被吞（slice-08-logic-007）")
        void openingContainerDropsPendingInput() {
            PlayerMock player = player("inventory-open");
            List<Double> got = new ArrayList<>();
            int[] cancelled = {0};
            NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100,
                    got::add, () -> cancelled[0]++, lang);

            Inventory top = server.createInventory(null, 27, "chest");
            InventoryView view = new SimpleInventoryViewMock(player, top, player.getInventory(),
                    org.bukkit.event.inventory.InventoryType.CHEST);
            handler.onInventoryOpen(new InventoryOpenEvent(view));

            AsyncPlayerChatEvent after = say(player, "55");

            assertTrue(got.isEmpty(),
                    "玩家 SHIFT+点击后跑去开箱子，说明已离开输入；此后公屏里任何能解析为数字的话"
                            + "都不得被静默写进配置（round1 slice-08-logic-007）");
            assertFalse(after.isCancelled(),
                    "条目已丢弃，这条聊天必须照常进公屏，不能被吞掉");
            assertEquals(0, cancelled[0],
                    "与 onQuit 同口径：玩家已经在做别的事，不应把 GUI 抢回来");
        }

        @Test
        @DisplayName("退服静默丢弃，不回调 onCancel（回调会打到失效连接）")
        void quitDropsPendingSilently() {
            PlayerMock player = player("quit");
            List<Double> got = new ArrayList<>();
            int[] cancelled = {0};
            NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100,
                    got::add, () -> cancelled[0]++, lang);

            handler.onQuit(new PlayerQuitEvent(player, (String) null));
            server.getScheduler().performTicks(601);

            assertTrue(got.isEmpty(), "退服后不得写入任何值");
            assertEquals(0, cancelled[0],
                    "退服不回调 onCancel：onCancel 的调用方约定是给在线玩家重开 GUI，"
                            + "对已退服玩家调用只会打出发包到失效连接");
        }
    }

    @Nested
    @DisplayName("已消费的输入不会被重复消费")
    class SingleUse {

        @Test
        @DisplayName("一次输入只消费一次，随后的同类消息不再被拦截")
        void consumedInputIsNotReused() {
            PlayerMock player = player("single-use");
            List<Double> got = new ArrayList<>();
            NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100, got::add, null, lang);

            say(player, "55");
            AsyncPlayerChatEvent second = say(player, "55");

            assertEquals(List.of(55.0), got, "第一次输入消费后条目必须移除，回调只能发生一次");
            assertFalse(second.isCancelled(),
                    "条目已消费，第二条同样的聊天必须照常进公屏");
        }
    }

    @Nested
    @DisplayName("字符串输入（requestString）")
    class StringInput {

        @Test
        @DisplayName("回调收到 trim 后的原始文本")
        void stringInputTrimsAndPassesThrough() {
            PlayerMock player = player("string");
            List<String> got = new ArrayList<>();
            NumericInputHandler.requestString(plugin(), player, "prompt", got::add, null, lang);

            say(player, "  minecraft:iron_axe  ");

            assertEquals(List.of("minecraft:iron_axe"), got,
                    "ItemsAdder / Material 名输入必须 trim 后原样交出");
        }

        @Test
        @DisplayName("字符串输入优先于数值输入，不会串到数值通道")
        void stringInputTakesPrecedence() {
            PlayerMock player = player("string-priority");
            List<String> strings = new ArrayList<>();
            List<Double> numbers = new ArrayList<>();

            NumericInputHandler.requestNumber(plugin(), player, "prompt", 10, 100,
                    numbers::add, null, lang);
            NumericInputHandler.requestString(plugin(), player, "prompt", strings::add, null, lang);
            say(player, "77");

            assertEquals(List.of("77"), strings,
                    "同一玩家同时挂着两种输入时，字符串通道先消费");
            assertTrue(numbers.isEmpty(), "不得同时触发数值回调");
        }

        @Test
        @DisplayName("字符串输入的 cancel 只取消，不提交文本")
        void stringCancelDoesNotSubmit() {
            PlayerMock player = player("string-cancel");
            List<String> got = new ArrayList<>();
            int[] cancelled = {0};
            NumericInputHandler.requestString(plugin(), player, "prompt", got::add,
                    () -> cancelled[0]++, lang);

            say(player, "CANCEL");

            assertTrue(got.isEmpty(), "cancel 不是待提交的文本");
            assertEquals(1, cancelled[0], "cancel（大小写不敏感）必须回调 onCancel");
        }
    }

    @Test
    @DisplayName("没有待处理输入的聊天完全不被触碰")
    void unrelatedChatIsNeverTouched() {
        PlayerMock player = player("no-pending");

        AsyncPlayerChatEvent event = say(player, "hello world");

        assertFalse(event.isCancelled(), "没有挂起输入时，onChat 必须是彻底的 no-op");
        // 反向证明：mock 的 lang 若被调用过，说明 handler 误判了状态
        org.mockito.Mockito.verify(lang, never()).getFor(any(), anyString());
    }
}
