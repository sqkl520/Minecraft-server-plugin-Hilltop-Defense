package com.hilltopvillage.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.shop.ShopManager;
import com.hilltopvillage.stats.StatsManager;

/**
 * 测试基础设施的<b>自检</b>：证明 {@link BareHost} + {@link MockBukkitTestBase} 这套夹具
 * 真的能承载生产代码，而不是"编译通过但一用就炸"。
 *
 * <p>本类不验证任何业务逻辑，它验证的是<b>夹具本身</b>：宿主活着、三个共享管理器能构造、
 * {@code saveToFile()} 能落盘、世界与玩家能创建、{@code ItemStack} 能用。
 * 后续所有 MockBukkit 测试都建立在这些前提上；本类红了说明基础设施坏了，
 * 不该先去怀疑业务测试。</p>
 */
class TestInfrastructureProbeTest extends MockBukkitTestBase {

    @Test
    @DisplayName("夹具: mock 服务端可用，且未登录任何玩家")
    void serverIsAlive() {
        assertNotNull(server, "ServerMock 未创建");
        assertTrue(server.getOnlinePlayers().isEmpty(), "新 mock 服务端不应有在线玩家");
        assertNotNull(server.getScheduler(), "调度器不可用");
    }

    @Test
    @DisplayName("夹具: BareHost 能加载、启用，且 onEnable 真的没跑全量启动链")
    void bareHostLoads() {
        BareHost host = loadBareHost();

        assertNotNull(host, "宿主未加载");
        assertTrue(host.isEnabled(), "宿主应处于 enabled 状态");
        assertNotNull(host.getDataFolder(), "getDataFolder() 为 null");
        assertTrue(host.getDataFolder().isDirectory(), "数据目录不存在：" + host.getDataFolder());
        assertNotNull(host.getLogger(), "getLogger() 为 null");

        // onEnable 被架空的最强证据：父类的共享 manager 字段全部仍是 null。
        // 若哪天有人把 BareHost.onEnable 改成 super.onEnable()，这里立刻红。
        assertEquals(null, host.getConfigManager(), "BareHost 不应装配 ConfigManager");
        assertEquals(null, host.getMultiArenaManager(), "BareHost 不应装配 MultiArenaManager");
        assertEquals(null, host.getStatsManager(), "BareHost 不应装配 StatsManager");
    }

    @Test
    @DisplayName("夹具: new ConfigManager(host) 成功，且能从 JAR 释放默认配置")
    void configManagerConstructs() {
        BareHost host = loadBareHost();

        ConfigManager config = new ConfigManager(host);

        assertNotNull(config.getActiveConfig(), "activeConfig 为 null");
        assertNotNull(config.getDisplayConfig(), "displayConfig 为 null");
        // 出厂默认值：守卫战胜利波次 20、怪物 3 种。这两个数变了说明资源没释放成功
        // （拿到的是空配置而不是 src/main/resources 里的模板）。
        assertEquals(20, config.getActiveConfig().getVictoryWaves(),
                "未读到出厂 victory-waves —— saveResource 可能失败");
        assertFalse(config.getActiveConfig().getMonsters().isEmpty(),
                "未读到出厂 monsters 段 —— saveResource 可能失败");

        // 三个配置文件都应已释放到数据目录
        File folder = host.getDataFolder();
        assertTrue(new File(folder, "game-settings.yml").isFile(), "game-settings.yml 未释放");
        assertTrue(new File(folder, "monsters-config.yml").isFile(), "monsters-config.yml 未释放");
        assertTrue(new File(folder, "display.yml").isFile(), "display.yml 未释放");
    }

    @Test
    @DisplayName("夹具: ConfigManager.saveToFile() 能真正落盘（写盘链路不依赖真实服务器）")
    void configManagerSavesToDisk() throws Exception {
        BareHost host = loadBareHost();
        ConfigManager config = new ConfigManager(host);

        config.getActiveConfig().setVictoryWaves(7);
        assertTrue(config.hasUnsavedChanges(), "改配置后应标记为未保存");

        boolean saved = config.saveToFile();

        assertTrue(saved, "saveToFile() 返回 false —— 落盘被拒绝");
        assertFalse(config.hasUnsavedChanges(), "保存后 hasUnsavedChanges() 应为 false");

        String disk = MockBukkitTestBase.TestPaths.read(
                new File(host.getDataFolder(), "game-settings.yml"));
        assertTrue(disk.contains("victory-waves: 7"),
                "磁盘上未出现 victory-waves: 7，实际内容片段："
                        + disk.substring(0, Math.min(200, disk.length())));
    }

