package com.hilltopvillage.ai;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.mechanics.NodeSystem;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Mob;

import java.util.EnumSet;

public class ExplodeBeetleGoal implements Goal<Mob> {

    private static final double EXPLODE_DISTANCE   = 2.5;
    private static final double STUCK_THRESHOLD_SQ = 0.01;
    private static final int    STUCK_TICK_LIMIT   = 60;
    private static final double PATHFINDER_SPEED   = 1.5;
    /** 重新搜索最近节点的间隔（tick）：避免每 tick 全量扫描节点表 */
    private static final int    RETARGET_INTERVAL_TICKS = 30;

    /**
     * Goal 的身份键。
     *
     * <p>{@code GoalKey.of(...)} 每次调用都新建对象（已反编译 paper-api 1.21.4 的
     * {@code GoalKey.of} 核实：字节码只有 {@code new GoalKey} + 构造，无任何缓存），
     * 且还会顺带 {@code new NamespacedKey(...)}（其构造含键名合法性校验）。
     * GoalKey 不可变并已实现 equals/hashCode，故提为常量复用完全安全。</p>
     */
    private static final GoalKey<Mob> KEY =
            GoalKey.of(Mob.class, new NamespacedKey("hilltopvillage", "explode_beetle"));

    /** GoalType 集合：{@code EnumSet.of} 每次都新建，同样提为常量。 */
    private static final EnumSet<GoalType> TYPES = EnumSet.of(GoalType.MOVE, GoalType.LOOK);

    private final Mob beetle;
    private final GameManager gameManager;
    private NodeSystem.NodeData targetNode;
    /** 目标节点上方一格（寻路 / 引爆判定用，节点位置固定不变，随目标一起缓存） */
    private Location targetApproach;
    /** 下次允许重新扫描节点的实体存活 tick；使用绝对 tick 避免激活切换导致少算或多算 */
    private long nextRetargetTick;
    /** 搜索半径平方；配置值小于等于 0 时为 0，表示禁用节点寻路 */
    private double searchRadiusSq;
    private int stuckTicks;
    private Location lastPosition;

