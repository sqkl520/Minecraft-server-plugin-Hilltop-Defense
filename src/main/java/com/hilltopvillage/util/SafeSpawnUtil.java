package com.hilltopvillage.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * 安全落点工具（fix4 G1b）：把"凭空坐标"修正到该列地表的安全落点。
 *
 * <p>背景：fix3 把 arenas.yml 的 {@code spawn-radius-min/max} 接线生效后，作者服务器
 * （天空岛地图）刷怪点周围的水平随机偏移大量落在岛外虚空——怪物刷在虚空随即下落出
 * 世界（日志成片 {@code fell out of the world}），第一波一只不剩；玩家开局传送后
 * 同样下坠掉虚空。本工具按列向下扫描找第一个实心非液体方块，把落点归到其顶面，
 * 杜绝"落点在虚空 / 实心方块内部 / 头顶堵死"三类不安全情形。</p>
 */
public final class SafeSpawnUtil {

    private SafeSpawnUtil() {
    }

    /**
     * 在 base 所在列（x/z 取整到方块）向下扫描安全落点。
     *
     * <p>扫描方式：起点 = {@code min(base.y + 4, 世界最高方块 y)}，向下到世界最低高度，
     * 取第一个实心且非液体（LAVA/WATER）的方块，落点即其顶面（y = 方块 y + 1），
     * 并把 x/z 居中（+0.5，保持浮点不取整——实体落点居中可避免卡在方块接缝）。
     * 要求落点上方 2 格无实心方块（越界视为无方块），防"刷在洞穴顶下 / 墙缝里"卡墙；
     * 顶面被堵时继续向下找更低的可用表面。</p>
     *
     * <p>不安全情形一律返回 null（由调用方决定重试或回退）：整列无实心方块（岛外/虚空）、
     * 落点上方 2 格内有实心方块或无向下可用表面、落点贴近世界底部
     * （{@code y < 世界最低高度 + 2}，紧邻虚空层）。</p>
     *
     * @param base 参考坐标（不会被修改；yaw/pitch 原样保留）
     * @return 修正后的安全落点；不安全时返回 null
     */
    public static Location findSafeGround(Location base) {
        if (base == null) return null;
        World world = base.getWorld();
        if (world == null) return null;

        int x = base.getBlockX();
        int z = base.getBlockZ();
        int maxY = world.getMaxHeight() - 1;
        int minY = world.getMinHeight();
        // base.y 异常低（低于世界底部）时从 minY 起扫，而不是直接放弃整列
        int startY = Math.max(minY, Math.min(base.getBlockY() + 4, maxY));

        for (int y = startY; y >= minY; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            // isSolid 已排除空气/花草/水等，LAVA/WATER 再显式兜底（岩浆顶面绝不能当落点）
            if (!type.isSolid() || type == Material.LAVA || type == Material.WATER) continue;

            // 上方 2 格必须无实心方块（本块紧贴世界顶部时落点会跑到世界外，同样不可用），
            // 被堵则继续向下找更低的表面（如洞穴地面），找不到就整列判不安全
            boolean blocked = false;
            for (int dy = 1; dy <= 2; dy++) {
                int above = y + dy;
                if (above > maxY || world.getBlockAt(x, above, z).getType().isSolid()) {
                    blocked = true;
                    break;
                }
            }
            if (blocked) continue;

            int surfaceY = y + 1;
            // 贴近世界底部（基岩层表面）：紧邻虚空，实体仍可能被环境挤出世界，整列判不安全
            if (surfaceY < minY + 2) return null;

            Location result = base.clone();
            result.setX(x + 0.5);
            result.setY(surfaceY);
            result.setZ(z + 0.5);
            return result;
        }
        return null;
    }
}