    @Test
    @DisplayName("夹具: StatsManager 能构造并在 temp 数据目录落盘")
    void statsManagerConstructs() {
        BareHost host = loadBareHost();

        StatsManager stats = new StatsManager(host);

        assertNotNull(stats.top(StatsManager.StatType.KILLS, 10),
                "top() 返回 null —— 统计缓存不可用");
        assertTrue(stats.top(StatsManager.StatType.KILLS, 10).isEmpty(),
                "全新数据目录不应有历史统计");
    }

    @Test
    @DisplayName("夹具: ShopManager 能构造（双子构造器），且读到出厂商品表")
    void shopManagerConstructs() {
        BareHost host = loadBareHost();
        ShopManager shop = new ShopManager(host, new com.hilltopvillage.util.LanguageManager(host));

        shop.reload();
        assertNotNull(shop.getShopConfig(), "ShopManager.getShopConfig() 为 null");
        assertFalse(shop.getShopConfig().getItems().isEmpty(),
                "未读到出厂商品 —— shop.yml 可能未释放");
    }

    @Test
    @DisplayName("夹具: 能创建世界并放置方块")
    void worldCanBeCreated() {
        WorldMock world = server.addSimpleWorld("hilltop_arena1");

        assertNotNull(world, "addSimpleWorld 返回 null");
        assertSame(world, server.getWorld("hilltop_arena1"), "getWorld 拿到的不是同一个实例");

        world.getBlockAt(3, 64, 3).setType(Material.STONE);
        assertEquals(Material.STONE, world.getBlockAt(3, 64, 3).getType(),
                "方块写入未生效");

        // 区块必须显式 load —— SafeSpawnUtil.findSafeGround 在 isChunkLoaded 为 false 时
        // 静默返回 null，会让所有刷怪测试"0 只怪且无异常"
        world.getChunkAt(0, 0).load();
        assertTrue(world.isChunkLoaded(0, 0), "区块未加载");
    }

    @Test
    @DisplayName("夹具: 能添加玩家并拿到真实 Player 实例")
    void playerCanBeAdded() {
        server.addSimpleWorld("lobby");
        PlayerMock player = server.addPlayer("ProbeTester");

        assertNotNull(player, "addPlayer 返回 null");
        assertTrue(player.isOnline(), "玩家应在线");
        assertNotNull(player.getUniqueId(), "player UUID 为 null");
        Player asBukkit = server.getPlayer(player.getUniqueId());
        assertNotNull(asBukkit, "Bukkit.getPlayer(uuid) 拿不到刚添加的玩家");
        assertEquals(player.getName(), asBukkit.getName(), "玩家名不一致");
    }

    @Test
    @DisplayName("夹具: ItemStack / ItemMeta / PersistentDataContainer 可用")
    void itemStackWorks() {
        ItemStack stack = new ItemStack(Material.EMERALD, 5);

        assertEquals(Material.EMERALD, stack.getType());
        assertEquals(5, stack.getAmount());
        ItemMeta meta = stack.getItemMeta();
        assertNotNull(meta, "getItemMeta() 返回 null");

        // 注意：Bukkit 的 ItemStack#getItemMeta() 返回的是<b>防御性副本</b>。
        // 必须把副本存进变量、改完再 setItemMeta 回去。写成
        //     stack.getItemMeta().setDisplayName("x");
        //     stack.setItemMeta(stack.getItemMeta());   // ← 这里拿到的是另一个干净副本
        // 是错的（本测试第一版就踩了这个坑），与 MockBukkit 无关。
        meta.setDisplayName("测试水晶");
        stack.setItemMeta(meta);
        assertEquals("测试水晶", stack.getItemMeta().getDisplayName(), "displayName 未写回");

        assertNotNull(stack.getItemMeta().getPersistentDataContainer(),
                "PersistentDataContainer 不可用 —— 所有 PDC 防伪测试都会受影响");
    }

