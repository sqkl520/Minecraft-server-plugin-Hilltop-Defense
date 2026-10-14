package com.hilltopvillage.ai;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.mechanics.NodeSystem;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Mob;

import java.util.EnumSet;

/**
 * 普通怪物攻击能量节点的 AI。
 *
 * <p>设计对应「节点才是真正的血条」：怪物在没有玩家可攻击时，会向最近的活跃节点推进并攻击它。</p>
 *
 * <p><b>伤害值 = 怪物对玩家的攻击伤害</b>（{@code Attribute.ATTACK_DAMAGE}），
 * 因此管理员在怪物配置里调整的 base-damage 会同时作用于玩家和节点。</p>
 *
 * <p>本 Goal 以较低优先级注册，原版近战 AI（攻击玩家）优先级更高，
 * 即：<b>有玩家在附近时优先打玩家，否则推进打节点</b>。</p>
 */
public class NodeAttackGoal implements Goal<Mob> {

    /** 攻击距离（格） */
    private static final double ATTACK_RANGE = 3.0;
    /** 攻击间隔（tick），20 tick = 1 秒 */
    private static final int ATTACK_COOLDOWN_TICKS = 20;
    /** 寻路速度 */
    private static final double PATHFINDER_SPEED = 1.0;
    /** 重新搜索最近节点的间隔（tick）：避免每 tick 全量扫描节点表 */
    private static final int RETARGET_INTERVAL_TICKS = 30;
    /** 卡住判定阈值（位移平方） */
    private static final double STUCK_THRESHOLD_SQ = 0.01;
    /** 卡住超时（tick）后重新寻路 */
    private static final int STUCK_TICK_LIMIT = 60;
    /** 卡死放弃上限：连续卡死这么多次后放弃节点改选次近节点，次近也不可达则自毁出列（P0-C） */
    private static final int MAX_STUCK_GIVEUPS = 3;

    /**
     * Goal 的身份键。
     *
     * <p>{@code GoalKey.of(...)} 每次调用都新建对象（已反编译 paper-api 1.21.4 的
     * {@code GoalKey.of} 核实：字节码只有 {@code new GoalKey} + 构造，无任何缓存），
     * 且还会顺带 {@code new NamespacedKey(...)}（其构造含键名合法性校验）。
     * GoalKey 不可变并已实现 equals/hashCode，故提为常量复用完全安全。</p>
     */
    private static final GoalKey<Mob> KEY =
            GoalKey.of(Mob.class, new NamespacedKey("hilltopvillage", "node_attack"));

    /** GoalType 集合：{@code EnumSet.of} 每次都新建，同样提为常量。 */
    private static final EnumSet<GoalType> TYPES = EnumSet.of(GoalType.MOVE, GoalType.LOOK);

    private final Mob mob;
    private final GameManager gameManager;
    private NodeSystem.NodeData targetNode;
    /** 目标节点方块中心（节点位置固定不变，随目标一起缓存，避免每 tick 重建 Location） */
    private Location targetCenter;
    /** 下次允许重新扫描节点的实体存活 tick；使用绝对 tick 避免激活切换导致少算或多算 */
    private long nextRetargetTick;
    /** 搜索半径平方；配置值小于等于 0 时为 0，表示禁用节点寻路 */
    private double searchRadiusSq;
    private int attackCooldown;
    private int stuckTicks;
    private Location lastPosition;
    /** 卡死放弃次数；达到 {@link #MAX_STUCK_GIVEUPS} 则自毁出列（P0-C） */
    private int stuckGiveUps;
    /** 最近一次因卡死而放弃的节点；retarget 时优先排除它，避免立即重新锁定同一卡点 */
    private NodeSystem.NodeData lastGivenUpNode;

    public NodeAttackGoal(Mob mob, GameManager gameManager) {
        this.mob = mob;
        this.gameManager = gameManager;
    }

    @Override
    public boolean shouldActivate() {
        if (!gameManager.isGameRunning()) return false;

        retargetIfNeeded();
        return targetNode != null;
    }

    /**
     * 在目标失效时立即重选，并在目标有效或暂无目标时每 30 tick 重新评估一次。
     *
     * <p>绝对实体 tick 同时覆盖未激活与已激活阶段，不依赖 shouldActivate 的调用频率；
     * 目标被摧毁、移除或 reload 替换时则绕过缓存立即放弃旧对象。</p>
     */
    private boolean retargetIfNeeded() {
        NodeSystem nodeSystem = gameManager.getNodeSystem();
        if (targetNode != null && !nodeSystem.isRegisteredActive(targetNode)) {
            clearTarget();
            nextRetargetTick = 0;
        }

        long currentTick = mob.getTicksLived();
        if (currentTick < nextRetargetTick) return false;

        NodeSystem.NodeData previousTarget = targetNode;
        // M-14 死配置接线（决策 D-1）：per-arena node-search-radius 优先、全局 nodes.search-radius 兜底
        double radius = gameManager.getNodeSearchRadius();
        searchRadiusSq = radius > 0 ? radius * radius : 0;

        NodeSystem.NodeData nearest = null;
        if (radius > 0) {
            // P0-C：若刚因卡死放弃过某节点，优先改选排除它的次近节点；次近不存在（只剩放弃过的那个）
            // 时回退到它，由卡死放弃计数最终兜底自毁。
            nearest = lastGivenUpNode != null
                    ? nodeSystem.getNearestNodeExcluding(mob.getLocation(), lastGivenUpNode)
                    : nodeSystem.getNearestNode(mob.getLocation());
            if (nearest == null && lastGivenUpNode != null) {
                nearest = nodeSystem.getNearestNode(mob.getLocation());
            }
            if (nearest != null && !isWithinSearchRadius(nearest)) {
                nearest = null;
            }
        }

        targetNode = nearest;
        targetCenter = nearest == null
                ? null
                : nearest.getLocation().add(0.5, 0.5, 0.5);
        nextRetargetTick = currentTick + RETARGET_INTERVAL_TICKS;
        return previousTarget != targetNode;
    }

