package com.hilltopvillage.mechanics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.util.LanguageManager;

/**
 * mechanics 层测试共用的「mock 插件」构造器。
 *
 * <p><b>为什么要一个共享夹具：</b>本层四个类（{@code MapRestoreManager} /
 * {@code VillagerSystem} / {@code NodeSystem} / {@code HammerListener}）都只通过
 * {@link HilltopVillagePlugin} 的 getter 触碰外部世界。每个测试类重复这 ~25 行
 * 不但啰嗦，更要命的是漏桩的后果各不相同（漏 {@code getDataFolder} 让
 * {@code ConfigManager} 把文件写到进程 CWD、漏 {@code getLogger} 让日志捕获静默失效），
 * 而失败点往往离真正的漏桩处很远。集中一处才能一次修好所有类。</p>
 *
 * <p><b>为什么不放进 {@code testsupport/}：</b>本轮写权限只覆盖
 * {@code src/test/java/com/hilltopvillage/mechanics/}，那是基础设施 Agent 的领地。</p>
 *
 * <p><b>i18n 桩值故意非空：</b>{@code LanguageManager} 是 mock，未桩的重载返回
 * {@code null}；而 {@code HammerListener#createHammer()} 会把 {@code lang.get(...)}
 * 的结果直接塞进 {@code ItemMeta#setLore}，MockBukkit 的
 * {@code LegacyComponentSerializer.deserialize(null)} 立刻 NPE。返回 {@code "x"}
 * 让所有依赖文本的路径都能跑通，且断言不看这些字符串。</p>
 */
final class MechanicsFixtures {

    private MechanicsFixtures() {
    }

    /**
     * 构造一个带真实 {@link ConfigManager} 语义（但配置对象由调用方给定的）的 mock 插件。
     *
     * @param logger     日志出口，通常来自 {@code MockBukkitTestBase.attachLogCapture}
     * @param dataFolder 插件数据目录（测试用临时目录）
     * @param config     生效配置；{@code null} 时新建出厂 {@link GameConfig}
     */
    static HilltopVillagePlugin mockPlugin(Logger logger, File dataFolder, GameConfig config) {
        HilltopVillagePlugin plugin = mock(HilltopVillagePlugin.class);
        when(plugin.getName()).thenReturn("HilltopVillage");
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.isEnabled()).thenReturn(true);

        GameConfig active = config == null ? new GameConfig() : config;
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getActiveConfig()).thenReturn(active);
        when(plugin.getConfigManager()).thenReturn(configManager);

        // 必须先赋给局部变量再 thenReturn：直接在 thenReturn(...) 里构造会让
        // Mockito 在桩还没闭合时重入 plugin 的桩，抛 UnfinishedStubbingException
        // （round2 勘察报告 B-2 记录的同一个坑）。
        LanguageManager lang = mockLanguageManager();
        when(plugin.getLanguageManager()).thenReturn(lang);
        return plugin;
    }

    /**
     * i18n mock：所有文本重载返回非空占位串（理由见类注释）。
     *
     * <p>{@code getFor(Player, String, Object...)} 与 {@code get(String, String, Object...)}
     * 必须显式桩 —— Mockito 的 varargs 匹配在「零个可变参数」时不会命中
     * {@code any()}，会静默落到默认 {@code null}。</p>
     */
    static LanguageManager mockLanguageManager() {
        LanguageManager lang = mock(LanguageManager.class);
        when(lang.getLocale()).thenReturn("en");
        when(lang.get(any(String.class))).thenReturn("x");
        when(lang.get(any(String.class), any(String.class))).thenReturn("x");
        when(lang.get(any(String.class), any(String.class), any())).thenReturn("x");
        when(lang.getFor(any(Player.class), any(String.class))).thenReturn("x");
        when(lang.getFor(any(Player.class), any(String.class), any())).thenReturn("x");
        return lang;
    }
}