    @Test
    @DisplayName("夹具: 出厂四世界预建后，Bukkit.getWorldContainer 的未实现陷阱可被绕开")
    void factoryWorldsSatisfyTheLoadPrecondition() {
        createFactoryWorlds();

        for (String name : FACTORY_WORLD_NAMES) {
            World world = server.getWorld(name);
            assertNotNull(world, "世界缺失：" + name);
            assertInstanceOf(WorldMock.class, world, "世界不是 WorldMock：" + name);
        }
        // createFactoryWorlds 必须幂等（重复调用不应抛 DuplicateWorldException）
        createFactoryWorlds();
        assertEquals(FACTORY_WORLD_NAMES.size(),
                FACTORY_WORLD_NAMES.stream().filter(n -> server.getWorld(n) != null).count(),
                "重复调用后世界集合发生了变化");
    }

    @Test
    @DisplayName("夹具: 预建出厂世界后，全量加载真实插件成功并产出可用 ArenaInstance")
    void fullPluginLoadsWithFactoryWorlds() {
        HilltopVillagePlugin plugin = loadFullPluginWithArenas();

        assertTrue(plugin.isEnabled(), "插件未启用");
        assertNotNull(plugin.getConfigManager(), "ConfigManager 未装配");
        assertNotNull(plugin.getLanguageManager(), "LanguageManager 未装配");
        assertNotNull(plugin.getMultiArenaManager(), "MultiArenaManager 未装配");

        // 启用队列跑完后应有真实 ArenaInstance —— 这是 GameManager/ArenaInstance 类测试的前提
        var active = plugin.getMultiArenaManager().getActiveArenas();
        assertEquals(1, active.size(), "出厂配置应恰好启用 1 个竞技场，实际：" + active.size());

        var arena = plugin.getMultiArenaManager().getArena("arena1");
        assertNotNull(arena, "getArena(\"arena1\") 为 null —— 启用队列未完成？");
        assertNotNull(arena.getGameManager(), "ArenaInstance.getGameManager() 为 null");
        // arenas.yml 里 arena1 的 min-players 是 1（与全局默认 2 不同）。
        // 注意 GameManager#getArenaMinPlayers() 是 private，只能经 ArenaInstance#getMinPlayers() 断言。
        assertEquals(1, arena.getMinPlayers(),
                "per-arena min-players 不是 1，说明读到的可能是全局兜底值");
    }

    @Test
    @DisplayName("夹具: @TempDir 注入的目录与基类的 temp 数据目录相互独立")
    void tempDirsAreIndependent(@TempDir File junitTemp) {
        assertNotNull(junitTemp, "@TempDir 未注入");
        assertTrue(junitTemp.isDirectory(), "@TempDir 不是目录");
        assertNotNull(dataFolder, "基类的 dataFolder 为 null");
        assertFalse(junitTemp.toPath().toAbsolutePath().equals(dataFolder.toAbsolutePath()),
                "@TempDir 与基类 temp 目录不应是同一个");
    }

    @Test
    @DisplayName("夹具: 日志捕获器能挂上并读到 mock 服务端日志")
    void logCaptureWorks() {
        Logger logger = Logger.getLogger("HilltopVillageProbe");
        MockBukkitTestBase.LogCapture capture = attachLogCapture(logger);

        logger.warning("probe-warning-marker");
        logger.severe("probe-severe-marker");

        List<String> lines = capture.lines();
        assertEquals(2, lines.size(), "应捕获两行，实际：" + lines);
        assertTrue(capture.anyContains("probe-warning-marker"), "未捕获 warning");
        assertTrue(capture.anyContains("probe-severe-marker"), "未捕获 severe");
        assertEquals(1, capture.countAt(java.util.logging.Level.WARNING), "warning 计数不对");

        capture.reset();
        assertTrue(capture.lines().isEmpty(), "reset 后应为空");
    }
}
