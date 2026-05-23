package com.hilltopvillage.ai;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.mechanics.NodeSystem;
import org.bukkit.*;
import org.bukkit.entity.Mob;

import java.util.EnumSet;

public class ExplodeBeetleGoal implements Goal<Mob> {

    private final Mob beetle;
    private final GameManager gameManager;
    private NodeSystem.NodeData targetNode;
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

        targetNode = gameManager.getNodeSystem().getNearestNode(beetle.getLocation());
        return targetNode != null;
    }

    @Override
    public boolean shouldStayActive() {
        return targetNode != null && targetNode.isActive()
                && gameManager.isGameRunning() && beetle.isValid();
    }

    @Override
    public void start() {
        stuckTicks = 0;
        lastPosition = beetle.getLocation().clone();
        beetle.setGlowing(true);
    }

    @Override
    public void stop() {
        beetle.setGlowing(false);
        targetNode = null;
    }

    @Override
    public void tick() {
        if (targetNode == null) return;

        Location nodeLoc = targetNode.getLocation().clone().add(0.5, 1, 0.5);
        Location beetleLoc = beetle.getLocation();
        double distance = beetleLoc.distance(nodeLoc);

        if (distance <= 2.5) {
            explode();
            return;
        }

        org.bukkit.util.Vector direction = nodeLoc.toVector().subtract(beetleLoc.toVector()).normalize();
        Location newLoc = beetleLoc.clone().add(direction.multiply(0.5));
        newLoc.setDirection(direction);
        beetle.teleport(newLoc);

        if (beetleLoc.distanceSquared(lastPosition) < 0.01) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPosition = beetleLoc.clone();

        if (stuckTicks > 60) {
            beetle.teleport(beetleLoc.add(
                    beetleLoc.getDirection().multiply(1.5).setY(0.5)));
            stuckTicks = 0;
        }
    }

    @Override
    public GoalKey<Mob> getKey() {
        return GoalKey.of(Mob.class, new NamespacedKey("hilltopvillage", "explode_beetle"));
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE, GoalType.LOOK);
    }

    private void explode() {
        if (targetNode == null) return;

        Location nodeLoc = targetNode.getLocation();
        World world = nodeLoc.getWorld();

        world.createExplosion(nodeLoc,
                (float) gameManager.getPlugin().getConfig()
                        .getDouble("monsters.explode-beetle.explosion-power", 4.0),
                false, false, beetle);

        double nodeDamage = gameManager.getPlugin().getConfig()
                .getDouble("monsters.explode-beetle.explosion-damage-to-nodes", 40.0);
        double multiplier = gameManager.getPlugin().getConfig()
                .getDouble("nodes.self-destruct-damage-multiplier", 3.0);

        gameManager.getNodeSystem().damageNode(nodeLoc, nodeDamage * multiplier);
        gameManager.getDisplayEntityManager().spawnNodeDestroyEffect(nodeLoc);

        beetle.remove();
        gameManager.getWaveManager().onMobDeath(beetle);
    }
}
