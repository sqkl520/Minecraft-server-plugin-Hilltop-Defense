package com.hilltopvillage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * #7 载入侧数值钳制：磁盘上的非法/越界值必须被收敛到可玩区间，并且收敛本身要进入
 * {@code hasUnsavedChanges()}，否则主菜单会显示「已是最新」，管理员无法把修正后的值写回。
 *
 * <p>同时钉住两条边界：{@code explode-damage-multiplier: 0} 是合法的（多个怪物模板就是 0），
 * 合法值一个字都不许改。</p>
 */
class ConfigLoadClampTest extends MockBukkitTestBase {

    private static File settingsFile(BareHost host) {
        return new File(host.getDataFolder(), "game-settings.yml");
    }

    private static YamlConfiguration minimal() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("world-name", "world");
        yaml.set("min-players", 2);
        yaml.set("max-players", 6);
        yaml.set("victory-waves", 20);
        yaml.set("nodes.repair-cost-amount", 1);
        yaml.set("monsters.explode-beetle.explode-damage-multiplier", 3.0);
        return yaml;
    }

    private ConfigManager loadWith(YamlConfiguration yaml) throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(settingsFile(host), yaml.saveToString());
        return new ConfigManager(host);
    }

    private static double diskDouble(File file, String path) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(file));
        return yaml.getDouble(path);
    }

    private static int diskInt(File file, String path) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(file));
        return yaml.getInt(path);
    }

    private static void handEdit(File file, String path, Object value) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(MockBukkitTestBase.TestPaths.read(file));
        yaml.set(path, value);
        MockBukkitTestBase.TestPaths.write(file, yaml.saveToString());
        Files.setLastModifiedTime(file.toPath(),
                FileTime.fromMillis(System.currentTimeMillis() + 5_000L));
    }

    @Test
    @DisplayName("loadFromFile: victory-waves=0、repair-cost=0、explode=NaN 全部被钳制并标记待保存")
    void loadClampsUnsafeValuesAndMarksDirty() throws Exception {
        YamlConfiguration yaml = minimal();
        yaml.set("victory-waves", 0);
        yaml.set("nodes.repair-cost-amount", 0);
        yaml.set("monsters.explode-beetle.explode-damage-multiplier", Double.NaN);

        ConfigManager config = loadWith(yaml);

        assertEquals(1, config.getActiveConfig().getVictoryWaves(),
                "victory-waves=0 会让首波即判胜；必须钳到 GUI 下界 1");
        assertEquals(1, config.getActiveConfig().getRepairCostAmount(),
                "repair-cost-amount=0 是免费修复；必须钳到 1");
        assertEquals(0.0,
                config.getActiveConfig().getMonsters().get("explode-beetle")
                        .getExplodeDamageMultiplier(),
                1e-9, "NaN 不能进入 createExplosion；非有限值收敛到安全值 0");
        assertTrue(config.hasUnsavedChanges(),
                "★ 载入期改写了内存值就必须登记 loadTimeNormalizationPending，"
                        + "否则主菜单显示「已是最新」、保存按钮熄灭，钳制只存在于内存");
    }

    @Test
    @DisplayName("loadFromFile: 合法值不被改写（explode=0 合法，victory=37，repair=5）")
    void loadLeavesLegalValuesUntouched() throws Exception {
        YamlConfiguration yaml = minimal();
        yaml.set("victory-waves", 37);
        yaml.set("nodes.repair-cost-amount", 5);
        yaml.set("monsters.explode-beetle.explode-damage-multiplier", 0.0);

        ConfigManager config = loadWith(yaml);

        assertEquals(37, config.getActiveConfig().getVictoryWaves());
        assertEquals(5, config.getActiveConfig().getRepairCostAmount());
        assertEquals(0.0,
                config.getActiveConfig().getMonsters().get("explode-beetle")
                        .getExplodeDamageMultiplier(),
                1e-9, "0 是部分怪物模板的合法值，不得被当成「未配置」抬到 0.5/3.0");
        assertFalse(config.hasUnsavedChanges(),
                "合法配置不应被误标为待保存（否则每次启动都逼管理员保存一次）");
    }

    @Test
    @DisplayName("loadFromFile: 负数/超大值分别钳到边界（victory=-5→1，explode=500→10）")
    void loadClampsBothDirections() throws Exception {
        YamlConfiguration yaml = minimal();
        yaml.set("victory-waves", -5);
        yaml.set("nodes.repair-cost-amount", -3);
        yaml.set("monsters.explode-beetle.explode-damage-multiplier", 500.0);

        ConfigManager config = loadWith(yaml);

        assertEquals(1, config.getActiveConfig().getVictoryWaves());
        assertEquals(1, config.getActiveConfig().getRepairCostAmount());
        assertEquals(10.0,
                config.getActiveConfig().getMonsters().get("explode-beetle")
                        .getExplodeDamageMultiplier(),
                1e-9, "超大倍率必须收敛到 GUI 上限 10");
    }

    @Test
    @DisplayName("loadFromFile: ±Infinity 分别收敛到 10/0，不能把非有限值写进 GameConfig")
    void infinityClampsToFiniteBounds() throws Exception {
        YamlConfiguration positive = minimal();
        positive.set("monsters.explode-beetle.explode-damage-multiplier",
                Double.POSITIVE_INFINITY);
        ConfigManager positiveConfig = loadWith(positive);
        assertEquals(10.0,
                positiveConfig.getActiveConfig().getMonsters().get("explode-beetle")
                        .getExplodeDamageMultiplier(),
                1e-9, "+Infinity 必须收敛到 GUI 上限 10");
        assertTrue(positiveConfig.hasUnsavedChanges(), "钳制改写了内存值，必须登记待保存");

        YamlConfiguration negative = minimal();
        negative.set("monsters.explode-beetle.explode-damage-multiplier",
                Double.NEGATIVE_INFINITY);
        ConfigManager negativeConfig = loadWith(negative);
        assertEquals(0.0,
                negativeConfig.getActiveConfig().getMonsters().get("explode-beetle")
                        .getExplodeDamageMultiplier(),
                1e-9, "-Infinity 必须收敛到安全下界 0");
        assertTrue(negativeConfig.hasUnsavedChanges(), "钳制改写了内存值，必须登记待保存");
    }

    @Test
    @DisplayName("mergeExternalChanges: 载入后手改成非法值，下一次保存必须把钳制结果写回磁盘")
    void externalHandEditIsClampedOnSave() throws Exception {
        BareHost host = loadBareHost();
        MockBukkitTestBase.TestPaths.write(settingsFile(host), minimal().saveToString());
        ConfigManager config = new ConfigManager(host);

        handEdit(settingsFile(host), "victory-waves", 0);
        handEdit(settingsFile(host), "nodes.repair-cost-amount", -3);
        handEdit(settingsFile(host),
                "monsters.explode-beetle.explode-damage-multiplier", 500.0);

        assertTrue(config.saveToFile(), "落盘应成功");

        assertEquals(1, config.getActiveConfig().getVictoryWaves(),
                "外部手改的 0 必须在合并阶段被钳制");
        assertEquals(1, config.getActiveConfig().getRepairCostAmount(),
                "外部手改的负数必须在合并阶段被钳制");
        assertEquals(10.0,
                config.getActiveConfig().getMonsters().get("explode-beetle")
                        .getExplodeDamageMultiplier(),
                1e-9);
        assertEquals(1, diskInt(settingsFile(host), "victory-waves"),
                "★ 磁盘也必须被写成钳制后的值，而不是把 0 原样留下");
        assertEquals(1, diskInt(settingsFile(host), "nodes.repair-cost-amount"));
        assertEquals(10.0, diskDouble(settingsFile(host),
                "monsters.explode-beetle.explode-damage-multiplier"), 1e-9);
        assertFalse(config.hasUnsavedChanges(),
                "保存成功后待保存状态必须归零");
    }
}