    private boolean isWithinSearchRadius(NodeSystem.NodeData node) {
        Location nodeLocation = node.getLocation();
        Location mobLocation = mob.getLocation();
        return nodeLocation.getWorld() == mobLocation.getWorld()
                && nodeLocation.distanceSquared(mobLocation) <= searchRadiusSq;
    }

    @Override
    public boolean shouldStayActive() {
        if (!gameManager.isGameRunning() || !mob.isValid() || targetNode == null) return false;
        if (!gameManager.getNodeSystem().isRegisteredActive(targetNode)) {
            clearTarget();
            nextRetargetTick = 0;
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        attackCooldown = 0;
        stuckTicks = 0;
        stuckGiveUps = 0;
        lastGivenUpNode = null;
        lastPosition = mob.getLocation();
        pathToNode();
    }

    @Override
    public void stop() {
        mob.getPathfinder().stopPathfinding();
        clearTarget();
    }

    @Override
    public void tick() {
        boolean targetChanged = retargetIfNeeded();
        if (targetNode == null || targetCenter == null) return;
        if (!gameManager.getNodeSystem().isRegisteredActive(targetNode)) {
            clearTarget();
            nextRetargetTick = 0;
            return;
        }

        Location mobLoc = mob.getLocation();
        if (targetChanged) {
            stuckTicks = 0;
            lastPosition = mobLoc;
            pathToNode();
        }

        if (attackCooldown > 0) {
            attackCooldown--;
        }

        // 面向节点
        mob.lookAt(targetCenter);

        if (mobLoc.distance(targetCenter) <= ATTACK_RANGE) {
            mob.getPathfinder().stopPathfinding();
            // 成功够到节点：重置卡死放弃预算与排除标记，下次卡死重新计数（P0-C）
            stuckGiveUps = 0;
            lastGivenUpNode = null;
            attackNode();
            return;
        }

        // 卡住检测：长时间未位移则重新寻路
        if (mobLoc.distanceSquared(lastPosition) < STUCK_THRESHOLD_SQ) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPosition = mobLoc;

        if (stuckTicks >= STUCK_TICK_LIMIT) {
            stuckTicks = 0;
            // P0-C：原实现只是对同一节点无限重发 moveTo，节点不可达时怪物永久卡住、波次永不清。
            // 改为：累计放弃次数到上限直接自毁出列；否则记下卡点、立即改选次近节点再寻路。
            if (++stuckGiveUps >= MAX_STUCK_GIVEUPS) {
                gameManager.getWaveManager().discardMob(mob);
                return;
            }
            lastGivenUpNode = targetNode;
            nextRetargetTick = 0; // 解除 30-tick 节流，立即重选
            retargetIfNeeded();
            if (targetNode == null) {
                // 没有其它可达节点可选：出列，避免木桩占位卡波
                gameManager.getWaveManager().discardMob(mob);
                return;
            }
            lastPosition = mob.getLocation();
            pathToNode();
        }
    }

    private void pathToNode() {
        if (targetNode == null || !gameManager.getNodeSystem().isRegisteredActive(targetNode)) return;
        Location nodeCenter = targetNode.getLocation().add(0.5, 1, 0.5);
        mob.getPathfinder().moveTo(nodeCenter, PATHFINDER_SPEED);
    }

    /** 攻击节点：伤害 = 怪物对玩家的攻击伤害 */
    private void attackNode() {
        if (!gameManager.getNodeSystem().isRegisteredActive(targetNode)) {
            clearTarget();
            nextRetargetTick = 0;
            return;
        }
        if (attackCooldown > 0) return;
        attackCooldown = ATTACK_COOLDOWN_TICKS;

        double damage = resolveAttackDamage();
        Location nodeLoc = targetNode.getLocation();

        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.6f, 0.8f);
        mob.getWorld().spawnParticle(Particle.CRIT,
                nodeLoc.clone().add(0.5, 1.0, 0.5), 6, 0.3, 0.3, 0.3, 0.05);

        gameManager.getNodeSystem().damageNode(nodeLoc, damage);
    }

    private void clearTarget() {
        targetNode = null;
        targetCenter = null;
    }

    /** 读取怪物的攻击伤害属性（即其对玩家造成的伤害） */
    private double resolveAttackDamage() {
        AttributeInstance attribute = mob.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attribute != null && attribute.getValue() > 0) {
            return attribute.getValue();
        }
        return 3.0; // 原版僵尸默认攻击伤害兜底
    }

    @Override
    public GoalKey<Mob> getKey() {
        return KEY;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        // 返回副本：已反编译核实服务端只读取该集合（PaperMobGoals#getHandle 只做
        // contains(TARGET)，MobGoalHelper#paperToVanilla 只迭代），但本实例经
        // MobGoals#getGoal/getAllGoals 对外暴露，而 EnumSet 可变，故保留防御性副本。
        // clone() 只是一次位掩码拷贝，比原来的 EnumSet.of（noneOf + 两次 add）更便宜。
        return TYPES.clone();
    }
}
