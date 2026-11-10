package com.hilltopvillage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GameConfig} 的快照隔离、值语义与两个曾漂移的默认值。
 *
 * <p><b>这个类守的是什么</b>：{@code snapshot()} 是「未保存改动」判定的全部基础 ——
 * {@code ConfigManager.hasUnsavedChanges()} 拿它跟 {@code activeConfig} 做 {@code equals}，
 * 只要快照与活配置共享了任何一个可变子对象，快照就会被后续编辑「污染」，
 * 于是 {@code hasUnsavedChanges()} 永远返回 false，保存按钮再也不会亮。
 * 这种缺陷不会抛异常、不会打日志，只会让管理员的修改在某次重启后凭空消失。
 *
 * <p><b>纯 JUnit5，零 mock</b>：构造器只用 {@code Material} / {@code EntityType} /
 * {@code PotionEffectType} 的<b>枚举常量</b>（不调 {@code isItem()} 之类的方法），
 * 实测不需要任何 server。这是本仓库最便宜的高价值测试。
 */
class GameConfigTest {

    // ---------------------------------------------------------------- 默认值（曾漂移）

    @Test
    @DisplayName("默认值: hammerMaterial 必须是 IRON_AXE（与出厂 game-settings.yml 的 hammer.material 一致）")
    void hammerMaterialDefaultMatchesShippedTemplate() {
        assertEquals(Material.IRON_AXE, new GameConfig().getHammerMaterial(),
                "GameConfig 的 hammerMaterial 初值必须与出厂模板 game-settings.yml 的 hammer.material 一致。"
                        + " loadFromFile() 用 contains 守卫（键缺失即保留代码初值），saveToFile() 又无条件回写，"
                        + " 两侧不一致时「删掉该键 → 保存」会把模板从未声明过的材质固化进服务器配置");
    }

    @Test
    @DisplayName("默认值: coreY 必须是 100.0（与出厂 game-settings.yml 的 core.y 一致）")
    void coreYDefaultMatchesShippedTemplate() {
        assertEquals(100.0, new GameConfig().getCoreY(),
                "coreY 初值必须是 100.0（地板高度附近），不能是 0.0（基岩层）。"
                        + " 这是 F-006：0.0 会让删键后的保存把模板从未声明过的高度固化进服务器配置");
    }

