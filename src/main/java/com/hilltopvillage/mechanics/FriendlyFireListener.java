package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * 对局内玩家互伤门控（fix5 M-06：弓友好伤与「零友伤」承诺的矛盾）。
 *
 * <p>背景：插件对技能类伤害已全部实现零友伤（烈焰蛋 {@code FireballListener} 玩家分支只弹飞、
 * 重锤 {@code HammerListener} 玩家分支只弹飞），但**原版伤害路径**（弓 / 弩 / 三叉戟 /
 * 近战挥砍）直通 {@link EntityDamageByEntityEvent}，无任何门控——队友中箭可被致死，
 * 与 README「全技能零友伤」的承诺矛盾（STORY-1.8）。</p>
 *
 * <h2>拦截范围（刻意收窄）</h2>
 * <ul>
 *   <li>只拦「双方都是同一个活动竞技场实例的成员」的伤害——受击者所在世界能反查到
 *       活动实例，且双方 {@link ArenaInstance#isPlayer(Player)} 均命中；</li>
 *   <li>怪物伤害玩家、玩家伤害怪物、大厅内 / 非成员场景 → 全部放行；</li>
 *   <li>弹射物（箭 / 弩箭 / 三叉戟）按 shooter 还原攻击者后再判定；</li>
 *   <li>不做提示、不扣弹药：静默取消（与原版 PVP 关闭语义一致，README 已把「零友伤」
 *       作为宣传点，不存在需要教育玩家的场景）。</li>
 * </ul>
 *
 * <p>数据面：若未来要做「友伤开关」配置，应在此处加分支而不是放开承诺；
 * 当前版本按作者导向（README / STORY-1.8）硬编码零友伤。</p>
 */
public class FriendlyFireListener implements Listener {

    private final HilltopVillagePlugin plugin;

    public FriendlyFireListener(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player damager = resolveDamager(event.getDamager());
        if (damager == null || damager.getUniqueId().equals(victim.getUniqueId())) return;

        // 受击者必须在活动竞技场世界；大厅 / 普通世界直接放行
        ArenaInstance arena = plugin.getMultiArenaManager()
                .getArenaByWorld(victim.getWorld().getName());
        if (arena == null) return;

        // 只拦同场成员互伤：任一方不是本场成员（含旁观者、越权闯入者、管理员）
        // 都不属于「友伤」语义，交由其它机制处理（ArenaAccessListener / 管理通道）
        if (!arena.isPlayer(victim) || !arena.isPlayer(damager)) return;

        event.setCancelled(true);
    }

    /** 还原真实攻击者：直接玩家 / 玩家发射的弹射物（箭、弩箭、三叉戟、雪球等） */
    private Player resolveDamager(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