    public ExplodeBeetleGoal(Mob beetle, GameManager gameManager) {
        this.beetle = beetle;
        this.gameManager = gameManager;
        this.stuckTicks = 0;
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
     * <p>搜索半径沿用配置中心已有的 {@code nodes.search-radius}；值小于等于 0
     * 明确定义为禁用节点寻路，不扫描节点，也不会退回到隐藏的魔法默认值。</p>
     */
    private boolean retargetIfNeeded() {
        NodeSystem nodeSystem = gameManager.getNodeSystem();
        if (targetNode != null && !nodeSystem.isRegisteredActive(targetNode)) {
            clearTarget();
            nextRetargetTick = 0;
        }

        long currentTick = beetle.getTicksLived();
        if (currentTick < nextRetargetTick) return false;

        NodeSystem.NodeData previousTarget = targetNode;
        double radius = gameManager.getConfigManager().getActiveConfig().getNodeSearchRadius();
        searchRadiusSq = radius > 0 ? radius * radius : 0;

        NodeSystem.NodeData nearest = null;
        if (radius > 0) {
            nearest = nodeSystem.getNearestNode(beetle.getLocation());
            if (nearest != null && !isWithinSearchRadius(nearest)) {
                nearest = null;
            }
        }

        targetNode = nearest;
        targetApproach = nearest == null
                ? null
                : nearest.getLocation().add(0.5, 1, 0.5);
        nextRetargetTick = currentTick + RETARGET_INTERVAL_TICKS;
        return previousTarget != targetNode;
    }

    private boolean isWithinSearchRadius(NodeSystem.NodeData node) {
        Location nodeLocation = node.getLocation();
        Location beetleLocation = beetle.getLocation();
        return nodeLocation.getWorld() == beetleLocation.getWorld()
                && nodeLocation.distanceSquared(beetleLocation) <= searchRadiusSq;
    }

    @Override
    public boolean shouldStayActive() {
        if (!gameManager.isGameRunning() || !beetle.isValid() || targetNode == null) return false;
        if (!gameManager.getNodeSystem().isRegisteredActive(targetNode)) {
            clearTarget();
            nextRetargetTick = 0;
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        if (targetNode == null || targetApproach == null) return;

        stuckTicks = 0;
        lastPosition = beetle.getLocation();
        beetle.setGlowing(true);
        beetle.getPathfinder().moveTo(targetApproach, PATHFINDER_SPEED);
    }

    @Override
    public void stop() {
        beetle.getPathfinder().stopPathfinding();
        beetle.setGlowing(false);
        clearTarget();
    }

    @Override
    public void tick() {
        boolean targetChanged = retargetIfNeeded();
        if (targetNode == null || targetApproach == null) return;
        if (!gameManager.getNodeSystem().isRegisteredActive(targetNode)) {
            clearTarget();
            nextRetargetTick = 0;
            return;
        }

        Location beetleLoc = beetle.getLocation();
        if (targetChanged) {
            stuckTicks = 0;
            lastPosition = beetleLoc;
            beetle.getPathfinder().moveTo(targetApproach, PATHFINDER_SPEED);
        }

        double distance = beetleLoc.distance(targetApproach);
        if (distance <= EXPLODE_DISTANCE) {
            explode();
            return;
        }

        if (beetleLoc.distanceSquared(lastPosition) < STUCK_THRESHOLD_SQ) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPosition = beetleLoc;

        if (stuckTicks >= STUCK_TICK_LIMIT) {
            beetle.getPathfinder().moveTo(targetApproach, PATHFINDER_SPEED);
            stuckTicks = 0;
        }
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

    private void explode() {
        NodeSystem nodeSystem = gameManager.getNodeSystem();
        if (targetNode == null || !nodeSystem.isRegisteredActive(targetNode)) {
            clearTarget();
            nextRetargetTick = 0;
            return;
        }

        Location nodeLoc = targetNode.getLocation();
        World world = nodeLoc.getWorld();

        // 怪物爆炸伤害 = 对玩家的基础攻击伤害 × 怪物爆炸倍率；节点倍率只额外应用一次
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        GameConfig.MonsterConfig monsterCfg = cfg.getMonsters().get("explode-beetle");
        double explodeMultiplier = monsterCfg == null
                ? 0
                : Math.max(0, monsterCfg.getExplodeDamageMultiplier());
        double nodeMultiplier = Math.max(0, cfg.getNodeSelfDestructMultiplier());
        double nodeDamage = resolveBaseDamage(monsterCfg) * explodeMultiplier * nodeMultiplier;

        if (world == null) return;
        if (monsterCfg != null && !monsterCfg.isEntityExplosion()) {
            // STORY-1.6：纯视觉自爆 —— 只结算节点伤害（damageNode 见下），不再附带原版实体爆炸，
            // 节点旁的村民/玩家不受波及；粒子 + 音效保留爆炸观感。
            world.spawnParticle(Particle.EXPLOSION_EMITTER, nodeLoc, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.EXPLOSION, nodeLoc, 8, 0.5, 0.5, 0.5, 0.2);
            world.spawnParticle(Particle.LARGE_SMOKE, nodeLoc, 10, 0.6, 0.5, 0.6, 0.05);
            world.playSound(nodeLoc, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.0f);
        } else {
            world.createExplosion(nodeLoc, (float) explodeMultiplier, false, false, beetle);
        }
        nodeSystem.damageNode(nodeLoc, nodeDamage);
        gameManager.getDisplayEntityManager().spawnNodeDestroyEffect(nodeLoc);

        beetle.remove();
        gameManager.getWaveManager().onMobDeath(beetle);
    }

    /** ATTACK_DAMAGE 由 WaveManager 按 base-damage 写入；属性不可用时才回退配置值。 */
    private double resolveBaseDamage(GameConfig.MonsterConfig monsterCfg) {
        AttributeInstance attribute = beetle.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attribute != null && attribute.getValue() > 0) {
            return attribute.getValue();
        }
        return monsterCfg == null ? 0 : Math.max(0, monsterCfg.getBaseDamage());
    }

    private void clearTarget() {
        targetNode = null;
        targetApproach = null;
    }
}
