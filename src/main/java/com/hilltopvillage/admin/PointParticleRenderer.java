package com.hilltopvillage.admin;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/**
 * 点位标记的粒子绘制（F-7 五类点位配色体系的统一渲染入口）。
 *
 * <p>垂直光柱 + 环绕螺旋两部分都使用 {@link Particle#DUST} 绘制，颜色与尺寸取自
 * {@link PointKind} 中预先构造的 {@link Particle.DustOptions}，五类点位各自一色。
 * 部署向导侧（{@link AdminDeployManager}）与竞技场管理 GUI 侧
 * （{@link LocationParticlePreview}）共用本类，保证两套预览系统的视觉永远一致。</p>
 *
 * <p>【为什么保留逐点循环，不做「一次调用 + 大 offset」合并】原性能审计曾建议把光柱合并为
 * 1 次调用（count=51、中心 y+10、offsetY=10），前提是 offset 为「均匀覆盖范围」。
 * 反编译 paper-1.21.4 核实该前提不成立：服务端粒子包只做同世界 + 距离判定、不做范围变换；
 * offset 在 count&gt;0 时是高斯散布的标准差 σ。σ=10 会让约 32% 粒子溢出柱外、密度中浓端淡、
 * 地面起点消失，与逐点柱视觉不一致。本路径每 4 tick 才跑一次、按点循环，并非热路径，
 * 故保留逐点循环。</p>
 */
final class PointParticleRenderer {

    /** 光柱高度（格）：从点位方块中心向上 20 格 */
    private static final double BEAM_HEIGHT = 20.0;
    /** 光柱粒子间距（格）：0.4 格一颗，y=0→20 共 51 次迭代 */
    private static final double BEAM_STEP = 0.4;
    /** 螺旋半径（格） */
    private static final double SPIRAL_RADIUS = 1.2;
    /** 螺旋高度（格）：每 60 tick（3 秒）爬升一轮 */
    private static final double SPIRAL_HEIGHT = 3.0;
    /** 螺旋每帧粒子点数 */
    private static final int SPIRAL_POINTS = 3;

    private PointParticleRenderer() {
    }

    /**
     * 在给定位置绘制一个类别色的点位标记（垂直光柱 + 环绕螺旋）。
     *
     * @param world        点位所在世界（调用方保证非 null）
     * @param loc          点位（方块坐标；绘制时 +0.5 居中）
     * @param kind         点位类别（决定颜色，见 {@link PointKind}）
     * @param angle        螺旋当前角度（由调用方的动画循环推进）
     * @param ticksElapsed 已流逝的 tick 数（决定螺旋的爬升进度）
     */
    static void drawMarker(World world, Location loc, PointKind kind, double angle, int ticksElapsed) {
        // 1. 垂直光柱（类别色 DUST，从点位方块中心到 y+20）
        for (double y = 0; y <= BEAM_HEIGHT; y += BEAM_STEP) {
            world.spawnParticle(Particle.DUST,
                loc.getX() + 0.5, loc.getY() + y, loc.getZ() + 0.5,
                1, 0, 0, 0, 0, kind.beamDust());
        }

        // 2. 周围螺旋符文装饰（同色 DUST、尺寸略大，每 3 秒爬升一轮）
        for (int i = 0; i < SPIRAL_POINTS; i++) {
            double spiralAngle = angle + (i * 2 * Math.PI / SPIRAL_POINTS);
            double spiralY = (ticksElapsed % 60) * (SPIRAL_HEIGHT / 60.0);
            double spiralX = loc.getX() + 0.5 + Math.cos(spiralAngle) * SPIRAL_RADIUS;
            double spiralZ = loc.getZ() + 0.5 + Math.sin(spiralAngle) * SPIRAL_RADIUS;
            world.spawnParticle(Particle.DUST,
                spiralX, loc.getY() + spiralY, spiralZ,
                1, 0, 0, 0, 0, kind.spiralDust());
        }
    }
}
