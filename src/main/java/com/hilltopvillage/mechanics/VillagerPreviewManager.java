package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 村民预览实体管理器（部署向导第 5 步村民管理专用）。
 *
 * <p>管理员在向导第 5 步登记村民后，光有坐标数据看不出「这里有没有村民、是第几个」。
 * 本管理器在该方块坐标中心（方块坐标 +(0.5, 0, 0.5)）生成一只<b>纯装饰</b>的村民实体：
 * 无敌（{@link Entity#setInvulnerable(boolean)} + 取消一切 {@link EntityDamageEvent}）、
 * 不可碰撞、{@code setAI(false)}、静音，头顶显示序号（复用 {@code setup.villager-entry} 的
 * 「村民 #{0}」语义），让管理员一眼看清已登记的村民分布。</p>
 *
 * <h3>与开局真村民的隔离（最重要的正确性约束）</h3>
 *
 * <p>预览实体与 {@link VillagerSystem} 在开局时生成的待保护村民是<b>两套完全独立的生命周期</b>，
 * 绝不能互相混淆，否则开局后会凭空多出一批无敌村民、把对局搞坏。隔离手段有三重：</p>
 * <ol>
 *   <li><b>PDC 标记不同</b>：预览用 {@code hilltopvillage:villager-preview}（本类 {@link #PREVIEW_KEY}），
 *       真村民用 {@code hilltopvillage:village-villager}（{@link VillagerSystem#VILLAGE_VILLAGER_KEY}）。
 *       两边的孤儿清理（{@link VillagerSystem#cleanupOrphans()} / {@link #cleanupOrphans()}）各扫各的键，
 *       互不误删；而预览实体也天然<b>不会</b>被 {@link VillagerSystem} 认作真村民。</li>
 *   <li><b>生命周期收口</b>：预览只在「向导第 5 步」这一窗口内存在。离开第 5 步、完成部署、saveexit、
 *       玩家退出、向导会话结束都会清理（见 {@code SetupWizard} 的收口点）。</li>
 *   <li><b>开局兜底清场</b>：{@code ArenaInstance#onGameStart()} 在 {@code spawnAll()} 之前对本场世界
 *       调用 {@link #clearInWorld(World)}，即使前两道防线因崩溃/异常被绕过，开局也绝不会出现预览实体。</li>
 * </ol>
 *
 * <p>崩溃残留（未经正常收口就断电）由 {@link #cleanupOrphans()} 在服务器启动时按 PDC 标记清掉，
 * 模式与 {@link com.hilltopvillage.shop.NpcManager#cleanupOrphans()} 一致。</p>
 */
public class VillagerPreviewManager implements Listener {

    /**
     * 预览实体 PDC 标记（{@code hilltopvillage:villager-preview} 的键部分）。
     * 与真村民的 {@link VillagerSystem#VILLAGE_VILLAGER_KEY} 刻意区分。
     */
    public static final String PREVIEW_KEY = "villager-preview";

    private final HilltopVillagePlugin plugin;
    private final LanguageManager lang;
    private final NamespacedKey previewKey;
    /** 当前存活的预览实体引用（全插件共享；正常路径下同一世界只会有一批） */
    private final List<Villager> previews = new CopyOnWriteArrayList<>();

    public VillagerPreviewManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.previewKey = new NamespacedKey(plugin, PREVIEW_KEY);
    }

    /**
     * 把某世界内的预览实体同步为与给定位置列表<b>一一对应</b>（序号 = 下标 + 1）。
     *
     * <p>采用「整体重同步」而非增删单只：删除中间某个村民后，后续村民的序号会整体前移，
     * 重同步天然处理了这种重编号，也不会残留错位的孤儿预览实体。列表规模很小（几十只），
     * 且只在管理员手动增删/进入第 5 步时触发，性能可忽略。</p>
     *
     * @param world     目标竞技场世界；null 时只做清理、不生成
     * @param positions 已登记的村民位置（方块坐标）；null 等价于空
     * @param viewer    进行部署的玩家（用于取语言；null 时用默认语言）
     */
    public void syncPreviews(World world, List<Location> positions, Player viewer) {
        clearInWorld(world);
        if (world == null || positions == null) return;
        for (int i = 0; i < positions.size(); i++) {
            Location loc = positions.get(i);
            if (loc == null || loc.getWorld() == null) continue;
            // 只生成属于目标世界的位置，避免把别场竞技场的位置错生成到本世界
            if (!loc.getWorld().equals(world)) continue;
            spawnPreview(loc, lang.getFor(viewer, "setup.villager-entry", i + 1));
        }
    }

    /** 生成单只预览村民：方块坐标 +(0.5, 0, 0.5) 的方块中心，无敌 / 不可碰撞 / 无 AI / 静音 */
    private void spawnPreview(Location blockLoc, String name) {
        Villager villager = (Villager) blockLoc.getWorld()
                .spawnEntity(blockLoc.clone().add(0.5, 0, 0.5), EntityType.VILLAGER);
        villager.setInvulnerable(true);
        villager.setCollidable(false);
        villager.setAI(false);
        villager.setSilent(true);
        villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false);
        if (name != null && !name.isEmpty()) {
            villager.setCustomName(name);
            villager.setCustomNameVisible(true);
        }
        villager.getPersistentDataContainer().set(previewKey, PersistentDataType.BYTE, (byte) 1);
        previews.add(villager);
    }

    /** 清理某世界内的全部预览实体（幂等；world 为 null 时无操作） */
    public void clearInWorld(World world) {
        if (world == null) return;
        for (Villager villager : previews) {
            if (villager != null && villager.isValid() && world.equals(villager.getWorld())) {
                villager.remove();
            }
        }
        // 同步丢弃：null / 已失效 / 属于本世界（已移除）的引用一律剔除，避免列表无限增长。
        // fix5 X6-①：isValid=false 的区块卸载实体由 onEntitiesLoad 兜底在重载时清除，
        // 这里照旧只维护内存列表。
        previews.removeIf(villager -> villager == null || !villager.isValid()
                || world.equals(villager.getWorld()));
    }

    /** 清理全部预览实体（竞技场重置 / 插件禁用等收尾场景） */
    public void clearAll() {
        for (Villager villager : previews) {
            if (villager != null && villager.isValid()) {
                villager.remove();
            }
        }
        previews.clear();
    }

    /**
     * 清理世界中残留的预览实体（崩溃 / 重载后的孤儿实体）。
     *
     * <p>只认 PDC 标记、不依赖内存引用，模式与
     * {@link com.hilltopvillage.shop.NpcManager#cleanupOrphans()} 一致，由服务器启动阶段调用一次。</p>
     */
    public void cleanupOrphans() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Villager)) continue;
                if (entity.getPersistentDataContainer().has(previewKey, PersistentDataType.BYTE)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        previews.clear();
        if (removed > 0) {
            plugin.getLogger().info("Villager preview cleanup: removed " + removed + " leftover entity(ies)");
        }
    }

    /**
     * fix5 X6-①：区块实体加载兜底——带预览标记的实体在任何区块加载时一律清除。
     *
     * <p>背景（fix3 结转项）：预览实体是 persistent + 不远离消失的永久实体；区块卸载后
     * 原内存引用失效并从 {@link #previews} 被剔除，但实体本体留在存档里，区块重载产生的
     * 新对象不再被任何清理路径覆盖（{@link #clearInWorld} 只扫内存列表），运行期即脱管，
     * 可能残留进对局世界直到下次开服（{@link #cleanupOrphans} 仅启动时跑一次）。</p>
     *
     * <p>预览的生命周期只在「向导第 5 步」窗口内（设计三约束之一）：区块加载时出现的
     * 预览实体必然已跨出该窗口（正常路径下切步/退出/开局都会经内存列表清掉）。因此
     * 这里不判别会话状态、直接移除——代价是第 5 步期间管理员远离后返回可能发现预览消失，
     * 可经「重新进入第 5 步 / goto 5 / 聊天栏 [添加村民] / GUI 增删村民」触发
     * {@code syncPreviews} 重新生成（fix5 锐评 C-02：单开 villagersgui 或翻页只渲染列表、
     * 不重建预览），属可接受的降级。</p>
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Villager
                    && entity.getPersistentDataContainer().has(previewKey, PersistentDataType.BYTE)) {
                entity.remove();
            }
        }
    }

    /**
     * 预览实体绝对无敌：取消一切伤害来源（玩家攻击、怪物、环境伤害、虚空等）。
     *
     * <p>{@code setInvulnerable(true)} 已挡掉绝大多数伤害，但为满足「不会受伤」的硬约束再兜一层
     * 事件级取消：只要实体带预览标记，任何 {@link EntityDamageEvent} 一律取消。
     * 优先级 HIGHEST 保证本监听最后执行，其它监听即便放行了也无法让其掉血。</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreviewDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Villager)) return;
        if (event.getEntity().getPersistentDataContainer().has(previewKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }
}
