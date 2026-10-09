package com.hilltopvillage.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.hilltopvillage.util.LanguageManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 发送部署菜单、命令用法和可点击命令行的工具类。
 * 供 CommandHandler 和 SetupWizard 共用。
 */
public class DeployMenuSender {

    private final LanguageManager lang;

    public DeployMenuSender(LanguageManager lang) {
        this.lang = lang;
    }

    public void sendUsage(CommandSender sender) {
        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;
        String hover = lang.getFor(player, "usage-click-to-copy");

        sender.sendMessage(lang.getFor(player, "usage-header"));

        // 玩家命令
        sender.sendMessage(lang.getFor(player, "usage-player-header"));
        sendClickableLine(sender, "usage-join", "/hilltop join ", hover);
        sendClickableLine(sender, "usage-leave", "/hilltop leave", hover);
        sendClickableLine(sender, "usage-lobby", "/hilltop lobby", hover);
        sendClickableLine(sender, "usage-gui", "/hilltop gui", hover);
        sendClickableLine(sender, "usage-status", "/hilltop status", hover);
        sendClickableLine(sender, "usage-list", "/hilltop list", hover);
        sendClickableLine(sender, "usage-stats", "/hilltop stats ", hover);
        sendClickableLine(sender, "usage-top", "/hilltop top ", hover);
        sendClickableLine(sender, "usage-lang", "/hilltop lang ", hover);

        // 管理员命令
        if (sender.hasPermission("hilltopvillage.admin")) {
            sender.sendMessage(lang.getFor(player, "usage-admin-header"));
            sendClickableLine(sender, "usage-start", "/hilltop start", hover);
            sendClickableLine(sender, "usage-stop", "/hilltop stop", hover);
            sendClickableLine(sender, "usage-hammer", "/hilltop hammer", hover);
            sendClickableLine(sender, "usage-fireball", "/hilltop fireball", hover);
            sendClickableLine(sender, "usage-setup", "/hilltop setup", hover);
            sendClickableLine(sender, "usage-deploy", "/hilltop deploy", hover);
            sendClickableLine(sender, "usage-config", "/hilltop config", hover);
            sendClickableLine(sender, "usage-reloadconfig", "/hilltop reloadconfig", hover);
            sendClickableLine(sender, "usage-arenas", "/hilltop arenas", hover);
            sendClickableLine(sender, "usage-manage", "/hilltop manage", hover);
            sendClickableLine(sender, "usage-enable", "/hilltop enable ", hover);
            sendClickableLine(sender, "usage-disable", "/hilltop disable ", hover);
            sendClickableLine(sender, "usage-shop-reload", "/hilltop shop reload", hover);
            sendClickableLine(sender, "usage-build", "/hilltop build", hover);
            sendClickableLine(sender, "usage-tps", "/hilltop tps", hover);
        }
    }

    /** 部署命令帮助菜单（点击自动填入聊天栏） */
    public void sendDeployMenu(Player player) {
        String hover = lang.getFor(player, "usage-click-to-copy");

        player.sendMessage("");
        player.sendMessage(LegacyComponentSerializer.legacySection()
                .deserialize(lang.getFor(player, "deploy.menu-title"))
                .hoverEvent(HoverEvent.showText(Component.text(lang.getFor(player, "deploy.menu-hover")))));

        sendClickableLine(player, "deploy.cmd-setup", "/hilltop setup", hover,
                lang.getFor(player, "deploy.desc-setup"));
        sendClickableLine(player, "deploy.cmd-setcore", "/hilltop deploy setcore", hover,
                lang.getFor(player, "deploy.desc-setcore"));
        sendClickableLine(player, "deploy.cmd-addspawn", "/hilltop deploy addspawn", hover,
                lang.getFor(player, "deploy.desc-addspawn"));
        sendClickableLine(player, "deploy.cmd-listspawns", "/hilltop deploy listspawns", hover,
                lang.getFor(player, "deploy.desc-listspawns"));
        sendClickableLine(player, "deploy.cmd-delspawn", "/hilltop deploy delspawn ", hover,
                lang.getFor(player, "deploy.desc-delspawn"));
        sendClickableLine(player, "deploy.cmd-showparticles", "/hilltop deploy showparticles", hover,
                lang.getFor(player, "deploy.desc-showparticles"));
        sendClickableLine(player, "deploy.cmd-clearspawns", "/hilltop deploy clearspawns", hover,
                lang.getFor(player, "deploy.desc-clearspawns"));
        sendClickableLine(player, "deploy.cmd-confirmclear", "/hilltop deploy confirmclear", hover,
                lang.getFor(player, "deploy.desc-confirmclear"));
        sendClickableLine(player, "deploy.cmd-addmobspawn", "/hilltop deploy addmobspawn", hover,
                lang.getFor(player, "deploy.desc-addmobspawn"));
        sendClickableLine(player, "deploy.cmd-listmobspawns", "/hilltop deploy listmobspawns", hover,
                lang.getFor(player, "deploy.desc-listmobspawns"));
        sendClickableLine(player, "deploy.cmd-delmobspawn", "/hilltop deploy delmobspawn ", hover,
                lang.getFor(player, "deploy.desc-delmobspawn"));
        sendClickableLine(player, "deploy.cmd-reloadnodes", "/hilltop deploy reloadnodes", hover,
                lang.getFor(player, "deploy.desc-reloadnodes"));
        sendClickableLine(player, "deploy.cmd-addvillager", "/hilltop deploy addvillager", hover,
                lang.getFor(player, "deploy.desc-addvillager"));
        sendClickableLine(player, "deploy.cmd-listvillagers", "/hilltop deploy listvillagers", hover,
                lang.getFor(player, "deploy.desc-listvillagers"));

        player.sendMessage("");
    }

    /** 发送可点击复制的命令提示行（带描述） */
    public void sendClickableLine(CommandSender sender, String msgKey, String command, String hoverText,
                                    String description) {
        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;
        String msg = lang.getFor(player, msgKey);
        String line = description + " - " + msg;

        if (isPlayer) {
            Component component = LegacyComponentSerializer.legacySection()
                    .deserialize(line)
                    .clickEvent(ClickEvent.suggestCommand(command))
                    .hoverEvent(HoverEvent.showText(Component.text(hoverText)));
            player.sendMessage(component);
        } else {
            sender.sendMessage(line);
        }
    }

    /** 发送可点击复制的命令提示行 */
    public void sendClickableLine(CommandSender sender, String msgKey, String command, String hoverText) {
        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;
        String msg = lang.getFor(player, msgKey);

        if (isPlayer) {
            Component component = LegacyComponentSerializer.legacySection()
                    .deserialize(msg)
                    .clickEvent(ClickEvent.suggestCommand(command))
                    .hoverEvent(HoverEvent.showText(Component.text(hoverText)));
            player.sendMessage(component);
        } else {
            sender.sendMessage(msg);
        }
    }
}
