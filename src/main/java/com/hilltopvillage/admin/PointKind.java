package com.hilltopvillage.admin;

import org.bukkit.Color;
import org.bukkit.Particle;

/**
 * 部署点位类别：为坐标粒子预览提供统一的「颜色 + 文案」配色体系（F-7）。
 *
 * <p>五类点位各有一种专属 DUST 颜色，管理员一眼即可分辨预览中的点位类型：
 * 玩家出生点=绿、怪物刷怪点=红、能量节点=蓝、村民=黄、商店=金。
 * 同一种颜色同时用于两套预览系统：{@link AdminDeployManager}（部署向导 / deploy 命令）
 * 与 {@link LocationParticlePreview}（竞技场管理 GUI）。</p>
 *
 * <p>{@link Particle.DustOptions} 每次构造都会分配对象，而粒子循环每帧每点位要调用
 * 数十次 spawnParticle，因此这里在枚举常量构造时一次性建好并全程复用
 * （DustOptions 创建后只读使用，跨调用共享安全）。</p>
 */
public enum PointKind {

    /** 玩家出生点：绿色 */
    SPAWN("particles.no-spawnpoints", "particles.showing", Color.fromRGB(0x3CFF5A)),

    /** 怪物刷怪点：红色 */
    MOB_SPAWN("particles.no-mobspawnpoints", "particles.showing-mobspawns", Color.fromRGB(0xFF5555)),

    /** 能量节点：蓝色 */
    NODE("particles.no-nodes", "particles.showing-nodes", Color.fromRGB(0x4A8CFF)),

    /** 待保护村民：黄色 */
    VILLAGER("particles.no-villagers", "particles.showing-villagers", Color.fromRGB(0xFFDE59)),

    /** 商店村民：金色 */
    SHOP("particles.no-shopnpc", "particles.showing-shop", Color.fromRGB(0xFFAA00));

    /** 光柱 DUST 尺寸（与原 END_ROD 柱观感接近） */
    private static final float BEAM_DUST_SIZE = 1.0f;
    /** 螺旋 DUST 尺寸（略大，增强环绕装饰的可辨识度） */
    private static final float SPIRAL_DUST_SIZE = 1.3f;

    /** 无点位时的提示文案键（由预览方法统一发送） */
    private final String emptyKey;
    /** 预览开始时的提示文案键，占位符 {0}=点位数、{1}=持续秒数 */
    private final String showingKey;
    /** 垂直光柱粒子数据（按类别取色） */
    private final Particle.DustOptions beamDust;
    /** 螺旋装饰粒子数据（同色、尺寸略大） */
    private final Particle.DustOptions spiralDust;

    PointKind(String emptyKey, String showingKey, Color color) {
        this.emptyKey = emptyKey;
        this.showingKey = showingKey;
        this.beamDust = new Particle.DustOptions(color, BEAM_DUST_SIZE);
        this.spiralDust = new Particle.DustOptions(color, SPIRAL_DUST_SIZE);
    }

    public String emptyKey() {
        return emptyKey;
    }

    public String showingKey() {
        return showingKey;
    }

    public Particle.DustOptions beamDust() {
        return beamDust;
    }

    public Particle.DustOptions spiralDust() {
        return spiralDust;
    }
}
