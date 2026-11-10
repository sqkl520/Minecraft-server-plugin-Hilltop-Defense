package com.hilltopvillage.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.VillagerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.ArenaManager;
import com.hilltopvillage.arena.ArenaRegistry;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.testsupport.BareHost;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@link NpcManager} 的回归测试 —— 9 个公开方法各有直接用例，每条关键行为都配反向对照。
 *
 * <p><b>为什么这条链要单独钉</b>：{@code NpcManager} 是「插件级单例实体」的唯一持有者。
 * 它的失败形态都是服主可见且不可逆的：</p>
 * <ul>
 *   <li>跨世界迁移保护失效 → 把别的竞技场正在对局时使用的商店当场撤走（P2-23）；</li>
 *   <li>{@code despawnIfInWorld} 大小写比较写错 → 清除竞技场 A 的商店时删了 YAML 却留下实体（P2-29）；</li>
 *   <li>{@code cleanupOrphans} 漏掉「跳过当前引用」→ 崩溃重启后把刚恢复的商店一起清掉（P2-25）。</li>
 * </ul>
 *
 * <p><b>夹具的核心难点与解法（实测探针结论）</b>：</p>
 * <ol>
 *   <li>{@code world.spawnEntity(loc, EntityType.VILLAGER)} 在 MockBukkit 4.45.0 上<b>可用</b>，
 *       返回真实的 {@code VillagerMock}，并且会出现在 {@code world.getEntities()} 里。</li>
 *   <li>但 {@code LivingEntityMock#setRemoveWhenFarAway} 在该版本<b>未实现</b>
 *       （{@code UnimplementedOperationException: Not implemented}），而 {@link NpcManager#spawnNpc}
 *       第 97 行必然调用它 —— 直接用原生实体，{@code spawnNpc} 必炸。</li>
 *   <li>{@code world.spawn(loc, VillagerMock.class)} / {@code world.createEntity(...)} /
 *       {@code world.addEntity(...)} 三条「自定义子类」路径全部不可用
 *       （{@code EntityTypesMock.create} 抛出 "Mock for entity ... was not implemented yet"，
 *       {@code addEntity} 抛 "Not implemented"）。</li>
 *   <li>{@code server.registerEntity(EntityMock)} <b>可以</b>把手工 new 出来的实体登记进全局实体表，
 *       使其出现在 {@code world.getEntities()} 中，且 {@code remove()} 会正常反登记。</li>
 * </ol>
 *
 * <p>因此夹具 = {@code spy(WorldMock)} 的 {@code spawnEntity} 返回一个
 * {@link SafeVillagerMock}（只把未实现的 {@code setRemoveWhenFarAway} 变成可观测 no-op），
 * 再 {@code server.registerEntity} 登记。这样 {@code spawnNpc} 的<b>全部生产逻辑</b>
 * 都真实跑到，且实体在 {@code getEntities()} 里可见 —— 这是
 * {@link NpcManager#cleanupOrphans()} 唯一能观测的前提。</p>
 *
 * <p>{@link SafeVillagerMock} <b>不是</b>生产代码的替代品：它只补 MockBukkit 的能力缺口，
 * 被跳过的 setter 用同名字段回读断言（{@code getRemoveWhenFarAway()}），所以「生产代码确实调了它」
 * 仍然是可断言的。</p>
 */
@DisplayName("NpcManager 商店村民生命周期")
class NpcManagerTest extends MockBukkitTestBase {

    /** 商店 NPC 的 PDC 标记键（与生产代码同一个 NamespacedKey 语义，独立构造以便断言侧自洽） */
    private static final NamespacedKey SHOP_NPC_KEY =
            new NamespacedKey("hilltopvillage", "shop-npc");

    /**
     * 只覆写 MockBukkit 4.45.0 未实现的两个方法，其余行为（PDC / 名称 / 移除 / 有效性）全部继承真实实现。
     */
    static final class SafeVillagerMock extends VillagerMock {

        private boolean removeWhenFarAway = true;

        SafeVillagerMock(org.mockbukkit.mockbukkit.ServerMock server, UUID uuid) {
            super(server, uuid);
        }

        @Override
        public void setRemoveWhenFarAway(boolean value) {
            this.removeWhenFarAway = value;
        }

        @Override
        public boolean getRemoveWhenFarAway() {
            return removeWhenFarAway;
        }
    }

    private BareHost host;
    private ShopManager shop;
    private NpcManager npc;
    private LanguageManager lang;
    private MultiArenaManager arenas;
    private ArenaManager definitions;
    private ArenaRegistry registry;
    private WorldMock world;

    @BeforeEach
    void setUp() throws IOException {
        // 宿主必须叫 "HilltopVillage"：NpcManager 用 new NamespacedKey(plugin, "shop-npc")
        // 构造 PDC 键，而 NamespacedKey(Plugin, String) 的 namespace 就是 plugin.getName()
        // 的小写形式。用 loadBareHost() 的默认名 "HilltopVillageTestHost" 会让键变成
        // hilltopvillagetesthost:shop-npc —— 与线上实体上真实写入的 hilltopvillage:shop-npc
        // 不是同一个键，凡是断言 PDC 的地方都会「看起来实现坏了」，而其实是夹具名字错了。
        host = spy(MockBukkit.loadWith(BareHost.class,
                new org.bukkit.plugin.PluginDescriptionFile(
                        "HilltopVillage", "1.0.0", BareHost.class.getName())));
        assertEquals("HilltopVillage", host.getName(),
                "夹具前提：PDC namespace 必须与生产一致，否则所有 PDC 断言都是假阴性");
        // 默认 Answer：所有 String 返回值都带上被请求的 key，让「走了 i18n 回退」可以被精确断言
        lang = mock(LanguageManager.class, invocation -> {
            if (invocation.getMethod().getReturnType() == String.class) {
                Object[] args = invocation.getArguments();
                if (args.length > 0 && args[0] instanceof String key) return "i18n:" + key;
                return "i18n";
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        when(host.getLanguageManager()).thenReturn(lang);
        when(host.getLogger()).thenReturn(Logger.getLogger("npc-manager-test-" + System.nanoTime()));

        shop = new ShopManager(host, lang);
        shop.reload();
        // resolveNpcName() 会读 plugin.getShopManager()：桩上它，配置名优先的用例才有意义
        when(host.getShopManager()).thenReturn(shop);

        // 默认「无任何活动竞技场」：liveArenaByWorld 会走 getActiveArenas() 兜底，必须是非 null
        arenas = mock(MultiArenaManager.class);
        when(arenas.getActiveArenas()).thenReturn(List.of());
        when(arenas.getArenaByWorld(anyString())).thenReturn(null);
        definitions = mock(ArenaManager.class);
        registry = new ArenaRegistry();
        when(definitions.getRegistry()).thenReturn(registry);
        when(arenas.getDefinitionManager()).thenReturn(definitions);
        when(host.getMultiArenaManager()).thenReturn(arenas);

        world = spawnableWorld("npc-world");
        npc = shop.getNpcManager();
    }

    @AfterEach
    void tearDown() {
        // 每次 spawnNpc 都会留下一个每 10 tick 的 BukkitRunnable；不收尾会混进「任务泄漏」型 flaky
        try {
            if (npc != null) npc.despawnNpc();
        } catch (RuntimeException ignored) {
            // 收尾失败不应盖过真正的断言失败
        }
    }

    // ================================================================ 夹具

    /**
     * 让该世界的 spawnEntity 返回可用的村民并登记进全局实体表。
     *
     * <p><b>为什么要把 spy 换进 {@code server} 的世界表</b>：{@code cleanupOrphans()} 遍历的是
     * {@code Bukkit.getWorlds()}，而 {@code WorldMock#getEntities()} 的过滤判据是
     * {@code entity.getWorld() == this}。若把 spy 留在 server 之外（只拿它当生成入口），
     * 实体世界引用指向 spy 而 {@code Bukkit.getWorlds()} 里只有 real，孤儿永远「扫不到」——
     * 那是夹具与生产观测口径不一致造成的假阴性。{@code removeWorld(real) + addWorld(spy)}
     * 让全局世界表与实体世界引用指向同一个对象（实测可行）。</p>
     */
    WorldMock spawnableWorld(String name) {
        WorldMock real = server.addSimpleWorld(name);
        WorldMock spied = spy(real);
        doAnswer(invocation -> {
            Location at = invocation.getArgument(0);
            SafeVillagerMock villager = new SafeVillagerMock(server, UUID.randomUUID());
            villager.setLocation(at);
            server.registerEntity(villager);
            return villager;
        }).when(spied).spawnEntity(any(Location.class), any(EntityType.class));
        server.removeWorld(real);
        server.addWorld(spied);
        return spied;
    }

    /** 世界里当前所有村民（玩家也是实体，任何计数都必须先按类型过滤）。 */
    List<Villager> villagers(World w) {
        List<Villager> out = new ArrayList<>();
        for (Entity entity : w.getEntities()) {
            if (entity instanceof Villager villager) out.add(villager);
        }
        return out;
    }

    /** 造一个只有 PDC 标记、不被 NpcManager 引用的「孤儿」实体。 */
    SafeVillagerMock orphan(WorldMock inWorld, double x, double z, boolean marked) {
        SafeVillagerMock villager = new SafeVillagerMock(server, UUID.randomUUID());
        villager.setLocation(new Location(inWorld, x, 64, z));
        if (marked) {
            villager.getPersistentDataContainer().set(SHOP_NPC_KEY, PersistentDataType.BYTE, (byte) 1);
        }
        server.registerEntity(villager);
        return villager;
    }

    /** 让某世界对应一个指定状态的活动竞技场。 */
    ArenaInstance activeArena(String arenaId, String worldName, GameState state) {
        ArenaInstance instance = mock(ArenaInstance.class);
        when(instance.getArenaId()).thenReturn(arenaId);
        when(instance.getWorldName()).thenReturn(worldName);
        when(instance.getState()).thenReturn(state);
        when(arenas.getArenaByWorld(worldName)).thenReturn(instance);
        return instance;
    }

    /** 往 registry 里塞一个带 shop-npc 坐标的竞技场定义。 */
    void defineArena(String id, String worldName, Location shopNpcLocation) {
        registry.register(ArenaDefinition.builder(id, worldName,
                        new Location(shopNpcLocation == null ? world : shopNpcLocation.getWorld(), 0, 64, 0))
                .shopNpc(shopNpcLocation)
                .build());
    }

    // ================================================================ spawnNpc

    @Nested
    @DisplayName("spawnNpc —— 生成与守护")
    class Spawn {

        @Test
        @DisplayName("正向：生成成功、实体可见、PDC 已打标、返回 true")
        void spawnsAVillagerAndMarksIt() {
            Location target = new Location(world, 3.5, 64, 3.5);

            assertTrue(npc.spawnNpc(target), "合法坐标必须返回 true");

            List<Villager> spawned = villagers(world);
            assertEquals(1, spawned.size(), "应当恰好生成 1 只村民；实际 " + spawned.size());
            Villager villager = spawned.get(0);
            assertTrue(villager.getPersistentDataContainer().has(SHOP_NPC_KEY, PersistentDataType.BYTE),
                    "没有 PDC 标记的村民在重启后既无法被识别也无法被清理 —— 这是整条链的地基");
            assertTrue(npc.isShopNpc(villager));
            assertNotNull(npc.getNpcLocation(), "生成后 getNpcLocation 不得为 null");
        }

        @Test
        @DisplayName("反向对照：非村民实体不会被误认（PDC 是唯一识别依据之一，但不是全部）")
        void onlyVillagersAreSpawned() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));

            for (Entity entity : world.getEntities()) {
                assertTrue(entity instanceof Villager,
                        "商店 NPC 必须是 VILLAGER；实际生成了 " + entity.getType());
            }
        }

        @Test
        @DisplayName("村民外观与防伤害属性全部落到位（AI/无敌/静默/不可推/持久/职业）")
        void appliesTheNpcFlagSet() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            SafeVillagerMock villager = (SafeVillagerMock) villagers(world).get(0);

            assertFalse(villager.hasAI(), "商店 NPC 不能自己走动（AI 必须关）");
            assertTrue(villager.isInvulnerable(), "防伤害属性 = 无敌，与 onNpcDamage 的取消是两道不同保险");
            assertTrue(villager.isSilent(), "村民音效会盖过商店交互音，必须静默");
            assertFalse(villager.isCollidable(), "可碰撞的商店 NPC 会把玩家顶开");
            assertTrue(villager.isPersistent(), "非持久实体会在区块卸载后消失");
            assertFalse(villager.getRemoveWhenFarAway(),
                    "removeWhenFarAway 必须为 false —— 否则玩家离远一点商店就自己消失");
            assertEquals(Villager.Profession.TOOLSMITH, villager.getProfession(),
                    "职业是外观的一部分（铁匠最像商人）");
            assertTrue(villager.isCustomNameVisible(), "名字必须可见，否则玩家不知道这是商店");
        }

        @Test
        @DisplayName("正向：pitch 被强制归零（F-4 —— 村民永远不低头/抬头）")
        void normalisesPitchToZero() {
            Location target = new Location(world, 2.5, 64, 2.5, 90f, 45f);

            assertTrue(npc.spawnNpc(target));

            assertEquals(0f, npc.getNpcLocation().getPitch(), 1.0e-6,
                    "这里是最外层兜底：旧存档里写坏的 pitch 也会在生成时被纠正");
            assertEquals(90f, npc.getNpcLocation().getYaw(), 1.0e-6,
                    "yaw 必须保留 —— 归零 pitch 不能顺带把朝向也抹掉");
        }

        @Test
        @DisplayName("反向对照：传入坐标的 pitch 被改掉时，调用方持有的 Location 不得被就地污染")
        void doesNotMutateTheCallerLocation() {
            Location target = new Location(world, 2.5, 64, 2.5, 0f, 45f);

            npc.spawnNpc(target);

            assertEquals(45f, target.getPitch(), 1.0e-6,
                    "实现用的是 location.clone()；若直接改入参，调用方的存档/预览坐标会被静默改坏");
        }

        @Test
        @DisplayName("反向：null 坐标与 null 世界均返回 false 且不留实体")
        void rejectsInvalidLocations() {
            assertFalse(npc.spawnNpc(null), "null 坐标必须被拒绝而不是 NPE");
            assertFalse(npc.spawnNpc(new Location(null, 0, 64, 0)), "无世界的坐标必须被拒绝");
            assertNull(npc.getNpcLocation(), "失败路径不得留下半个实体");
            assertEquals(0, villagers(world).size());
        }

        @Test
        @DisplayName("幂等：重复生成只保留最后一只（先 despawn 旧的）")
        void repeatedSpawnKeepsOnlyOne() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            Villager first = villagers(world).get(0);

            npc.spawnNpc(new Location(world, 8.5, 64, 8.5));

            assertEquals(1, villagers(world).size(),
                    "插件级单例实体：重复生成必须替换而非叠加，否则每改一次坐标就多一只");
            assertFalse(world.getEntities().contains(first),
                    "旧实体必须被真正 remove（不是仅仅解除引用）—— 否则它会作为无主实体留在世界里");
            assertFalse(npc.isShopNpc(first) && world.getEntities().contains(first),
                    "「还能被识别」+「还在世界里」同时成立才是残留；已 remove 的实体不再参与任何交互");
            assertEquals(8.5, npc.getNpcLocation().getX(), 1.0e-6, "新位置必须生效");
        }

        /**
         * <p><b>记录一条实测契约（不是缺陷）</b>：{@code despawnNpc()} 只调用 {@code npc.remove()}，
         * <b>不清 PDC</b>。因此对已移除实体的引用调用 {@code isShopNpc()} 仍会返回 true。</p>
         *
         * <p>这在线上无害：已 remove 的实体不在任何世界的实体表里，Bukkit 不会再把它派发给
         * {@code PlayerInteractEntityEvent} / {@code EntityDamageEvent}；真正需要兜底的是「硬崩后
         * 实体仍在世界里」的场景，那条由 PDC 判据 + {@code cleanupOrphans()} 覆盖（见
         * {@link CleanupOrphans}）。此处把行为钉住，避免将来有人「顺手」在 despawn 里清 PDC
         * 而破坏「despawn 后可安全重复调用」的幂等语义。</p>
         */
        @Test
        @DisplayName("契约：despawn 后旧引用仍带 PDC（remove 不清 PDC），但已不在世界实体表中")
        void despawnedEntityKeepsPdcButLeavesTheWorld() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            Villager old = villagers(world).get(0);

            npc.despawnNpc();

            assertFalse(world.getEntities().contains(old), "实体必须离开世界实体表");
            assertTrue(old.getPersistentDataContainer().has(SHOP_NPC_KEY, PersistentDataType.BYTE),
                    "remove() 不会清 PDC —— 这是 Bukkit 行为；测试把它钉住，防止有人误以为清理由 despawn 完成");
            assertNull(npc.getNpcLocation(), "当前引用已清空，跨世界保护不会拿旧坐标做判定");
        }

        @Test
        @DisplayName("跨世界保护（P2-23）：旧实体所在世界正在对局时拒绝迁移，旧实体留在原地")
        void refusesCrossWorldMigrationDuringMatch() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            activeArena("arena1", "npc-world", GameState.RUNNING);
            WorldMock other = spawnableWorld("npc-world-2");

            boolean moved = npc.spawnNpc(new Location(other, 4.5, 64, 4.5));

            assertFalse(moved, "对局中跨世界迁移必须被拒绝，否则会当场撤走场内玩家正在用的商店");
            assertEquals("npc-world", npc.getNpcLocation().getWorld().getName(),
                    "旧实体必须留在原世界继续服务场内玩家");
            assertEquals(0, villagers(other).size(), "目标世界不得留下任何实体");
        }

        @Test
        @DisplayName("反向对照：旧实体所在世界没有对局时，跨世界迁移正常放行")
        void allowsCrossWorldMigrationWhenIdle() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            activeArena("arena1", "npc-world", GameState.WAITING);
            WorldMock other = spawnableWorld("npc-world-3");

            assertTrue(npc.spawnNpc(new Location(other, 4.5, 64, 4.5)),
                    "WAITING 不是对局窗口 —— 过度保守会让管理员永远搬不动商店");
            assertEquals("npc-world-3", npc.getNpcLocation().getWorld().getName());
            assertEquals(0, villagers(world).size(), "迁移必须真的把旧实体撤掉");
        }

        @Test
        @DisplayName("跨世界保护的边界：第一次生成不受保护（此时没有旧实体可比）")
        void firstSpawnIsNeverBlocked() {
            // 目标世界自身有对局，但当前没有任何 NPC —— previous == null，保护不触发
            activeArena("arena2", "npc-world-fresh", GameState.RUNNING);
            WorldMock fresh = spawnableWorld("npc-world-fresh");

            assertTrue(npc.spawnNpc(new Location(fresh, 2.5, 64, 2.5)),
                    "保护判据是「旧实体在别世界」；首次生成必须能成功，否则首次部署永远失败");
        }

        @Test
        @DisplayName("头顶名称：shop.yml 配置优先（含颜色码翻译）")
        void prefersConfiguredName() throws IOException {
            TestPaths.write(new File(host.getDataFolder(), "shop.yml"), """
                    shop:
                      currency:
                        material: EMERALD
                      npc:
                        name: "&aShop Keeper"
                      gui:
                        size: 54
                      items: {}
                    """);
            shop.reload();

            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            String name = villagers(world).get(0).getCustomName();

            assertEquals("§aShop Keeper", name,
                    "配置名必须优先于 i18n，且 & 颜色码要被翻译；实际 " + name);
            assertFalse(name.startsWith("i18n:"), "走了 i18n 回退说明配置根本没被读到");
        }

        @Test
        @DisplayName("反向对照：配置名为空时回退 i18n 的 shop.npc.name")
        void fallsBackToI18nName() throws IOException {
            TestPaths.write(new File(host.getDataFolder(), "shop.yml"), """
                    shop:
                      currency:
                        material: EMERALD
                      npc:
                        name: ""
                      gui:
                        size: 54
                      items: {}
                    """);
            shop.reload();

            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));

            assertEquals("i18n:shop.npc.name", villagers(world).get(0).getCustomName(),
                    "配置为空必须回退 i18n，否则服主留空就会得到一只无名 NPC");
        }

        @Test
        @DisplayName("转向任务（F-4）：半径内的玩家会改变 yaw，但 pitch 恒为 0")
        void lookTaskYawsTowardsNearbyPlayer() {
            PlayerMock player = server.addPlayer();
            player.teleport(new Location(world, 3.5, 64, 3.5));

            npc.spawnNpc(new Location(world, 0.5, 64, 0.5));
            Villager villager = villagers(world).get(0);
            float initialYaw = villager.getLocation().getYaw();

            server.getScheduler().performTicks(30);

            assertTrue(Math.abs(villager.getLocation().getYaw() - initialYaw) > 2.0f,
                    "半径 8 格内的玩家应当让 NPC 转向；yaw 一直不动说明任务没跑起来。initial="
                            + initialYaw + " now=" + villager.getLocation().getYaw());
            assertEquals(0f, villager.getLocation().getPitch(), 1.0e-6,
                    "转向任务同样锁 pitch —— 低头 NPC 是 F-4 明确要修掉的外观");
        }

        @Test
        @DisplayName("反向对照：半径外的玩家不触发转向（LOOK_RADIUS = 8）")
        void lookTaskIgnoresDistantPlayer() {
            PlayerMock player = server.addPlayer();
            player.teleport(new Location(world, 30.5, 64, 30.5));

            npc.spawnNpc(new Location(world, 0.5, 64, 0.5));
            Villager villager = villagers(world).get(0);

            server.getScheduler().performTicks(30);

            assertEquals(0f, villager.getLocation().getYaw(), 1.0e-6,
                    "30 格外的玩家不该让远处 NPC 转头 —— 半径判据失效会让全场 NPC 一起抽");
        }
    }

    // ================================================================ findBlockingArenaId / isGameInProgress

    @Nested
    @DisplayName("findBlockingArenaId —— 迁移拒绝原因的对外查询")
    class Blocking {

        @Test
        @DisplayName("正向：跨世界且旧世界对局中 → 返回阻塞的竞技场 ID")
        void reportsBlockingArena() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            activeArena("arena-beta", "npc-world", GameState.STARTING);
            WorldMock other = spawnableWorld("npc-world-block");

            assertEquals("arena-beta", npc.findBlockingArenaId(new Location(other, 0, 64, 0)),
                    "向导据这个 ID 决定把 stop 指引指向哪一场；返回 null 会让文案指向错误的场");
        }

        @Test
        @DisplayName("反向对照：同一世界内移动时不受跨世界保护，返回 null")
        void sameWorldIsNeverBlocked() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            activeArena("arena-beta", "npc-world", GameState.RUNNING);

            assertNull(npc.findBlockingArenaId(new Location(world, 9.5, 64, 9.5)),
                    "同世界移动由调用方另行判定；这里若返回 ID，向导会误报「他场对局阻塞」");
        }

        @Test
        @DisplayName("反向：目标坐标无效 / 未生成 NPC / 旧世界无对局时一律返回 null")
        void noBlockingInBenignCases() {
            assertNull(npc.findBlockingArenaId(null), "null 目标不得 NPE");
            assertNull(npc.findBlockingArenaId(new Location(null, 0, 64, 0)), "无世界目标不得 NPE");

            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            activeArena("arena-beta", "npc-world", GameState.WAITING);
            WorldMock other = spawnableWorld("npc-world-block2");
            assertNull(npc.findBlockingArenaId(new Location(other, 0, 64, 0)),
                    "WAITING 不算对局中");
        }
    }

    @Nested
    @DisplayName("isGameInProgress —— 统一的「对局窗口」口径")
    class GameWindow {

        @Test
        @DisplayName("正向：五个状态都算对局进行中（含 VICTORY / DEFEAT 结算倒计时）")
        void countsAllInProgressStates() {
            for (GameState state : List.of(GameState.STARTING, GameState.RUNNING,
                    GameState.WAVE_INTERMISSION, GameState.VICTORY, GameState.DEFEAT)) {
                assertTrue(NpcManager.isGameInProgress(state),
                        state + " 必须算对局中 —— 结算倒计时期间玩家仍在场内用商店");
            }
        }

        @Test
        @DisplayName("反向对照：WAITING / ENDING / null 一律不算（否则管理员永远搬不动商店）")
        void excludesIdleStates() {
            for (GameState state : List.of(GameState.WAITING, GameState.ENDING)) {
                assertFalse(NpcManager.isGameInProgress(state), state + " 不算对局中");
            }
            assertFalse(NpcManager.isGameInProgress(null), "null 必须放行而不是 NPE");
        }
    }

    // ================================================================ despawnNpc / despawnIfInWorld

    @Nested
    @DisplayName("despawnNpc —— 清除与幂等")
    class Despawn {

        @Test
        @DisplayName("正向：实体被移除，getNpcLocation 归 null")
        void removesTheEntity() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            assertEquals(1, villagers(world).size());

            npc.despawnNpc();

            assertEquals(0, villagers(world).size(), "清除后世界里不得留残影");
            assertNull(npc.getNpcLocation(), "未生成时 getNpcLocation 必须为 null");
        }

        @Test
        @DisplayName("反向对照：只清自己的实体，不动世界里的其它村民")
        void doesNotTouchForeignVillagers() {
            SafeVillagerMock bystander = orphan(world, 20.5, 20.5, false);

            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            npc.despawnNpc();

            assertTrue(world.getEntities().contains(bystander),
                    "despawnNpc 若退化成「清空世界里的村民」，会把村民系统的样板村民也一起删掉");
        }

        @Test
        @DisplayName("反向：未生成时调用、以及连续调用两次都不抛（幂等）")
        void isIdempotent() {
            npc.despawnNpc();          // 从未生成
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            npc.despawnNpc();
            npc.despawnNpc();          // 已清空

            assertEquals(0, villagers(world).size());
            assertNull(npc.getNpcLocation());
        }
    }

    @Nested
    @DisplayName("despawnIfInWorld —— 按世界归属清除")
    class DespawnIfInWorld {

        @Test
        @DisplayName("正向：世界名匹配（忽略大小写）时清掉实体并返回 true")
        void clearsWhenWorldMatchesIgnoringCase() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));

            // P2-29：arenas.yml 的原始字符串与实际世界名大小写可能不一致
            assertTrue(npc.despawnIfInWorld("NPC-WORLD"),
                    "大小写不一致时 equals 会漏判 —— 删了 YAML 却留下实体，正是 P2-29 的失败形态");
            assertEquals(0, villagers(world).size());
        }

        @Test
        @DisplayName("反向对照：世界名不匹配时实体原样保留、返回 false")
        void keepsEntityInAnotherWorld() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));

            assertFalse(npc.despawnIfInWorld("some-other-world"),
                    "清除竞技场 A 的商店绝不能误删竞技场 B 正在用的实体");
            assertEquals(1, villagers(world).size());
            assertNotNull(npc.getNpcLocation());
        }

        @Test
        @DisplayName("反向：null / 空白世界名与「未生成」状态下均返回 false 且不抛")
        void rejectsBlankWorldNames() {
            assertFalse(npc.despawnIfInWorld(null), "null 世界名不得 NPE");
            assertFalse(npc.despawnIfInWorld("   "), "空白世界名不得命中任何世界");

            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            npc.despawnNpc();
            assertFalse(npc.despawnIfInWorld("npc-world"), "未生成实体时没有东西可清");
        }
    }

    // ================================================================ isShopNpc

    @Nested
    @DisplayName("isShopNpc —— 引用与 PDC 双判据")
    class IsShopNpc {

        @Test
        @DisplayName("正向：当前引用命中")
        void recognisesCurrentReference() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));

            assertTrue(npc.isShopNpc(villagers(world).get(0)));
        }

        @Test
        @DisplayName("正向：只有 PDC 的孤儿也命中（崩溃残留靠这条被识别）")
        void recognisesPdcOnlyOrphan() {
            SafeVillagerMock orphan = orphan(world, 30.5, 30.5, true);

            assertTrue(npc.isShopNpc(orphan),
                    "崩溃后重连的实体已不是当前引用，只剩 PDC —— 少了这条判据，ShopListener 会拒绝为它开商店");
        }

        @Test
        @DisplayName("反向对照：普通村民与 null 一律 false")
        void rejectsUnrelatedEntities() {
            SafeVillagerMock plain = orphan(world, 40.5, 40.5, false);

            assertFalse(npc.isShopNpc(plain), "无标记的普通村民必须返回 false");
            assertFalse(npc.isShopNpc(null), "null 必须返回 false 而不是 NPE");
        }
    }

    // ================================================================ respawnFromConfig

    @Nested
    @DisplayName("respawnFromConfig —— 重启恢复")
    class RespawnFromConfig {

        @Test
        @DisplayName("正向：有配置时按竞技场 ID 排序取第一个生成（并警告多场配置只用一个）")
        void usesTheLowestArenaId() {
            Location bravo = new Location(world, 5.5, 64, 5.5);
            Location alpha = new Location(world, 7.5, 64, 7.5);
            // 注册顺序故意反过来：结果必须由 ID 排序决定，而不是插入顺序
            defineArena("bravo", "npc-world", bravo);
            defineArena("alpha", "npc-world", alpha);
            var logs = attachLogCapture(host.getLogger());

            npc.respawnFromConfig();

            assertNotNull(npc.getNpcLocation(), "有 shop-npc 配置时必须生成实体");
            assertEquals(7.5, npc.getNpcLocation().getX(), 1.0e-6,
                    "必须选 ID 最小的 arena（alpha），否则每次重启选到的商店位置可能不同");
            assertTrue(logs.anyContains("2 arena(s) have a shop NPC location"),
                    "多场都配了位置却只生成一个，必须在日志里说明 —— 否则服主会以为配置没生效："
                            + logs.lines());
        }

        @Test
        @DisplayName("反向对照：没有任何竞技场配置 shop-npc 时不生成任何实体")
        void doesNothingWithoutConfiguration() {
            defineArena("alpha", "npc-world", null);
            defineArena("bravo", "npc-world", null);

            npc.respawnFromConfig();

            assertNull(npc.getNpcLocation(), "无配置时不得生成（否则会在默认坐标凭空冒出一只商店）");
            assertEquals(0, villagers(world).size());
        }
    }

    // ================================================================ cleanupOrphans

    @Nested
    @DisplayName("cleanupOrphans —— 崩溃残留清理")
    class CleanupOrphans {

        @Test
        @DisplayName("正向：删掉孤儿（只有 PDC），但保留当前 NPC")
        void removesOrphansAndKeepsCurrentNpc() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            Villager current = villagers(world).get(0);
            SafeVillagerMock orphan = orphan(world, 20.5, 20.5, true);
            assertEquals(2, villagers(world).size(), "前提：清理前应当有 1 只 NPC + 1 只孤儿");

            npc.cleanupOrphans();

            assertFalse(world.getEntities().contains(orphan), "有 PDC 且非当前引用的实体必须被清掉");
            assertTrue(world.getEntities().contains(current),
                    "当前 NPC 同样带 PDC —— 少了「跳过 entity == npc」，恢复出来的商店会被自己人清掉");
            assertEquals(1, villagers(world).size());
        }

        @Test
        @DisplayName("反向对照：没有 PDC 的同类实体不受牵连")
        void keepsUnmarkedEntities() {
            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            SafeVillagerMock plain = orphan(world, 20.5, 20.5, false);

            npc.cleanupOrphans();

            assertTrue(world.getEntities().contains(plain),
                    "清理判据必须是 PDC，不是「世界里所有村民」");
            assertEquals(2, villagers(world).size(), "NPC + 普通村民都应保留");
        }

        @Test
        @DisplayName("正向：跨世界清理（遍历 Bukkit.getWorlds()，不止当前世界）")
        void sweepsAcrossWorlds() {
            WorldMock second = spawnableWorld("npc-world-second");
            SafeVillagerMock orphanHere = orphan(world, 20.5, 20.5, true);
            SafeVillagerMock orphanThere = orphan(second, 5.5, 5.5, true);

            npc.cleanupOrphans();

            assertFalse(world.getEntities().contains(orphanHere), "本世界孤儿应被清掉");
            assertFalse(second.getEntities().contains(orphanThere),
                    "只扫当前世界会漏掉重启后被强制加载的另一半孤儿");
        }
    }

    // ================================================================ getNpcLocation

    @Nested
    @DisplayName("getNpcLocation —— 位置查询")
    class GetNpcLocation {

        @Test
        @DisplayName("正向：生成后返回实体当前位置")
        void returnsSpawnedLocation() {
            npc.spawnNpc(new Location(world, 6.5, 70, 6.5));

            Location location = npc.getNpcLocation();
            assertNotNull(location);
            assertEquals(6.5, location.getX(), 1.0e-6);
            assertEquals(70.0, location.getY(), 1.0e-6);
            assertSame(world, location.getWorld(), "世界必须是生成时那个");
        }

        @Test
        @DisplayName("反向对照：未生成 / 清除后返回 null（调用方据此判断「有没有商店」）")
        void returnsNullWhenAbsent() {
            assertNull(npc.getNpcLocation(), "从未生成时必须为 null");

            npc.spawnNpc(new Location(world, 1.5, 64, 1.5));
            npc.despawnNpc();

            assertNull(npc.getNpcLocation(), "清除后必须回到 null，否则跨世界保护会拿旧坐标做判定");
        }
    }

    // ================================================================ 单例语义

    @Test
    @DisplayName("getNpcManager 返回同一实例，且 reload 不重建（实体引用必须保持稳定）")
    void npcManagerIsStableAcrossReload() {
        NpcManager before = shop.getNpcManager();
        npc.spawnNpc(new Location(world, 1.5, 64, 1.5));

        shop.reload();

        assertSame(before, shop.getNpcManager(),
                "reload 重建 NpcManager 会丢掉实体引用 —— 老 NPC 从此既 despawn 不掉也识别不出");
        assertNotNull(shop.getNpcManager().getNpcLocation(), "reload 不应无条件 despawn 现有商店");
    }
}
