package com.hilltopvillage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.ZombieMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link WaveManager} 纯函数测试（反射进入 private 方法）。
 *
 * <p>这些方法全是「参数进、结果出」、不碰任何 Bukkit 单例的算术，因此<b>不需要</b>
 * B-2（{@code Bukkit.getMobGoals()} 抛 {@code UnimplementedOperationException}）与
 * B-3（{@code setRemoveWhenFarAway} 抛异常）那一整套刷怪夹具 —— 反过来说，本类
 * <b>刻意不覆盖任何刷怪路径</b>，那部分的夹具成本这里是付不起的。</p>
 *
 * <p>被守的两个真实缺陷：</p>
 * <ul>
 *   <li><b>{@code rollCurrencyDropAmount} 的端点交换</b> —— 配置把 min/max 写反时，
 *       {@code random.nextInt(max - min + 1)} 收到负数参数会抛
 *       {@link IllegalArgumentException}，掉落逻辑在击杀回调里炸掉；</li>
 *   <li><b>64 上限钳制</b> —— 超过单堆上限的掉落会被背包操作拆开，玩家实际到手数量与播报不符。</li>
 * </ul>
 *
 * <p><b>为什么用全量插件</b>：{@code GameManager} 构造期要 {@code plugin.getConfigManager()}
 * （共享单例），而 {@code BareHost} 的 getter 全返回 null —— 用它会 NPE。所以本类付一次
 * {@code loadFullPlugin()} 的钱（实测 0.5–5 秒），换取一个真实可用的 {@code GameConfig}。</p>
 */
class WaveManagerPureLogicTest extends MockBukkitTestBase {

    private HilltopVillagePlugin plugin;
    private WaveManager waveManager;
    private GameConfig config;

    @BeforeEach
    void setUp() {
        plugin = loadFullPlugin();
        config = plugin.getConfigManager().getActiveConfig();

        World world = server.addSimpleWorld("purelogic_world");
        GameManager gameManager = new GameManager(plugin, "purelogic",
                world, new Location(world, 0, 64, 0), List.of(), List.of());
        waveManager = new WaveManager(gameManager);
    }

    private static Method privateMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> trim(Map<String, Integer> counts, int total, int limit) throws Exception {
        Method method = privateMethod(WaveManager.class, "trimProportionally",
                Map.class, int.class, int.class);
        return (Map<String, Integer>) method.invoke(waveManager, counts, total, limit);
    }

    private int rollCurrency(GameConfig cfg, LivingEntity mob) throws Exception {
        Method method = privateMethod(WaveManager.class, "rollCurrencyDropAmount",
                GameConfig.class, LivingEntity.class);
        return (int) method.invoke(waveManager, cfg, mob);
    }

    private double ringDistance(int min, int max) throws Exception {
        Method method = privateMethod(WaveManager.class, "randomRingDistance", int.class, int.class);
        return (double) method.invoke(waveManager, min, max);
    }

    // -------------------------------------------------- trimProportionally

    @Test
    @DisplayName("trimProportionally 的结果总数恰好等于 limit（最大余数法）")
    void trimHitsExactTotal() throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("a", 3);
        counts.put("b", 3);
        counts.put("c", 3);

        Map<String, Integer> trimmed = trim(counts, 9, 4);

