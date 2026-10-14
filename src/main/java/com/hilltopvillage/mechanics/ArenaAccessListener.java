package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaDefinition;
import com.hilltopvillage.arena.ArenaInstance;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 竞技场世界准入防护（P2-41，作者决策 D-4：加传送守卫）。
 *
 * <p>竞技场世界此前没有任何"世界级"入口防护——入场的唯一门是 {@code /hilltop join} /
 * GUI 的命令路径（{@code ArenaInstance.addPlayer} 的校验）。任何玩家用原版 {@code /tp}、
 * {@code /execute in <世界>}、Multiverse 等外部手段进入竞技场世界后，交互全部开放：
 * 可攻击波次怪（非参赛者击杀 NPE，见 M-19）、捡走掉落的货币、使用商店 NPC 购物、
 * 随意破坏方块。本监听器把"只有成员在世界里"从不变量升级为世界层强制。</p>
 *
 * <h2>只拦「进」，不拦「出」</h2>
 * <ul>
 *   <li>目标世界是竞技场定义（registry 反查，覆盖已禁用 / 未部署的场）且玩家不是该场成员 → 拦；</li>
 *   <li>成员从竞技场世界传送出去（结算踢回大厅 / 离开）→ 放行（目标不是竞技场世界）；</li>
 *   <li>大厅内传送、非竞技场世界之间 → 放行；</li>
 *   <li>目标世界是大厅世界（配置异常时与竞技场世界重合的兜底）→ 放行。</li>
 * </ul>
 *
 * <h2>成员判定必须同时查两处（join 时序）</h2>
 * <p>{@code MultiArenaManager#joinArena} 的时序是
 * {@code arena.addPlayer(player)} 内部<b>先</b>把玩家加入实例的 {@code players} 集合、
 * <b>再</b>传送到场内的等待区，而 {@code playerArenaMap.put} 发生在 {@code addPlayer}
 * <b>返回之后</b>。因此只查 {@code getPlayerArena(player)}（读 playerArenaMap）会把
 * 正常入场的传送误拦。放行条件 = 「playerArenaMap 已指向本场」<b>或</b>
 * 「本场实例的成员集合已包含该玩家」。重连（rejoin）窗口内玩家仍在成员集合，同样放行。</p>
 *
 * <p>管理员豁免（{@code hilltopvillage.admin}）：部署向导、管理 GUI 传送核心、
 * 未部署场的进图核对都依赖管理员自由进出竞技场世界。</p>
 */
public class ArenaAccessListener implements Listener {

    private final HilltopVillagePlugin plugin;

    public ArenaAccessListener(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;

        String worldName = to.getWorld().getName();

        // 大厅世界放行（配置异常时竞技场世界与大厅世界重合的兜底）
        if (plugin.getMultiArenaManager().isLobbyWorld(worldName)) return;

        // 只拦目标世界能反查到竞技场定义（含已禁用 / 未部署的场）的传送
        ArenaDefinition definition = plugin.getMultiArenaManager().getDefinitionManager()
                .getArenaByWorld(worldName);
        if (definition == null) return;

        Player player = event.getPlayer();

        // 管理员豁免（部署 / 维护需要进图）
        if (player.hasPermission("hilltopvillage.admin")) return;

        // 本场成员放行：playerArenaMap 命中本场
        ArenaInstance mapped = plugin.getMultiArenaManager().getPlayerArena(player);
        if (mapped != null && definition.getId().equals(mapped.getArenaId())) return;

        // 本场实例成员集合命中（覆盖 join 流程「先入成员集合、后写 playerArenaMap」的窗口，
        // 以及实例存在但映射尚未写入的其它过渡态）
        ArenaInstance target = plugin.getMultiArenaManager().getArena(definition.getId());
        if (target != null && target.isPlayer(player)) return;

        // 非成员：拒绝入场并提示（不给"自动送回大厅"——已在世界内的人被拦传送时，
        // 主动 teleport 会在事件处理器内重入监听器，保持最小行为）
        event.setCancelled(true);
        plugin.getLanguageManager().send(player, "arena.access-denied");
    }
}
