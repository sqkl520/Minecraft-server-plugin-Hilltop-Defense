package com.hilltopvillage.admin.gui;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.menu.AbstractConfigMenu;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * 管理类 GUI 的公共基类：在 {@link AbstractConfigMenu} 之上补两个安全缺口。
 *
 * <p>缺口①：父类只处理 {@link InventoryClickEvent}，没有 {@link InventoryDragEvent} 处理器，
 * 因此玩家可以把 GUI 内的物品拖到自己的背包里。</p>
 *
 * <p>缺口②：父类 {@code onClick} 在点击玩家自己背包时直接 return（不取消事件），
 * 于是 shift-click 能把玩家背包里的物品塞进 GUI。本类对「本菜单是 top inventory」
 * 的所有点击一律取消。</p>
 *
 * <p><b>⚠ 关于 {@code setBackButton()}：</b>管理类 GUI 请<b>不要</b>调用父类的
 * {@link AbstractConfigMenu#setBackButton} —— 它固定占用 {@code size - 9}（54 格菜单即 slot 45），
 * 而管理 GUI 的底行（第 45 槽及之后）要放上一页 / 下一页等分页控件，会与返回按钮冲突。
 * 需要返回按钮时请自行在底行合适位置用 {@code setClickableItem(...)} 放置，
 * 文案使用 {@code manage.back} / {@code manage.page.*}。</p>
 *
 * <p>本类纯主线程使用：全部操作都发生在 Bukkit 事件线程与 GUI 交互流程内。</p>
 */
public abstract class AbstractAdminMenu extends AbstractConfigMenu {

    /** 主插件实例，供子类访问各 Manager。 */
    protected final HilltopVillagePlugin hilltop;

    protected AbstractAdminMenu(Player admin, String title, int size,
                                HilltopVillagePlugin plugin, LanguageManager lang) {
        super(admin, title, size, plugin, lang);
        this.hilltop = plugin;
    }

    /**
     * 缺口①：拦截一切涉及本菜单顶部容器的拖动，防止 GUI 物品被拖出。
     *
     * <p>只取消「raw slot 落在 top inventory 范围内」的拖动，玩家在自己背包内部的
     * 整理拖动不受影响。</p>
     */
    @EventHandler(priority = EventPriority.LOW)
    public void guardDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!event.getWhoClicked().equals(admin)) return;
        if (event.getView().getTopInventory().getHolder() != this) return;

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * 缺口②：本菜单作为 top inventory 时，一律取消点击（含 shift-click 把背包物品塞进 GUI）。
     *
     * <p>刻意使用与父类不同的方法名 + 更低优先级叠加，<b>不 override</b> 父类的
     * {@code onClick}：Bukkit 的 {@code registerEvents} 按 {@code getMethods()} 枚举
     * {@code @EventHandler}，覆写会让父类实现被替换而非叠加，导致父类的点击分发失效。
     * 本处理器只取消事件不消费它，父类的 NORMAL 优先级处理器仍会收到事件并正常分发。</p>
     */
    @EventHandler(priority = EventPriority.LOW)
    public void guardClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!event.getWhoClicked().equals(admin)) return;
        if (event.getView().getTopInventory().getHolder() != this) return;

        event.setCancelled(true);
    }
}
