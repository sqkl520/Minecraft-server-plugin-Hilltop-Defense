package com.hilltopvillage.doc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.bukkit.plugin.java.JavaPlugin;

public class CommandsDocumentGenerator {

    private final JavaPlugin plugin;

    public CommandsDocumentGenerator(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void generate() {
        File docFile = new File(plugin.getDataFolder(), "commands.md");
        StringBuilder md = new StringBuilder();

        md.append("# 村民守卫战PLUS — 命令文档\n\n");
        md.append("> 本文档由插件在服务器启动时自动生成/更新。\n");
        md.append("> 最后更新: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())).append("\n\n");
        md.append("---\n\n");

        md.append("## 基础命令\n\n");

        md.append("### `/hilltop join`\n\n");
        md.append("- **功能**: 加入游戏等待队列。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop join`\n");
        md.append("- **注意事项**: 需在游戏状态为 WAITING 或 STARTING 时使用，游戏开始后无法加入。\n\n");

        md.append("### `/hilltop leave`\n\n");
        md.append("- **功能**: 退出游戏。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop leave`\n");
        md.append("- **注意事项**: 游戏进行中退出将视为放弃，已获得的积分和击杀数将丢失。\n\n");

        md.append("### `/hilltop status`\n\n");
        md.append("- **功能**: 查看当前游戏状态。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop status`\n");
        md.append("- **注意事项**: 显示内容包括游戏状态、当前波次、在线玩家数、存活怪物数、能量节点状态等。\n\n");

        md.append("### `/hilltop hammer`\n\n");
        md.append("- **功能**: 获取神圣之锤（Sacred Hammer）。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop hammer`\n");
        md.append("- **注意事项**: 锤子右键可触发蓄力重击（Smash），左键为普通攻击。每位玩家同时只能持有一把锤子。\n\n");

        md.append("### `/hilltop help`\n\n");
        md.append("- **功能**: 查看命令帮助。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop help`\n");
        md.append("- **注意事项**: 管理员专用，显示所有可用命令及简要说明。\n\n");

        md.append("### `/hilltop lang <en|zh>`\n\n");
        md.append("- **功能**: 切换插件语言显示。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: `<en|zh>` — 目标语言代码，en = English，zh = 中文。\n");
        md.append("- **使用示例**: `/hilltop lang en`, `/hilltop lang zh`\n");
        md.append("- **注意事项**: 切换后所有命令返回的消息将以所选语言显示。语言偏好会持久化保存到 config.yml。消息文本可在 `plugins/HilltopVillage/messages.yml` 中自定义编辑。\n\n");

        md.append("---\n\n");
        md.append("## 管理命令\n\n");
        md.append("> 以下命令需要 `hilltopvillage.admin` 权限。\n\n");

        md.append("### `/hilltop start`\n\n");
        md.append("- **功能**: 启动游戏。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop start`\n");
        md.append("- **注意事项**: 需确保核心位置已设置（通过 `/hilltop deploy setcore`），游戏将从等待状态进入运行状态。\n\n");

        md.append("### `/hilltop stop`\n\n");
        md.append("- **功能**: 停止当前游戏。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop stop`\n");
        md.append("- **注意事项**: 强制结束游戏，所有进行中的波次和怪物将被清除，如需重新开始请再次执行 start 命令。\n\n");

        md.append("### `/hilltop deploy`\n\n");
        md.append("- **功能**: 打开管理员部署菜单。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy`\n");
        md.append("- **注意事项**: 显示当前部署状态、所有可用的部署子命令及说明。搭配以下子命令使用。\n\n");

        md.append("### `/hilltop deploy setcore`\n\n");
        md.append("- **功能**: 将管理员当前所在坐标设置为游戏核心位置。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy setcore`\n");
        md.append("- **注意事项**: 核心是村庄守卫的核心建筑，怪物将以此为目标。请站在核心上方执行命令。设置后会播放粒子效果作为视觉反馈。\n\n");

        md.append("### `/hilltop deploy addspawn`\n\n");
        md.append("- **功能**: 将管理员当前所在坐标添加为怪物生成点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy addspawn`\n");
        md.append("- **注意事项**: 每个生成点会被自动分配一个独特的粒子效果，用于视觉区分。可设置多个生成点。站在想要的位置执行命令即可精准记录坐标。\n\n");

        md.append("### `/hilltop deploy listspawns`\n\n");
        md.append("- **功能**: 列出所有已设置的生成点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy listspawns`\n");
        md.append("- **注意事项**: 显示每个生成点的编号、坐标。编号用于 delspawn 命令。\n\n");

        md.append("### `/hilltop deploy delspawn <编号>`\n\n");
        md.append("- **功能**: 删除指定编号的生成点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: `<编号>` — 生成点编号（从 listspawns 获取）。\n");
        md.append("- **使用示例**: `/hilltop deploy delspawn 2`\n");
        md.append("- **注意事项**: 删除后所有剩余生成点的粒子自动重新分配，确保无重复。\n\n");

        md.append("### `/hilltop deploy showparticles`\n\n");
        md.append("- **功能**: 在所有已设置的生成点周围展示粒子效果。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy showparticles`\n");
        md.append("- **注意事项**: 粒子效果在以每个生成点为中心、半径3格的范围内以螺旋动画展示，默认持续30秒。每个生成点使用其分配的独特粒子类型，便于视觉区分。重复执行会重置计时。\n\n");

        md.append("### `/hilltop deploy clearspawns`\n\n");
        md.append("- **功能**: 清除所有生成点（需二次确认）。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy clearspawns`\n");
        md.append("- **注意事项**: 执行后需在15秒内输入 `/hilltop deploy confirmclear` 确认。超时或未确认则操作取消。清除后怪物将在核心周围随机生成。\n\n");

        md.append("### `/hilltop deploy confirmclear`\n\n");
        md.append("- **功能**: 确认清除所有生成点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy confirmclear`\n");
        md.append("- **注意事项**: 此命令仅在 `/hilltop deploy clearspawns` 执行后15秒内有效。\n\n");

        md.append("### `/hilltop deploy reloadnodes`\n\n");
        md.append("- **功能**: 重新扫描地图中的能量节点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy reloadnodes`\n");
        md.append("- **注意事项**: 重新从世界方块中加载能量节点，用于地图更新后刷新节点状态。\n\n");

        md.append("### `/hilltop reloadconfig`\n\n");
        md.append("- **功能**: 重新加载游戏配置文件。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop reloadconfig`\n");
        md.append("- **注意事项**: 从 `game-settings.yml` 重新加载所有游戏参数（波次配置、怪物属性、锤子参数、节点设置等），无需重启服务器即可使修改生效。\n\n");

        md.append("---\n\n");
        md.append("## 配置中心命令\n\n");
        md.append("> 以下命令需要 `hilltopvillage.config.admin` 权限。\n\n");

        md.append("### `/hilltop config`\n\n");
        md.append("- **功能**: 打开游戏配置 GUI 菜单。\n");
        md.append("- **权限**: `hilltopvillage.config.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop config`\n");
        md.append("- **注意事项**: 通过 GUI 界面配置游戏规则、锤子参数、节点设置、怪物属性和波次组合。左键/右键/Shift+点击可进行不同操作。关闭菜单后自动保存配置。\n\n");

        md.append("### `/hilltop config nodes repairitem <材料名>`\n\n");
        md.append("- **功能**: 设置修复能量节点所需的物品材料。\n");
        md.append("- **权限**: `hilltopvillage.config.nodes`\n");
        md.append("- **参数**: `<材料名>` — Minecraft 材料名称（如 IRON_INGOT、GOLD_INGOT）。\n");
        md.append("- **使用示例**: `/hilltop config nodes repairitem IRON_INGOT`\n");
        md.append("- **注意事项**: 材料名需使用大写英文，且必须为有效的 Minecraft 材料枚举名。\n\n");

        md.append("### `/hilltop config nodes add <材料名>`\n\n");
        md.append("- **功能**: 添加能量节点的可用方块类型。\n");
        md.append("- **权限**: `hilltopvillage.config.nodes`\n");
        md.append("- **参数**: `<材料名>` — 要添加为能量节点的方块类型（如 CRYING_OBSIDIAN）。\n");
        md.append("- **使用示例**: `/hilltop config nodes add CRYING_OBSIDIAN`\n");
        md.append("- **注意事项**: 添加后方块会被识别为能量节点，玩家可与其交互获取增益效果。重复添加相同材料不会产生效果。\n\n");

        md.append("### `/hilltop config nodes remove <材料名>`\n\n");
        md.append("- **功能**: 移除能量节点的可用方块类型。\n");
        md.append("- **权限**: `hilltopvillage.config.nodes`\n");
        md.append("- **参数**: `<材料名>` — 要移除的方块类型。\n");
        md.append("- **使用示例**: `/hilltop config nodes remove CRYING_OBSIDIAN`\n");
        md.append("- **注意事项**: 移除后该方块类型不再被识别为能量节点。\n\n");

        md.append("---\n\n");
        md.append("## 权限节点\n\n");
        md.append("| 权限节点 | 说明 |\n");
        md.append("|---|---|\n");
        md.append("| `hilltopvillage.admin` | 管理员权限，包含 start/stop/deploy/help/reloadconfig |\n");
        md.append("| `hilltopvillage.config.admin` | 配置中心管理员权限，包含 config 主命令 |\n");
        md.append("| `hilltopvillage.config.gamerules` | 游戏规则配置权限 |\n");
        md.append("| `hilltopvillage.config.hammer` | 锤子参数配置权限 |\n");
        md.append("| `hilltopvillage.config.nodes` | 能量节点配置权限 |\n");
        md.append("| `hilltopvillage.config.monsters` | 怪物属性配置权限 |\n");
        md.append("| `hilltopvillage.config.waves` | 波次组合配置权限 |\n\n");

        md.append("---\n\n");
        md.append("## 注意事项\n\n");
        md.append("1. **命令别名**: `/hilltop` 命令也可使用 `/hv` 或 `/htv` 作为别名。\n");
        md.append("2. **控制台限制**: 除 status/reloadconfig 外，大部分涉及 GUI 或播放效果的命令仅限玩家在游戏内使用。\n");
        md.append("3. **部署流程**: 建议按照 `setcore → addspawn → reloadnodes → start` 的顺序操作。\n");
        md.append("4. **配置保存**: 使用 GUI 关闭菜单时配置会自动保存，`/hilltop reloadconfig` 用于手动重载外部编辑的配置。\n");
        md.append("5. **粒子展示**: `/hilltop deploy showparticles` 仅展示当前已设置的生成点对应粒子。若没有生成点则不会有任何效果。\n");
        md.append("6. **多语言**: 使用 `/hilltop lang <en|zh>` 切换语言。所有消息文本可在 `plugins/HilltopVillage/messages.yml` 中自定义编辑。\n");

        try {
            if (!docFile.getParentFile().exists()) {
                docFile.getParentFile().mkdirs();
            }
            java.nio.file.Files.write(docFile.toPath(), md.toString().getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("命令文档已生成: " + docFile.getAbsolutePath());
        } catch (IOException e) {
            plugin.getLogger().severe("无法生成命令文档 commands.md: " + e.getMessage());
        }
    }
}
