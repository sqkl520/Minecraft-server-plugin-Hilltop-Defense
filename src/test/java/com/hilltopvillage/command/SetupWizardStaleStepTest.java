package com.hilltopvillage.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 向导第 2 步「过期按钮」缺陷的回归网（用户实测报告）：
 * <blockquote>点击设为核心以后无法再设置等待区 —— 点击设为核心以后直接跳到下一步，
 * 再点击设置等待区就弹未知的部署命令</blockquote>
 *
 * <p><b>缺陷原状</b>：{@code sendCoreButtons} 把 [设为核心] 与 [设为等待区] 放进<b>同一聊天行</b>。
 * [设为核心] 会 {@code advance()} 到第 3 步，而聊天历史上的那一行<b>仍然可点</b>——
 * 点其中的 [设为等待区] 会撞上 {@code if (s.step != 2)} 守卫，回一句
 * {@code setup.unknown}（「未知的部署命令。」）。管理员并没有打错命令，却被指责「命令未知」，
 * 且第 3 步没有任何入口能回来设等待区。</p>
 *
 * <p><b>本类钉住什么</b>：这次修复把「哪个按钮在哪个步骤可用」抽成了两个<b>纯函数</b>
 * （{@link SetupWizard#stepAllows(int, int)} / {@link SetupWizard#waitingSettableAt(int)}），
 * 让这条合同可以被单测直接固定 —— 它正是缺陷的症结：按钮永远可点，是否真正执行完全取决于这个判定。</p>
 *
 * <p><b>为什么不写成「点一下按钮」的集成用例</b>：{@code confirm-core} / {@code confirm-waiting}
 * 的执行体都调 {@code player.getWorld().spawnParticle(HAPPY_VILLAGER, …)}，而 MockBukkit 4.45.0
 * 的 {@code WorldMock#spawnParticle} 每个重载都直接抛 {@code UnimplementedOperationException}
 * （本机实测，见 {@code LocationParticlePreviewTest} 的同一条记录）。要跑通只能把世界换成 spy
 * 再把该方法桩掉 —— 那样一来「成功了没有」就变成在验证桩本身，而不是验证这段逻辑。
 * 因此这里测纯判定，**真实飞行中的点击行为需要在服务器上人工复验**（已在交付说明中列出场景）。</p>
 */
class SetupWizardStaleStepTest {

    /** 向导总步数（与 {@code SetupWizard.TOTAL_STEPS} 对齐；本类只用它做边界断言）。 */
    private static final int TOTAL_STEPS = 8;

    @Nested
    @DisplayName("stepAllows：普通按钮仍绑定在它自己的那一步（P2-1 的原始保护不能被削弱）")
    class OrdinaryGuard {

        @Test
        @DisplayName("只有停在所属步骤时才放行")
        void allowsOnlyOnItsOwnStep() {
            for (int step = 1; step <= TOTAL_STEPS; step++) {
                assertTrue(SetupWizard.stepAllows(step, step),
                        "第 " + step + " 步上属于第 " + step + " 步的按钮必须可用");
                for (int other = 1; other <= TOTAL_STEPS; other++) {
                    if (other == step) continue;
                    assertFalse(SetupWizard.stepAllows(step, other),
                            "第 " + step + " 步上的第 " + other + " 步按钮必须被拒绝（否则聊天历史里的旧按钮会误改数据）");
                }
            }
        }

        @Test
        @DisplayName("用户报告的具体形态：第 3 步点 [设为核心]（属于第 2 步）必须被拒绝")
        void coreButtonRejectedAfterAdvancing() {
            assertFalse(SetupWizard.stepAllows(3, 2),
                    "第 3 步不该还能执行 confirm-core —— 否则聊天历史里的旧 [设为核心] 会把核心搬到管理员脚下");
        }
    }

    @Nested
    @DisplayName("waitingSettableAt：等待区可在确认世界后的任意步骤补点（用户诉求）")
    class WaitingGuard {

        @Test
        @DisplayName("用户报告的核心场景：进到第 3 步后仍然可以设等待区")
        void settableAfterCoreAdvancedToStep3() {
            assertTrue(SetupWizard.waitingSettableAt(3),
                    "点完 [设为核心] 到第 3 步后，聊天历史里的 [设为等待区] 必须仍然可用 —— 这正是用户报告里被堵死的操作");
        }

        @Test
        @DisplayName("第 2 步本身可用（原有行为不变）")
        void settableOnStep2() {
            assertTrue(SetupWizard.waitingSettableAt(2));
        }

        @Test
        @DisplayName("第 2~8 步全程可用")
        void settableOnEveryStepAfterWorldConfirmed() {
            for (int step = 2; step <= TOTAL_STEPS; step++) {
                assertTrue(SetupWizard.waitingSettableAt(step), "第 " + step + " 步应允许补设等待区");
            }
        }

        @Test
        @DisplayName("第 1 步（世界未确认）仍被拒绝 —— 世界确认前不能往 arenas.yml 写坐标")
        void rejectedOnStep1() {
            assertFalse(SetupWizard.waitingSettableAt(1),
                    "第 1 步还没确认目标世界，此时写 waiting-location 会把坐标落到未确认的竞技场");
        }
    }

    @Nested
    @DisplayName("recoveryCommand：过期按钮给出的跳转必须真的回到它所属的步骤")
    class Recovery {

        @Test
        @DisplayName("回程命令与所属步骤一一对应")
        void pointsAtOwnerStep() {
            for (int step = 1; step <= TOTAL_STEPS; step++) {
                assertEquals("/hilltop setup goto " + step, SetupWizard.recoveryCommand(step),
                        "第 " + step + " 步按钮的跳转必须落在 goto " + step);
            }
        }
    }

    @Nested
    @DisplayName("i18n：新增文案必须在 en / zh 两侧同时存在且带占位符")
    class Messages {

        @Test
        @DisplayName("setup.stale-step / setup.btn-back-to-step / setup.btn-resume-step 双语齐全")
        void staleStepKeysPresentInBothLocales() throws Exception {
            YamlConfiguration messages = loadMessages();
            for (String locale : new String[] {"en", "zh"}) {
                ConfigurationSection block = messages.getConfigurationSection(locale);
                assertNotNull(block, "messages.yml 缺少语言段：" + locale);
                for (String key : new String[] {"setup.stale-step", "setup.btn-back-to-step",
                        "setup.btn-resume-step"}) {
                    String value = block.getString(key);
                    assertNotNull(value, locale + " 段缺少 " + key);
                    assertFalse(value.isBlank(), locale + " 段的 " + key + " 不应为空");
                }
            }
        }

        @Test
        @DisplayName("占位符与调用方一致：stale-step 用 {0}{1}{2}，两个按钮用 {0}")
        void placeholdersMatchCallSites() throws Exception {
            YamlConfiguration messages = loadMessages();
            for (String locale : new String[] {"en", "zh"}) {
                String stale = messages.getString(locale + ".setup.stale-step");
                // 调用方传三个参数：当前步骤、总步数、按钮所属步骤
                assertTrue(stale.contains("{0}") && stale.contains("{1}") && stale.contains("{2}"),
                        locale + " 的 setup.stale-step 必须含 {0}/{1}/{2}，实际：" + stale);
                // 两个按钮各传一个参数
                assertTrue(messages.getString(locale + ".setup.btn-back-to-step").contains("{0}"),
                        locale + " 的 setup.btn-back-to-step 必须含 {0}");
                assertTrue(messages.getString(locale + ".setup.btn-resume-step").contains("{0}"),
                        locale + " 的 setup.btn-resume-step 必须含 {0}");
            }
        }

        @Test
        @DisplayName("defensive：setup.unknown 仍保留（未知子命令的真正兜底路径还在用）")
        void unknownKeyStillPresent() throws Exception {
            YamlConfiguration messages = loadMessages();
            assertNotNull(messages.getString("en.setup.unknown"));
            assertNotNull(messages.getString("zh.setup.unknown"));
        }

        /**
         * 读 JAR 内嵌的出厂 {@code messages.yml}。
         *
         * <p>不用插件数据目录的那份：出厂资源才是「新装服务器会看到什么」的口径，
         * 而数据目录那份在升级场景下可能被服主自定义过。</p>
         */
        private YamlConfiguration loadMessages() throws Exception {
            try (InputStream in = getClass().getResourceAsStream("/messages.yml")) {
                assertNotNull(in, "classpath 上找不到 /messages.yml —— 资源未被打进测试 classpath");
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    return YamlConfiguration.loadConfiguration(reader);
                }
            }
        }
    }

    /** 断言 {@code SetupWizard} 源文件确实引用了这几个键（防止文案加了却没人用）。 */
    @Test
    @DisplayName("源码确实引用了三个新键（文案不是孤儿）")
    void newKeysAreReferencedBySource() throws Exception {
        File source = new File("src/main/java/com/hilltopvillage/command/SetupWizard.java");
        assertTrue(source.isFile(), "找不到 SetupWizard.java，路径=" + source.getAbsolutePath());
        String text = java.nio.file.Files.readString(source.toPath(), StandardCharsets.UTF_8);
        for (String key : new String[] {"setup.stale-step", "setup.btn-back-to-step", "setup.btn-resume-step"}) {
            assertTrue(text.contains(key), "SetupWizard.java 未引用 " + key + " —— 新文案成了孤儿键");
        }
    }
}
