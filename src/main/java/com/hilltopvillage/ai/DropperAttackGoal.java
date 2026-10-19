package com.hilltopvillage.ai;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.hilltopvillage.core.GameManager;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * 飞行抛投者的骚扰俯冲 AI（fix4 反馈2：「其不会向着玩家攻击」）。
 *
 * <p>背景：{@code WaveManager#spawnFlyingDropper} 创建抛投者时调用
 * {@code removeAllGoals}，把幻翼原版的全部 goal（含 {@code PHANTOM_ATTACK_PLAYER}
 * 俯冲攻击）一并清掉，只挂上 {@link AirdropSpawnGoal} —— 于是它只会空投、从不攻击玩家，
 * 且没有落点时长时间滞空（实测日志：白天日光下焚烧致死）。</p>
 *
 * <p>本 Goal 补回「向着玩家攻击」：空投间隙锁定最近的本场玩家，向其头顶上方俯冲，
 * 贴近后造成一次轻攻击（伤害 = {@code Attribute.ATTACK_DAMAGE}，即
 * {@code monsters-config.yml} 的 {@code flying-dropper.base-damage}，管理员可直接调小），
 * 然后拉起进入较长冷却，形成「空投载具 + 骚扰者」的威胁模型。</p>
 *
 * <p>优先级 2，低于 {@link AirdropSpawnGoal} 的 1：有空投任务时空投优先（高优先级可抢占），
 * 冷却间隙才俯冲骚扰，不打断空投状态机。</p>
 */
public class DropperAttackGoal implements Goal<Mob> {

    /** 俯冲攻击命中距离（格，平方） */
    private static final double ATTACK_RANGE_SQ = 9.0;
    /** 俯冲速度（每 tick 位移倍率） */
    private static final double DIVE_SPEED = 0.8;
    /** 俯冲瞄准点：目标玩家头顶上方（防止贴脸穿模） */
    private static final double HOVER_ABOVE = 2.0;
    /** 锁定目标的搜索半径（格，平方）：别从半张地图外横穿过来耽误空投 */
    private static final double HUNT_RANGE_SQ = 900.0;
    /** 一次俯冲后的冷却（tick）：50 tick = 2.5 秒，压低骚扰 DPS */
    private static final int POST_ATTACK_COOLDOWN_TICKS = 50;
    /** 找不到目标时的扫描退避（tick），避免每 tick 重扫玩家集合 */
    private static final int SCAN_RETRY_INTERVAL_TICKS = 20;

    /**
     * Goal 的身份键。
     *
     * <p>{@code GoalKey.of(...)} 每次调用都新建对象（与同包其它 Goal 的核实结论一致），
     * GoalKey 不可变并已实现 equals/hashCode，故提为常量复用完全安全。</p>
     */
    private static final GoalKey<Mob> KEY =
            GoalKey.of(Mob.class, new NamespacedKey("hilltopvillage", "dropper_attack"));

    /** GoalType 集合：与 {@link AirdropSpawnGoal} 相同的 MOVE + LOOK，靠优先级协调。 */
    private static final EnumSet<GoalType> TYPES = EnumSet.of(GoalType.MOVE, GoalType.LOOK);

    private final Mob dropper;
    private final GameManager gameManager;
    /** 本次俯冲锁定的目标（shouldStayActive 依据）；null = 无目标 */
    private Player target;
    private int cooldownTicks;
    private int scanCooldown;

    public DropperAttackGoal(Mob dropper, GameManager gameManager) {
        this.dropper = dropper;
        this.gameManager = gameManager;
        this.cooldownTicks = 0;
        this.scanCooldown = 0;
    }

    @Override
    public boolean shouldActivate() {
        if (!gameManager.isGameRunning()) return false;
        // 冷却递减沿用与 AirdropSpawnGoal 相同的模式：未激活时也在本方法里倒数
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return false;
        }
        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }
        // 已有仍有效的目标就直接返回 true：被高优先级空投 Goal 抢占期间（本 Goal 已选定
        // 但尚未 start）避免每 tick 重扫玩家集合，等空投结束立即开俯冲
        if (target != null && isValidArenaTarget(target)) return true;
        target = findNearestPlayer();
        if (target == null) {
            scanCooldown = SCAN_RETRY_INTERVAL_TICKS;
            return false;
        }
        return true;
    }

    @Override
    public boolean shouldStayActive() {
        return isValidArenaTarget(target)
                && gameManager.isGameRunning()
                && dropper.isValid();
    }

    @Override
    public void start() {
        cooldownTicks = 0;
    }

    @Override
    public void stop() {
        // 无论本次俯冲是否命中都在此进入冷却：下一次骚扰至少要等 POST_ATTACK_COOLDOWN_TICKS
        cooldownTicks = POST_ATTACK_COOLDOWN_TICKS;
        target = null;
    }

    @Override
    public void tick() {
        if (!isValidArenaTarget(target)) return;

        Location dropperLoc = dropper.getLocation();
        Location aim = target.getLocation().add(0, HOVER_ABOVE, 0);
        org.bukkit.util.Vector dir = aim.toVector().subtract(dropperLoc.toVector());

        if (dir.lengthSquared() <= ATTACK_RANGE_SQ) {
            performAttack();
            target = null; // 本次俯冲完成，下一 tick shouldStayActive 判 false → stop() 进冷却
            return;
        }
        if (dir.lengthSquared() > 0.01) {
            dropper.setVelocity(dir.normalize().multiply(DIVE_SPEED));
        }
        // 面向目标（与 AirdropSpawnGoal 的纯速度驱动一致，不走寻路）
        Location face = target.getLocation();
        org.bukkit.util.Vector look = face.toVector().subtract(dropperLoc.toVector());
        if (look.lengthSquared() > 0.01) {
            dropperLoc.setDirection(look.normalize());
            dropper.setRotation(dropperLoc.getYaw(), dropperLoc.getPitch());
        }
    }

    @Override
    public GoalKey<Mob> getKey() {
        return KEY;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        // 返回副本：与同包其它 Goal 相同的防御性约定（EnumSet 可变）
        return TYPES.clone();
    }

    /** 一次俯冲攻击：轻伤害 + 音效粒子反馈。伤害取 ATTACK_DAMAGE 属性（= base-damage）。 */
    private void performAttack() {
        if (!isValidArenaTarget(target)) return;
        Player victim = target;

        dropper.getWorld().playSound(dropper.getLocation(), Sound.ENTITY_PHANTOM_BITE, 1.0f, 0.8f);
        dropper.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1.0, 0),
                8, 0.3, 0.3, 0.3, 0.05);

        // fix5 M-07：原版近战击退（hurt 内 knockback）方向 = 远离怪物、强度固定 0.5，
        // 俯冲时怪物水平投影可能位于玩家与岛心之间 → 击退恰把玩家推离天空岛坠入虚空。
        // 语义收敛为「只咬不推」：记录伤害前的水平速度，伤害后退回它（保留垂直分量——
        // 原版上抛 0.4 的受击反馈仍在），彻底消除不可控的水平击退位移。
        // 注：damage() 调用同步返回时击退已应用完毕，此处覆写即刻生效。
        org.bukkit.util.Vector before = victim.getVelocity();
        victim.damage(resolveAttackDamage(), dropper);
        org.bukkit.util.Vector after = victim.getVelocity();
        victim.setVelocity(new org.bukkit.util.Vector(before.getX(), after.getY(), before.getZ()));
    }

    /** 读取怪物的攻击伤害属性（与 NodeAttackGoal 同范式：即其对玩家的伤害）。 */
    private double resolveAttackDamage() {
        AttributeInstance attribute = dropper.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attribute != null && attribute.getValue() > 0) {
            return attribute.getValue();
        }
        // 属性不可用时回退配置 base-damage（WaveManager.applyMonsterEquipment 正常会写入属性）
        var cfg = gameManager.getConfigManager().getActiveConfig().getMonsters().get("flying-dropper");
        if (cfg != null && cfg.getBaseDamage() > 0) return cfg.getBaseDamage();
        return 4.0; // 原版幻翼默认攻击伤害附近兜底
    }

    /** 本竞技场世界内离抛投者最近、且可被攻击的玩家；无合格目标时返回 null。 */
    private Player findNearestPlayer() {
        World gameWorld = gameManager.getGameWorld();
        if (gameWorld == null) return null;
        Location dropperLoc = dropper.getLocation();
        // 抛投者不在竞技场世界（被外部插件传送等）时不锁定目标：跨世界 distanceSquared
        // 会抛 IllegalArgumentException，直接放弃本轮
        if (dropperLoc.getWorld() != gameWorld) return null;

        Player nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        Set<UUID> arenaPlayers = gameManager.getPlayers();
        for (UUID uuid : arenaPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline() || player.isDead()) continue;
            if (player.getWorld() != gameWorld) continue;
            if (player.getGameMode() == GameMode.SPECTATOR
                    || player.getGameMode() == GameMode.CREATIVE) continue;

            double distSq = player.getLocation().distanceSquared(dropperLoc);
            if (distSq <= HUNT_RANGE_SQ && distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }

    /** 目标仍然是本场对局中可被攻击的玩家（与 HookClawHunterGoal 的判据同口径）。 */
    private boolean isValidArenaTarget(Player player) {
        if (player == null || !player.isOnline() || player.isDead()) return false;
        if (!gameManager.isGameRunning()) return false;
        World gameWorld = gameManager.getGameWorld();
        return gameWorld != null && player.getWorld() == gameWorld
                && gameManager.getPlayers().contains(player.getUniqueId())
                && player.getGameMode() != GameMode.SPECTATOR
                && player.getGameMode() != GameMode.CREATIVE;
    }
}
