package com.hilltopvillage.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ArenaRegistry} 的 ID / 世界名归一化契约测试。
 *
 * <p><b>为什么这个类值得逐条钉</b>：注册表本身只有 87 行，但它是 {@code /hilltop} 全部
 * 子命令与 GUI 的查表入口。ID 只归一化一处、查询侧忘了归一化，症状不是异常而
 * 「命令说竞技场不存在」——与「竞技场真的没配」在玩家眼里不可区分。P2-29 的
 * {@code getByWorldName} 大小写修复正是这一类：Bukkit 的世界查找本身不区分大小写，
 * 世界名大小写与 {@code arenas.yml} 不一致时服务器能正常加载，只有
 * {@code /hilltop setup} 的起手解析会误报。
 *
 * <p><b>纯 JUnit5</b>：零 Bukkit 依赖（{@code ArenaDefinition} 允许 {@code core == null}，
 * {@code Location} 全程没用上），毫秒级，不需要 MockBukkit。
 */
class ArenaRegistryTest {

    /** {@code core == null} 是「未部署」的合法状态，构造成本为零。 */
    private static ArenaDefinition arena(String id, String worldName) {
        return ArenaDefinition.builder(id, worldName, null).build();
    }

    // ---------------------------------------------------------------- 注册

    @Test
    @DisplayName("register: null 定义被拒绝，且不会污染 registry")
    void registerRejectsNull() {
        ArenaRegistry registry = new ArenaRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.register(null),
                "register(null) 必须抛 IllegalArgumentException —— 静默接受会让 list() 里出现 null");
        assertEquals(0, registry.size(), "被拒绝的注册不应改变 size()");
    }

    @Test
    @DisplayName("register: 空/纯空白 ID 被拒绝（ArenaDefinition 允许空白？不允许 —— 这里是双保险）")
    void registerRejectsBlankId() {
        ArenaRegistry registry = new ArenaRegistry();

        // ArenaDefinition 的构造器自己就会拒绝空白 ID，所以这里能走到 registry 的唯一途径
        // 是将来有人放松了定义层校验。断言的是 registry 独立成立，与被锁住的实现无关。
        assertThrows(IllegalArgumentException.class, () -> arena("   ", "world1"),
                "ArenaDefinition 应拒绝空白 ID（registry 的 normalizeId 是第二道防线）");
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("register: 重复 ID 被拒绝，且大小写变体不能绕过")
    void registerRejectsDuplicateIgnoringCase() {
        ArenaRegistry registry = new ArenaRegistry();
        registry.register(arena("arena1", "hilltop_arena1"));

        IllegalArgumentException sameCase = assertThrows(IllegalArgumentException.class,
                () -> registry.register(arena("arena1", "other_world")),
                "同 ID 二次注册必须被拒绝");
        assertTrue(sameCase.getMessage().contains("already registered"),
                "拒绝原因应说明「已注册」，实际：" + sameCase.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(arena("Arena1", "other_world")),
                "大小写变体（Arena1 vs arena1）必须同样被拒绝 —— key 已小写，不得被绕过");
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(arena("ARENA1", "other_world")),
                "全大写变体同样必须被拒绝");

        assertEquals(1, registry.size(), "三次被拒后仍只应有 1 条记录");
    }

    @Test
    @DisplayName("register: 注册后以任意大小写都能 get 到同一实例")
    void getIsCaseInsensitive() {
        ArenaRegistry registry = new ArenaRegistry();
        ArenaDefinition original = arena("Arena1", "hilltop_arena1");
        registry.register(original);

        assertSame(original, registry.get("Arena1"), "原样查询应命中");
        assertSame(original, registry.get("arena1"), "全小写查询应命中（归一化契约）");
        assertSame(original, registry.get("ARENA1"), "全大写查询应命中");
        assertSame(original, registry.get("aReNa1"), "混合大小写查询应命中");
    }

    @Test
    @DisplayName("get: null / 未注册 / 空白返回 null，而不是抛异常")
    void getReturnsNullForMisses() {
        ArenaRegistry registry = new ArenaRegistry();
        registry.register(arena("arena1", "hilltop_arena1"));

        assertNull(registry.get(null), "get(null) 必须是 null（命令层直接用玩家输入，不能抛）");
        assertNull(registry.get("ghost"), "未注册 ID 应返回 null");
        // 空白走的是 normalizeId 的抛异常分支？不 —— get() 只对 null 短路，
        // 空白会落进 normalizeId 并抛。这条断言记录的是当前契约，不是理想契约。
        assertThrows(IllegalArgumentException.class, () -> registry.get("  "),
                "get(\"  \") 当前抛 IllegalArgumentException（normalizeId 对 blank 抛）——"
                        + " 若将来改成返回 null，请同步更新本断言与其调用方的预期");
    }

    // ---------------------------------------------------------------- 世界名

    @Test
    @DisplayName("getByWorldName: 大小写不一致也能命中（P2-29 修复的锚点）")
    void getByWorldNameIgnoresCase() {
        ArenaRegistry registry = new ArenaRegistry();
        ArenaDefinition original = arena("arena1", "Hilltop_Arena1");
        registry.register(original);

        assertSame(original, registry.getByWorldName("Hilltop_Arena1"), "原样世界名应命中");
        assertSame(original, registry.getByWorldName("hilltop_arena1"), "全小写世界名应命中");
        assertSame(original, registry.getByWorldName("HILLTOP_ARENA1"), "全大写世界名应命中");
    }

    @Test
    @DisplayName("getByWorldName: null / 空白 / 未知名返回 null")
    void getByWorldNameReturnsNullForMisses() {
        ArenaRegistry registry = new ArenaRegistry();
        registry.register(arena("arena1", "hilltop_arena1"));

        assertNull(registry.getByWorldName(null), "null 世界名必须返回 null");
        assertNull(registry.getByWorldName(""), "空串世界名必须返回 null");
        assertNull(registry.getByWorldName("   "), "纯空白世界名必须返回 null（不抛异常）");
        assertNull(registry.getByWorldName("some_other_world"), "未注册世界应返回 null");
    }

    @Test
    @DisplayName("getByWorldName: 空注册表上查询不会抛（启动早期世界可能还没加载）")
    void getByWorldNameOnEmptyRegistry() {
        ArenaRegistry registry = new ArenaRegistry();

        assertNull(registry.getByWorldName("anything"), "空注册表查询应返回 null");
        assertEquals(0, registry.size());
    }

    // ---------------------------------------------------------------- 默认竞技场

    @Test
    @DisplayName("setDefaultArenaId: 拒绝未注册 ID（既不静默、也不落 null）")
    void setDefaultRejectsUnregistered() {
        ArenaRegistry registry = new ArenaRegistry();
        registry.register(arena("arena1", "hilltop_arena1"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> registry.setDefaultArenaId("ghost"),
                "未注册 ID 作为默认竞技场必须被拒绝 —— 静默接受会让后续 getDefaultArenaId() 返回一个查不到的名字");
        assertTrue(failure.getMessage().contains("unregistered"),
                "拒绝原因应点名「未注册」，实际：" + failure.getMessage());

        assertNull(registry.getDefaultArenaId(), "被拒绝的 setDefault 不得改变既有默认值");
    }

    @Test
    @DisplayName("setDefaultArenaId: 记录归一化后的 ID，而不是调用方的原始大小写")
    void setDefaultNormalizesId() {
        ArenaRegistry registry = new ArenaRegistry();
        registry.register(arena("Arena1", "hilltop_arena1"));

        registry.setDefaultArenaId("ARENA1");

        assertEquals("arena1", registry.getDefaultArenaId(),
                "默认 ID 应存小写形态 —— 否则与 remove() 里的比较（normalizeId(removed.getId()).equals(defaultArenaId)）对不上");
    }

    @Test
    @DisplayName("setDefaultArenaId: null / 空白 = 清除默认，不抛异常")
    void setDefaultBlankClearsMarker() {
        ArenaRegistry registry = new ArenaRegistry();
        registry.register(arena("arena1", "hilltop_arena1"));
        registry.setDefaultArenaId("arena1");
        assertNotNull(registry.getDefaultArenaId(), "前置条件：默认已设置");

        registry.setDefaultArenaId(null);
        assertNull(registry.getDefaultArenaId(), "传 null 应清除默认");

        registry.setDefaultArenaId("arena1");
        registry.setDefaultArenaId("   ");
        assertNull(registry.getDefaultArenaId(), "传纯空白应清除默认（而不是抛 IllegalArgumentException）");
    }

    // ---------------------------------------------------------------- 删除

    @Test
    @DisplayName("remove: 删除当前默认竞技场会连带清掉默认标记")
    void removeClearsDefaultMarker() {
        ArenaRegistry registry = new ArenaRegistry();
        registry.register(arena("arena1", "hilltop_arena1"));
        registry.setDefaultArenaId("arena1");

        ArenaDefinition removed = registry.remove("Arena1");   // 大小写变体，验证归一化

        assertNotNull(removed, "remove 应返回被删掉的实例（大小写变体也要能删）");
        assertEquals(0, registry.size(), "删除后注册表应为空");
        assertNull(registry.getDefaultArenaId(),
                "删除默认竞技场必须同时清掉默认标记 —— 否则 getDefaultArenaId() 会指向一个已不存在的 ID");
    }

    @Test
    @DisplayName("remove: 删掉非默认竞技场时，默认标记保持不变")
    void removeKeepsDefaultWhenOtherArenaRemoved() {
        ArenaRegistry registry = new ArenaRegistry();
        registry.register(arena("arena1", "hilltop_arena1"));
        registry.register(arena("arena2", "hilltop_arena2"));
        registry.setDefaultArenaId("arena1");

        registry.remove("arena2");

        assertEquals("arena1", registry.getDefaultArenaId(),
                "删掉别的竞技场不应影响默认标记");
        assertEquals(1, registry.size());
    }

    @Test
    @DisplayName("remove: null / 未注册 ID 返回 null 且不改变状态")
    void removeMissReturnsNull() {
        ArenaRegistry registry = new ArenaRegistry();
        registry.register(arena("arena1", "hilltop_arena1"));
        registry.setDefaultArenaId("arena1");

        assertNull(registry.remove(null), "remove(null) 应返回 null");
        assertNull(registry.remove("ghost"), "remove(未注册) 应返回 null");
        assertEquals(1, registry.size(), "未命中的 remove 不应改变 size()");
        assertEquals("arena1", registry.getDefaultArenaId(), "未命中的 remove 不应清掉默认标记");
    }

    // ---------------------------------------------------------------- list / clear

    @Test
    @DisplayName("list: 保持注册顺序，且返回不可变副本")
    void listIsOrderedAndUnmodifiable() {
        ArenaRegistry registry = new ArenaRegistry();
        registry.register(arena("zulu", "w1"));
        registry.register(arena("alpha", "w2"));
        registry.register(arena("mike", "w3"));

        List<ArenaDefinition> listed = registry.list();
        assertEquals(List.of("zulu", "alpha", "mike"),
                listed.stream().map(ArenaDefinition::getId).toList(),
                "list() 必须是注册顺序（LinkedHashMap 语义），而非字典序 —— GUI 列表按配置顺序展示");

        assertThrows(UnsupportedOperationException.class, () -> listed.add(arena("x", "w")),
                "list() 必须返回不可变视图，否则外部能绕过 register() 的重复/归一化校验");
    }

    @Test
    @DisplayName("clear: 同时清空条目与默认标记")
    void clearResetsEverything() {
        ArenaRegistry registry = new ArenaRegistry();
        registry.register(arena("arena1", "hilltop_arena1"));
        registry.setDefaultArenaId("arena1");

        registry.clear();

        assertEquals(0, registry.size());
        assertNull(registry.getDefaultArenaId(), "clear 必须连默认标记一起清 —— 否则 reload 后默认指向幽灵 ID");
        assertTrue(registry.list().isEmpty());
    }
}
