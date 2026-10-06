package com.hilltopvillage.admin;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按玩家隔离的通用坐标粒子预览（生成点 / 能量节点 / 待保护村民共用）。
 *
 * <p>为什么不复用 {@link AdminDeployManager#showNodeParticles}：那边文案硬编码中文、
 * {@code showParticlesTaskId} 是单实例字段（两个玩家同时预览会互相打断），
 * 而且「未实例化的竞技场」根本拿不到对应的 {@code AdminDeployManager}。</p>
 *
 * <p>本类按 {@link UUID} 隔离任务，不同玩家互不打断；文案全部走 {@link LanguageManager}。</p>
 *
 * <p><b>线程约束：</b>本类所有方法均必须在主线程调用（Bukkit 事件线程），
 * 内部使用 {@code runTaskTimer} 而非异步任务，粒子生成也在主线程完成。
 * 任务表使用 {@link ConcurrentHashMap} 只是为了避免多线程误用时的可见性问题，
 * 并不代表本类支持并发调用。</p>
 */
public final class LocationParticlePreview {

    /** 预览任务轮询间隔（tick），与 AdminDeployManager 的粒子刷新频率保持一致 */
    private static final long PERIOD_TICKS = 4L;
    /** 距离剔除半径（格）：超过该距离的点不绘制粒子 */
    private static final double VIEW_DISTANCE = 64.0;

    private final HilltopVillagePlugin plugin;

    /** viewer UUID -> 当前预览任务（按玩家隔离，互不打断） */
    private final Map<UUID, BukkitTask> previewTasks = new ConcurrentHashMap<>();

    public LocationParticlePreview(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 为 viewer 预览一组坐标；会先取消该 viewer 上一次的预览（按 UUID 隔离，互不打断）。
     *
     * @param viewer        观看者
     * @param locations     待预览坐标（世界未加载的点会被跳过，不抛异常）
     * @param durationTicks 持续时间（tick），到点自动结束
     */
    public void show(Player viewer, List<Location> locations, long durationTicks) {
        if (viewer == null) return;

        // 先停掉该玩家上一次的预览
        stop(viewer);

        LanguageManager lang = plugin.getLanguageManager();
        UUID viewerId = viewer.getUniqueId();

        if (locations == null || locations.isEmpty()) {
            if (lang != null) viewer.sendMessage(lang.getFor(viewer, "manage.entry.particles-empty"));
            return;
        }

        // 快照一份，避免预览过程中源列表被其它逻辑改动
        final List<Location> targets = new ArrayList<>(locations);

        if (lang != null) {
            viewer.sendMessage(lang.getFor(viewer, "manage.preview.header",
                    targets.size(), durationTicks / 20));
        }

        final long totalTicks = durationTicks;
        BukkitRunnable runnable = new BukkitRunnable() {
            private int ticksElapsed = 0;
            private double angle = 0;

            @Override
            public void run() {
                if (ticksElapsed >= totalTicks || !viewer.isOnline()) {
                    cancel();
                    removeIfCurrent(viewerId, getTaskId());
                    return;
                }

                angle += Math.PI / 16;  // 慢速旋转
                if (angle >= 2 * Math.PI) angle = 0;

                for (Location loc : targets) {
                    World world = loc.getWorld();
                    if (world == null) continue;  // 世界未加载：跳过该点，不抛异常
                    if (viewer.getWorld() != world) continue;
                    // 距离剔除：viewer 超过 64 格则跳过该点
                    if (viewer.getLocation().distanceSquared(loc) > VIEW_DISTANCE * VIEW_DISTANCE) continue;

                    // === 方案C：符文 + 垂直光柱（与 AdminDeployManager 视觉一致）===

                    // 1. 垂直光柱（END_ROD 白色粒子，从地面到 y+20）
                    double beamHeight = 20.0;
                    double beamStep = 0.4;  // 每0.4格一个粒子
                    for (double y = 0; y <= beamHeight; y += beamStep) {
                        world.spawnParticle(Particle.END_ROD,
                            loc.getX() + 0.5, loc.getY() + y, loc.getZ() + 0.5,
                            1, 0, 0, 0, 0);
                    }

                    // 2. 周围螺旋符文装饰（ENCHANTED_HIT 紫色附魔粒子）
                    double spiralRadius = 1.2;
                    double spiralHeight = 3.0;
                    int spiralPoints = 3;  // 每帧3个粒子点
                    for (int i = 0; i < spiralPoints; i++) {
                        double spiralAngle = angle + (i * 2 * Math.PI / spiralPoints);
                        double spiralY = (ticksElapsed % 60) * (spiralHeight / 60.0);  // 3秒循环一次
                        double spiralX = loc.getX() + 0.5 + Math.cos(spiralAngle) * spiralRadius;
                        double spiralZ = loc.getZ() + 0.5 + Math.sin(spiralAngle) * spiralRadius;
                        world.spawnParticle(Particle.ENCHANTED_HIT,
                            spiralX, loc.getY() + spiralY, spiralZ,
                            1, 0, 0, 0, 0);
                    }
                }

                ticksElapsed++;
            }
        };

        BukkitTask task = runnable.runTaskTimer(plugin, 0L, PERIOD_TICKS);
        previewTasks.put(viewerId, task);
    }

    /** 取消某玩家的预览（无预览时静默返回）。 */
    public void stop(Player viewer) {
        if (viewer == null) return;
        BukkitTask task = previewTasks.remove(viewer.getUniqueId());
        if (task != null) task.cancel();
    }

    /** 取消全部（供 onDisable 调用）。 */
    public void stopAll() {
        for (BukkitTask task : previewTasks.values()) {
            if (task != null) task.cancel();
        }
        previewTasks.clear();
    }

    /** 仅当 map 中记录的仍是当前任务时才移除，避免误删之后新启动的预览。 */
    private void removeIfCurrent(UUID viewerId, int taskId) {
        BukkitTask current = previewTasks.get(viewerId);
        if (current != null && current.getTaskId() == taskId) {
            previewTasks.remove(viewerId);
        }
    }
}