    @Test
    @DisplayName("默认值: 从 JAR 里的出厂模板反查，两个键的值确实与代码默认值相同")
    void shippedTemplateAgreesWithCodeDefaults(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        // 直接读 classpath 上的 game-settings.yml（src/main/resources），不再经 ConfigManager。
        // 这样「代码默认值 ↔ 出厂模板」的一致性由一条断言钉死，
        // 而不是靠两个文件各自演进时有人记得同步。
        YamlConfiguration template = new YamlConfiguration();
        try (InputStream in = getClass().getResourceAsStream("/game-settings.yml")) {
            if (in == null) throw new AssertionError("classpath 上找不到 /game-settings.yml");
            template.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        assertEquals(template.getString("hammer.material"), new GameConfig().getHammerMaterial().name(),
                "出厂模板的 hammer.material 与 GameConfig 初值不一致");
        assertEquals(template.getDouble("core.y"), new GameConfig().getCoreY(),
                "出厂模板的 core.y 与 GameConfig 初值不一致");
    }

    // ---------------------------------------------------------------- 快照隔离

    @Test
    @DisplayName("snapshot: monsters 深拷贝 —— 改活配置的怪物属性不影响快照")
    void snapshotIsolatesMonsters() {
        GameConfig live = new GameConfig();
        String key = live.getMonsters().keySet().iterator().next();
        double originalHealth = live.getMonsters().get(key).getHealth();

        GameConfig snapshot = live.snapshot();
        live.getMonsters().get(key).setHealth(999.0);

        assertEquals(originalHealth, snapshot.getMonsters().get(key).getHealth(), 1e-9,
                "快照里的怪物必须是独立实例。共享实例会让 lastSavedSnapshot 跟着活配置一起变，"
                        + " hasUnsavedChanges() 永远 false");
        assertNotSame(live.getMonsters().get(key), snapshot.getMonsters().get(key),
                "快照里的 MonsterConfig 不能是同一个对象引用");
    }

    @Test
    @DisplayName("snapshot: waves 深拷贝（含 compositions 层）—— 改活配置的波次不影响快照")
    void snapshotIsolatesWavesDeeply() {
        GameConfig live = new GameConfig();
        GameConfig snapshot = live.snapshot();

        // 找一条既有 wave 又有 composition 的波次（构造器预置 1/5/10）
        Integer waveNumber = null;
        String mobKey = null;
        for (Map.Entry<Integer, GameConfig.WaveConfig> entry : live.getWaves().entrySet()) {
            if (!entry.getValue().getCompositions().isEmpty()) {
                waveNumber = entry.getKey();
                mobKey = entry.getValue().getCompositions().keySet().iterator().next();
                break;
            }
        }
        assertTrue(waveNumber != null && mobKey != null,
                "前置条件：构造器应预置至少一条带 compositions 的波次");

        int snapshotTotalMobs = snapshot.getWaves().get(waveNumber).getTotalMobs();
        int snapshotCompCount = snapshot.getWaves().get(waveNumber).getCompositions().get(mobKey).getCount();

        live.getWaves().get(waveNumber).setTotalMobs(777);
        live.getWaves().get(waveNumber).getCompositions().get(mobKey).setCount(888);

        assertEquals(snapshotTotalMobs, snapshot.getWaves().get(waveNumber).getTotalMobs(),
                "WaveConfig.totalMobs 未被深拷贝 —— 活配置改动泄漏进快照");
        assertEquals(snapshotCompCount,
                snapshot.getWaves().get(waveNumber).getCompositions().get(mobKey).getCount(),
                "MobComposition.count 未被深拷贝 —— WaveConfig.copy() 传了新的 map，"
                        + " 但 composition 实例若被共享，第二层仍会泄漏");
    }

    @Test
    @DisplayName("nodeBlockTypes 已删除：节点方块固定为 RESPAWN_ANCHOR，GameConfig 不得再暴露该字段/访问器")
    void nodeBlockTypesIsNotReintroducedAsFakeConfig() {
        // round5 F7：该字段曾被预置为 4 种材质（BEACON / ENCHANTING_TABLE / ENDER_CHEST /
        // RESPAWN_ANCHOR），但 NodeSystem#loadConfig 硬编码只认 RESPAWN_ANCHOR，YAML 与 GUI
        // 都没有编辑入口，saveToFile 也从不写它 —— 是「永不生效的假配置」。
        // 断言零消费者很容易（删掉就永远通过），所以这里改为钉住**决定本身**：
        // 字段与 getter/setter 都不得重新出现，否则「配置能换节点方块」的错误暗示会复活。
        assertTrue(Arrays.stream(GameConfig.class.getDeclaredFields())
                        .noneMatch(field -> field.getName().equals("nodeBlockTypes")),
                "GameConfig 不得重新引入 nodeBlockTypes 字段 —— 节点方块是 RESPAWN_ANCHOR 固定值，"
                        + " 字段存在而没有消费者只会让维护者以为改它能换节点方块");
        assertTrue(Arrays.stream(GameConfig.class.getDeclaredMethods())
                        .noneMatch(method -> method.getName().equals("getNodeBlockTypes")
                                || method.getName().equals("setNodeBlockTypes")),
                "GameConfig 不得重新引入 getNodeBlockTypes/setNodeBlockTypes —— 同上");
    }

    @Test
    @DisplayName("snapshot: nodeBuffEffects 是新的 map —— 改活配置的 Buff 表不影响快照")
    void snapshotIsolatesNodeBuffEffects() {
        GameConfig live = new GameConfig();
        GameConfig snapshot = live.snapshot();

        int snapshotSize = snapshot.getNodeBuffEffects().size();
        live.getNodeBuffEffects().put(org.bukkit.potion.PotionEffectType.SPEED, 3);

        assertEquals(snapshotSize, snapshot.getNodeBuffEffects().size(),
                "nodeBuffEffects 必须 new LinkedHashMap<>(...) 拷贝，否则编辑 Buff 表会污染快照");
        assertNotSame(live.getNodeBuffEffects(), snapshot.getNodeBuffEffects());
    }

    @Test
    @DisplayName("snapshot: starterKitItems 逐项深拷贝 —— 改活配置的套装数量不影响快照")
    void snapshotIsolatesStarterKit() {
        GameConfig live = new GameConfig();
        GameConfig snapshot = live.snapshot();

        assertFalse(live.getStarterKitItems().isEmpty(), "前置条件：应有默认套装");
        int snapshotAmount = snapshot.getStarterKitItems().get(0).getAmount();

        live.getStarterKitItems().get(0).setAmount(99);

        assertEquals(snapshotAmount, snapshot.getStarterKitItems().get(0).getAmount(),
                "StarterKitItem 必须逐项 copy() —— 只 new ArrayList<> 会共享条目实例，"
                        + " 改数量就会污染快照");
        assertNotSame(live.getStarterKitItems().get(0), snapshot.getStarterKitItems().get(0));
    }

    @Test
    @DisplayName("snapshot: 标量字段全量搬运 —— 快照与独立构造的新实例相等")
    void snapshotOfUntouchedConfigEqualsFreshConfig() {
        GameConfig live = new GameConfig();

        GameConfig snapshot = live.snapshot();

        assertEquals(new GameConfig(), snapshot,
                "未改动的配置，其快照必须等于一个全新的默认配置。"
                        + " 若某个字段没被 snapshot() 搬运，它会取新实例的初值 —— "
                        + "对默认配置来说恰好相同，所以这条断言单独不足以发现漏拷；"
                        + " 下面 snapshotCoversEveryDeclaredField 才是漏拷的真正守卫");
        assertNotSame(live, snapshot);
    }

    // ---------------------------------------------------------------- equals 语义

    @Test
    @DisplayName("equals: 任一字段被改后立刻为 false（hasUnsavedChanges 的语义底座）")
    void equalsDetectsSingleFieldMutation() {
        GameConfig a = new GameConfig();
        GameConfig b = a.snapshot();
        assertTrue(a.equals(b), "前置条件：快照应与原配置相等");

        a.setVictoryWaves(a.getVictoryWaves() + 1);

        assertFalse(a.equals(b),
                "改一个字段后 equals 必须立刻变 false —— 这是 hasUnsavedChanges() 唯一的数据来源");
    }

    @Test
    @DisplayName("equals: 与 null / 其它类型比较返回 false，不抛异常")
    void equalsIsNullSafe() {
        GameConfig config = new GameConfig();

        assertFalse(config.equals(null), "equals(null) 必须是 false");
        assertFalse(config.equals("not a GameConfig"), "跨类型比较必须是 false");
        assertTrue(config.equals(config), "自反性");
    }

    /**
     * <b>反射守护</b>：每个非 static 实例字段都必须同时被 {@code snapshot()} 搬运、
     * 且在 {@code equals()} 里参与比较。
     *
     * <p>做法是最小侵入式的 —— 不比对源码文本，而是：给每个字段赋一个与默认值不同的值，
     * 然后检查<b>两件事</b>：
     * <ol>
     *   <li>{@code snapshot()} 的对应字段是否跟着变了（<b>漏拷</b>检测）；</li>
     *   <li>{@code this.equals(this.snapshot())} 是否变成 false（<b>equals 漏比较</b>检测）。</li>
     * </ol>
     *
     * <p>两种漏法后果不同但都静默：漏拷 → 该字段的修改永远不会被写回，快照永远是旧值；
     * 漏 equals → 该字段的修改永远不会让「未保存」亮起。两者都不会抛异常。
     */
    @Test
    @DisplayName("equals/snapshot: 每个实例字段都被 snapshot() 搬运且被 equals() 比较")
    void snapshotAndEqualsCoverEveryDeclaredField() throws Exception {
        List<String> notSnapshotted = new ArrayList<>();
        List<String> notCompared = new ArrayList<>();

        for (Field field : GameConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
            if (field.isSynthetic()) continue;

            GameConfig probe = new GameConfig();
            // ① 先拍一张「变异前」的快照 —— 这是 equals 的对照组
            GameConfig beforeMutation = probe.snapshot();
            if (!probe.equals(beforeMutation)) continue;   // 默认配置自身不自洽，跳过（另有断言覆盖）

            field.setAccessible(true);
            Object mutated = mutate(field.get(probe));
            if (mutated == null) continue;                 // 类型未知，无法构造变异值

            field.set(probe, mutated);

            // ② 现在再拍一张：变异之后拍的快照必须带上新值。
            // 注意必须「变异后再 snapshot()」—— 拿变异前那张去比，漏拷的字段会因为它自己也
            // 是默认值而恰好「不相等」，检查变成永远通过（本文件第一版就栽在这里）。
            GameConfig afterMutation = probe.snapshot();
            if (!java.util.Objects.equals(field.get(afterMutation), mutated)) {
                notSnapshotted.add(field.getName());
            }
            // ③ equals 是否察觉这次变异
            if (probe.equals(beforeMutation)) {
                notCompared.add(field.getName());
            }
        }

        assertTrue(notSnapshotted.isEmpty(),
                "以下字段被改动后 snapshot() 里仍是旧值 —— snapshot() 漏拷了它们，"
                        + " 于是该字段的修改永远不会落盘：" + notSnapshotted);
        assertTrue(notCompared.isEmpty(),
                "以下字段被改动后 equals(快照) 仍为 true —— equals() 漏比较了它们，"
                        + " 于是改它不会让「未保存」亮起来：" + notCompared);
    }

    /**
     * 为一个字段值造一个「与默认值不同」的替代值。返回 null 表示不知道怎么造。
     *
     * <p>只处理 {@link GameConfig} 里实际出现的类型；新字段若用了没覆盖的类型，
     * 会以 null 返回并被跳过（宁可漏检也不要假绿）。


     */
    private static Object mutate(Object original) {
        if (original == null) return null;
        Class<?> type = original.getClass();

        if (type == String.class) {
            return original.equals("__mutated__") ? "__mutated2__" : "__mutated__";
        }
        if (type == int.class || type == Integer.class) {
            return ((Integer) original) + 1;
        }
        if (type == double.class || type == Double.class) {
            return ((Double) original) + 1.0;
        }
        if (type == boolean.class || type == Boolean.class) {
            return !((Boolean) original);
        }

        // 容器的处理：默认值非空时直接换成一个「已知不同」的元素/条目
        if (original instanceof List<?> list && !list.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object> copy = new ArrayList<>((List<Object>) original);
            copy.remove(0);
            return copy;
        }
        if (original instanceof Map<?, ?> map && !map.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> copy = new LinkedHashMap<>((Map<Object, Object>) original);
            copy.clear();
            return copy;
        }
        if (original instanceof Material material) {
            // 换一个确定不同的合法材质
            return material == Material.STONE ? Material.DIRT : Material.STONE;
        }
        // 自定义可变的 POJO（WaveConfig / MonsterConfig / StarterKitItem）
        if (original instanceof GameConfig.WaveConfig wave) {
            GameConfig.WaveConfig copy = wave.copy();
            copy.setTotalMobs(copy.getTotalMobs() + 1);
            return copy;
        }
        if (original instanceof GameConfig.MonsterConfig monster) {
            GameConfig.MonsterConfig copy = monster.copy();
            copy.setHealth(copy.getHealth() + 1.0);
            return copy;
        }
        if (original instanceof GameConfig.StarterKitItem item) {
            GameConfig.StarterKitItem copy = item.copy();
            copy.setAmount(copy.getAmount() + 1);
            return copy;
        }
        return null;
    }

    // ---------------------------------------------------------------- 升级归一化

    @Test
    @DisplayName("isLegacyDefaultStarterKit: 旧默认列表命中、新默认不命中、顺序打乱不命中")
    void legacyStarterKitDetectionIsOrderSensitive() {
        assertTrue(GameConfig.isLegacyDefaultStarterKit(GameConfig.legacyDefaultStarterKitItems()),
                "旧默认列表本身必须被识别为 legacy（否则存量服务器升级后永远拿不到皮革四件套）");
        assertFalse(GameConfig.isLegacyDefaultStarterKit(GameConfig.defaultStarterKitItems()),
                "新默认列表不得被判为 legacy —— 否则每次加载都会被「升级」一次");
        assertFalse(GameConfig.isLegacyDefaultStarterKit(null), "null 必须是 false，不是 NPE");

        List<GameConfig.StarterKitItem> shuffled = new ArrayList<>(GameConfig.legacyDefaultStarterKitItems());
        java.util.Collections.reverse(shuffled);
        assertFalse(GameConfig.isLegacyDefaultStarterKit(shuffled),
                "顺序打乱后不得判为 legacy —— List.equals 是顺序敏感的，这是刻意规格："
                        + " 服主显式排过的顺序不能被当成出厂值覆盖");

        List<GameConfig.StarterKitItem> retallied = new ArrayList<>(GameConfig.legacyDefaultStarterKitItems());
        retallied.get(0).setAmount(2);
        assertFalse(GameConfig.isLegacyDefaultStarterKit(retallied),
                "数量被改过不得判为 legacy —— 服主改过的配置必须原样保留");
    }
}