        assertEquals(3, trimmed.size(), "键集合不应增减：" + trimmed);
        int sum = trimmed.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(4, sum,
                "配额合计 " + sum + " != limit 4 —— 波次「total-mobs 是精确上限」的契约被破坏：" + trimmed);
        assertEquals(2, trimmed.get("a"), "最大余数法应给并列中的第一个多补一个名额：" + trimmed);
        assertEquals(1, trimmed.get("b"));
        assertEquals(1, trimmed.get("c"));
    }

    @Test
    @DisplayName("trimProportionally limit=0 时全部归零且不抛异常")
    void trimZeroLimit() throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("a", 3);
        counts.put("b", 3);

        Map<String, Integer> trimmed = trim(counts, 6, 0);
        assertEquals(0, trimmed.values().stream().mapToInt(Integer::intValue).sum(),
                "limit=0 必须产出全 0：" + trimmed);
        assertEquals(0, trimmed.get("a"));
        assertEquals(0, trimmed.get("b"));
    }

    @Test
    @DisplayName("trimProportionally 对每个 limit 都恰好等于 min(limit,total)，且不出现负配额")
    void trimNeverOvershootsForAnyLimit() throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("a", 10);
        counts.put("b", 1);
        counts.put("c", 5);

        for (int limit = 0; limit <= 16; limit++) {
            Map<String, Integer> trimmed = trim(counts, 16, limit);
            int sum = trimmed.values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(Math.min(limit, 16), sum,
                    "limit=" + limit + " 时合计不对：" + trimmed);
            for (Map.Entry<String, Integer> entry : trimmed.entrySet()) {
                assertTrue(entry.getValue() >= 0,
                        "limit=" + limit + " 出现负配额：" + trimmed);
            }
        }
    }

    // ---------------------------------------------- rollCurrencyDropAmount

    @Test
    @DisplayName("rollCurrencyDropAmount min=max=0 返回 0")
    void currencyZeroRangeReturnsZero() throws Exception {
        config.setCurrencyDropMin(0);
        config.setCurrencyDropMax(0);
        assertEquals(0, rollCurrency(config, null),
                "max<=0 时必须直接返回 0，不能落进 nextInt 分支");
    }

    @Test
    @DisplayName("rollCurrencyDropAmount min=max=5 恒返回 5")
    void currencyFixedRangeIsExact() throws Exception {
        config.setCurrencyDropMin(5);
        config.setCurrencyDropMax(5);
        for (int i = 0; i < 20; i++) {
            assertEquals(5, rollCurrency(config, null), "定值区间必须恒为 5");
        }
    }

    @Test
    @DisplayName("rollCurrencyDropAmount 配置写反（min>max）不抛异常，结果落在交换后的区间")
    void currencySwapsInvertedRange() throws Exception {
        config.setCurrencyDropMin(9);
        config.setCurrencyDropMax(3);
        for (int i = 0; i < 50; i++) {
            int amount = rollCurrency(config, null);
            assertTrue(amount >= 3 && amount <= 9,
                    "写反的 [9,3] 应被修正为 [3,9]，实际得到 " + amount
                            + "。不交换会让 nextInt(max-min+1) 收到负数并抛 IllegalArgumentException");
        }
    }

    @Test
    @DisplayName("rollCurrencyDropAmount 两端都钳到 [0,64]，超上限返回 64")
    void currencyClampsToStackLimit() throws Exception {
        config.setCurrencyDropMin(100);
        config.setCurrencyDropMax(200);
        for (int i = 0; i < 20; i++) {
            assertEquals(64, rollCurrency(config, null),
                    "超过单堆上限 64 未被钳制 —— 掉落实体数量与播报数量会不一致");
        }
    }

    @Test
    @DisplayName("rollCurrencyDropAmount 负下限被钳到 0，不产生负数掉落")
    void currencyClampsNegativeFloor() throws Exception {
        config.setCurrencyDropMin(-10);
        config.setCurrencyDropMax(4);
        for (int i = 0; i < 50; i++) {
            int amount = rollCurrency(config, null);
            assertTrue(amount >= 0 && amount <= 4,
                    "负下限未钳到 0，出现越界值 " + amount);
        }
    }

    @Test
    @DisplayName("rollCurrencyDropAmount 怪无怪种 PDC 标记时整体回退全局区间")
    void currencyFallsBackToGlobalWhenMonsterUnknown() throws Exception {
        config.setCurrencyDropMin(2);
        config.setCurrencyDropMax(2);
        ZombieMock mob = new ZombieMock(server, java.util.UUID.randomUUID());
        for (int i = 0; i < 10; i++) {
            assertEquals(2, rollCurrency(config, mob),
                    "无怪种标记时必须整体回退全局 [2,2]");
        }
    }

    // ------------------------------------------------- randomRingDistance

    @Test
    @DisplayName("randomRingDistance min=max 时恒等返回")
    void ringDistanceDegenerate() throws Exception {
        assertEquals(5.0, ringDistance(5, 5), 1e-9, "退化区间必须精确返回该值");
    }

    @Test
    @DisplayName("randomRingDistance 负下限钳到 0，结果始终落在 [0,max]")
    void ringDistanceClampsNegativeMin() throws Exception {
        for (int i = 0; i < 200; i++) {
            double distance = ringDistance(-3, 4);
            assertTrue(distance >= 0.0 && distance <= 4.0,
                    "负下限未钳到 0，出现越界半径 " + distance);
        }
    }

    @Test
    @DisplayName("randomRingDistance max<min 时收敛到 min，不产生负宽度区间")
    void ringDistanceClampsInvertedMax() throws Exception {
        for (int i = 0; i < 50; i++) {
            assertEquals(8.0, ringDistance(8, 3), 1e-9,
                    "max<min 时应当收敛到 min，而不是产生负宽度区间");
        }
    }
}
