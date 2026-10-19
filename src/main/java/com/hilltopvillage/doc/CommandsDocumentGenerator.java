package com.hilltopvillage.doc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 运行时命令文档生成器（启动时异步写出 {@code commands.md}）。
 *
 * <p>维护义务：本类内容为硬编码文本，修正命令行为/文案时必须与 README 命令表、
 * {@code plugin.yml} usage 同步核对（fix5 M-18 教训：leave 击杀口径、start 前置、
 * 三命令的可选参数行曾与实现脱节）。</p>
 */
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
        md.append("- **参数**: `[竞技场]` — 可选，竞技场 ID（不填时自动选择可用竞技场）。\n");
        md.append("- **使用示例**: `/hilltop join`\n");
        md.append("- **注意事项**: 需在游戏状态为 WAITING 或 STARTING 时使用，游戏开始后无法加入。\n\n");

        md.append("### `/hilltop leave`\n\n");
        md.append("- **功能**: 退出游戏。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop leave`\n");
        md.append("- **注意事项**: 中途离开只累计击杀/阵亡/修复节点数与获取水晶，不计胜负（胜/负场与场次需要打到结算）。撤离后物品快照原样归还。\n\n");

        md.append("### `/hilltop gui`\n\n");
        md.append("- **功能**: 打开竞技场选择菜单（带状态与人数）。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop gui`\n");
        md.append("- **注意事项**: 仅限玩家使用（控制台不可执行）。菜单中彩色条目代表可加入状态，灰色条目不可加入。\n\n");

        md.append("### `/hilltop lobby`\n\n");
        md.append("- **功能**: 返回大厅。\n");
        md.append("- **权限**: 无（所有玩家可用）；`setspawn` 子命令需 `hilltopvillage.admin`。\n");
        md.append("- **参数**: `setspawn` — 可选（管理员），把当前位置设为大厅出生点。\n");
        md.append("- **使用示例**: `/hilltop lobby`, `/hilltop lobby setspawn`\n");
        md.append("- **注意事项**: 若当前在竞技场中，会先退出该竞技场再传送到大厅。仅限玩家使用。`lobby setspawn` 必须在**大厅世界内**执行，位置（含朝向）即刻写入 `arenas.yml` 的 `lobby.spawn` 并即时生效（新登录、大厅复活与 `/hilltop lobby` 立即使用新出生点，无需重启）。\n\n");

        md.append("### `/hilltop list`\n\n");
        md.append("- **功能**: 列出所有竞技场。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop list`\n");
        md.append("- **注意事项**: 显示每个竞技场的编号、名称、状态与当前人数。\n\n");

        md.append("### `/hilltop status`\n\n");
        md.append("- **功能**: 查看当前游戏状态。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop status`\n");
        md.append("- **注意事项**: 显示内容包括游戏状态、当前波次、在线玩家数、存活怪物数、能量节点状态等。\n\n");

        md.append("### `/hilltop stats [玩家名]`\n\n");
        md.append("- **功能**: 查看自己或他人的统计。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: `[玩家名]` — 可选，指定要查看的玩家（不填则查看自己）。\n");
        md.append("- **使用示例**: `/hilltop stats`, `/hilltop stats Steve`\n");
        md.append("- **注意事项**: 显示胜率、K/D、修复节点数、累计获取能量水晶、最高波次等持久化数据。控制台执行时需带玩家名。\n\n");

        md.append("### `/hilltop top <类型>`\n\n");
        md.append("- **功能**: 查看排行榜。\n");
        md.append("- **权限**: 无（所有玩家可用）。\n");
        md.append("- **参数**: `<类型>` — 排行榜类型：`kills` / `wins` / `games` / `best-wave` / `nodes` / `deaths` / `crystals`（前 10 名）。\n");
        md.append("- **使用示例**: `/hilltop top kills`, `/hilltop top crystals`\n");
        md.append("- **注意事项**: 无参数或类型无效时会列出可用类型。`crystals` = 累计击杀获取的能量水晶榜。\n\n");

        md.append("### `/hilltop hammer`\n\n");
        md.append("- **功能**: 获取神圣之锤（Sacred Hammer）。\n");
        md.append("- **权限**: `hilltopvillage.admin`（管理员；普通玩家请通过村民商店购买）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop hammer`\n");
        md.append("- **注意事项**: 锤子右键可触发蓄力重击（Smash），左键为普通攻击。每位玩家同时只能持有一把锤子。\n\n");

        md.append("### `/hilltop fireball`\n\n");
        md.append("- **功能**: 获取烈焰蛋（Fireball）。\n");
        md.append("- **权限**: `hilltopvillage.admin`（管理员；普通玩家请通过村民商店购买）。\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop fireball`\n");
        md.append("- **注意事项**: 右键投掷，命中后爆炸造成范围伤害与击退。仅限玩家使用。\n\n");

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
        md.append("- **参数**: `[竞技场]` — 可选，竞技场 ID（不填时优先按管理员所在世界解析）。\n");
        md.append("- **使用示例**: `/hilltop start`\n");
        md.append("- **注意事项**: 需已完成部署——玩家出生点与怪物刷怪点两套坐标各至少 1 个（用 `/hilltop setup` 向导或 `/hilltop deploy addspawn` / `addmobspawn` 配置），且人数达到本场下限。\n\n");

        md.append("### `/hilltop stop`\n\n");
        md.append("- **功能**: 停止当前游戏。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: `[竞技场]` — 可选，竞技场 ID（不填时优先按管理员所在世界解析）。\n");
        md.append("- **使用示例**: `/hilltop stop`\n");
        md.append("- **注意事项**: 强制结束游戏，所有进行中的波次和怪物将被清除，如需重新开始请再次执行 start 命令。\n\n");

        md.append("### `/hilltop arenas`\n\n");
        md.append("- **功能**: 查看详细竞技场状态与故障原因。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop arenas`\n");
        md.append("- **注意事项**: 逐场展示状态、玩家数、世界加载情况，并标注未部署 / 世界缺失 / 配置禁用等故障原因及处置提示。\n\n");

        md.append("### `/hilltop enable [竞技场]`\n\n");
        md.append("- **功能**: 将竞技场加入启用队列。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: `[竞技场]` — 可选，竞技场 ID（不填时优先按管理员所在世界解析）。\n");
        md.append("- **使用示例**: `/hilltop enable`, `/hilltop enable arena2`\n");
        md.append("- **注意事项**: 加入队列后要等世界加载与初始化完成，状态变为「就绪」后才可加入。已就绪 / 已在队列中的竞技场会提示而不会重复入队。\n\n");

        md.append("### `/hilltop disable [竞技场]`\n\n");
        md.append("- **功能**: 禁用竞技场用于维护。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: `[竞技场]` — 可选，竞技场 ID（不填时优先按管理员所在世界解析）。\n");
        md.append("- **使用示例**: `/hilltop disable`, `/hilltop disable arena2`\n");
        md.append("- **注意事项**: 踢出玩家并从可加入列表移除。**对局进行中会被拒绝**，请等本局结束。禁用后立即重新启用会有几秒隔离等待（等上一局的地图回滚跑完）。\n\n");

        md.append("### `/hilltop build`\n\n");
        md.append("- **功能**: 切换大厅建造模式。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop build`\n");
        md.append("- **注意事项**: 开启后临时解除大厅保护，用于整修大厅；再次执行关闭。仅限玩家使用。\n\n");

        md.append("### `/hilltop tps`\n\n");
        md.append("- **功能**: TPS / MSPT 诊断。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop tps`\n");
        md.append("- **注意事项**: 输出服务器与对局双层级诊断信息（TPS/MSPT、内存、在线/对局玩家、波次、怪物、节点、DisplayEntity 清理队列、各世界实体/区块计数）。MSPT 超过 50ms 时附排查建议。\n\n");

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
        md.append("- **功能**: 将管理员当前所在坐标添加为玩家出生点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy addspawn`\n");
        md.append("- **注意事项**: 玩家出生点是开局时玩家被传送到的位置（阵亡复活用核心坐标，不是这里）；**不**影响怪物刷怪。至少需要 1 个玩家出生点才能开局。可设置多个，粒子预览统一显示为绿色。站在想要的位置执行命令即可精准记录坐标。\n\n");

        md.append("### `/hilltop deploy listspawns`\n\n");
        md.append("- **功能**: 列出所有已设置的玩家出生点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy listspawns`\n");
        md.append("- **注意事项**: 显示每个出生点的编号、坐标。编号用于 delspawn 命令。\n\n");

        md.append("### `/hilltop deploy delspawn <编号>`\n\n");
        md.append("- **功能**: 删除指定编号的玩家出生点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: `<编号>` — 出生点编号（从 listspawns 获取）。\n");
        md.append("- **使用示例**: `/hilltop deploy delspawn 2`\n");
        md.append("- **注意事项**: 删除后剩余出生点的编号会前移，请重新用 listspawns 确认编号。\n\n");

        md.append("### `/hilltop deploy showparticles`\n\n");
        md.append("- **功能**: 在所有玩家出生点周围展示粒子效果（30 秒）。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy showparticles`\n");
        md.append("- **注意事项**: 粒子为彩色光柱 + 环绕螺旋，五类点位各有专属颜色（玩家出生点=绿、怪物刷怪点=红、能量节点=蓝、村民=黄、商店=金）。重复执行会重置计时。\n\n");

        md.append("### `/hilltop deploy clearspawns`\n\n");
        md.append("- **功能**: 清除所有玩家出生点（需二次确认）。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy clearspawns`\n");
        md.append("- **注意事项**: 执行后需在 15 秒内输入 `/hilltop deploy confirmclear` 确认。超时或未确认则操作取消。清除后仍需重新添加出生点，否则无法开局（本操作不影响怪物刷怪点）。\n\n");

        md.append("### `/hilltop deploy confirmclear`\n\n");
        md.append("- **功能**: 确认清除所有玩家出生点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy confirmclear`\n");
        md.append("- **注意事项**: 此命令仅在 `/hilltop deploy clearspawns` 执行后 15 秒内有效。\n\n");

        md.append("### `/hilltop deploy addmobspawn`\n\n");
        md.append("- **功能**: 将管理员当前所在坐标添加为怪物刷怪点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy addmobspawn`\n");
        md.append("- **注意事项**: 怪物从刷怪点周围生成。若未设置独立刷怪点，旧存档会回退用玩家出生点刷怪，但新部署请两套点各设至少 1 个（`/hilltop start` 会检查）。可设置多个。\n\n");

        md.append("### `/hilltop deploy listmobspawns`\n\n");
        md.append("- **功能**: 列出所有已设置的怪物刷怪点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy listmobspawns`\n");
        md.append("- **注意事项**: 显示每个刷怪点的编号、坐标。编号用于 delmobspawn 命令。\n\n");

        md.append("### `/hilltop deploy delmobspawn <编号>`\n\n");
        md.append("- **功能**: 删除指定编号的怪物刷怪点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: `<编号>` — 刷怪点编号（从 listmobspawns 获取）。\n");
        md.append("- **使用示例**: `/hilltop deploy delmobspawn 1`\n");
        md.append("- **注意事项**: 删除后剩余刷怪点的编号会前移。\n\n");

        md.append("### `/hilltop deploy clearmobspawns`\n\n");
        md.append("- **功能**: 清除所有怪物刷怪点（需二次确认）。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy clearmobspawns`\n");
        md.append("- **注意事项**: 执行后需在 15 秒内输入 `/hilltop deploy confirmclearmob` 确认。清除后怪物将回退到玩家出生点刷怪。\n\n");

        md.append("### `/hilltop deploy confirmclearmob`\n\n");
        md.append("- **功能**: 确认清除所有怪物刷怪点。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy confirmclearmob`\n");
        md.append("- **注意事项**: 此命令仅在 `/hilltop deploy clearmobspawns` 执行后 15 秒内有效。\n\n");

        md.append("### `/hilltop deploy reloadnodes`\n\n");
        md.append("- **功能**: 从 `nodes.yml` 重新读取节点注册表。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy reloadnodes`\n");
        md.append("- **注意事项**: 只重新读取注册表，**不**扫描世界方块（节点注册请用向导第 4 步 [添加节点]，已废弃扫描式命令）。\n\n");

        md.append("### `/hilltop deploy setshop`\n\n");
        md.append("- **功能**: 将管理员当前所在坐标设为商店 NPC 位置，并立即生成商店村民。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy setshop`\n");
        md.append("- **注意事项**: 需站在目标竞技场世界内执行。位置按竞技场持久化在 `arenas.yml` 的 `arenas.<id>.shop-npc`，可重复执行覆盖旧位置（NPC 先移除再生成）。与向导第 6 步 [设置商店位置] 等效。\n\n");

        md.append("### `/hilltop deploy clearshop`\n\n");
        md.append("- **功能**: 清除本竞技场的商店 NPC（位置条目 + 实体）。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy clearshop`\n");
        md.append("- **注意事项**: 删除 `arenas.yml` 的 `arenas.<id>.shop-npc` 并移除「正站在本竞技场世界」的商店村民实体（商店 NPC 为插件级单例，别处竞技场 / 大厅的商店不受影响）。**对局进行中会被拒绝**，需先 `/hilltop stop`。清除后商店变为「未设置」，向导第 7 步与最终确认会重新要求设置。\n\n");

        md.append("### `/hilltop deploy addvillager`\n\n");
        md.append("- **功能**: 将管理员当前所在坐标登记为村民位置。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy addvillager`\n");
        md.append("- **注意事项**: 村民是怪物进攻的目标，**全部阵亡则对局失败**。至少需要 1 个。可多次执行登记多个村民。\n\n");

        md.append("### `/hilltop deploy listvillagers`\n\n");
        md.append("- **功能**: 列出所有已登记的村民。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy listvillagers`\n");
        md.append("- **注意事项**: 显示每个村民的编号、坐标。编号用于 delvillager 命令。\n\n");

        md.append("### `/hilltop deploy delvillager <编号>`\n\n");
        md.append("- **功能**: 删除指定编号的村民。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: `<编号>` — 村民编号（从 listvillagers 获取）。\n");
        md.append("- **使用示例**: `/hilltop deploy delvillager 2`\n");
        md.append("- **注意事项**: 删除后剩余村民的编号会前移。\n\n");

        md.append("### `/hilltop deploy clearvillagers`\n\n");
        md.append("- **功能**: 清除所有村民（需二次确认）。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy clearvillagers`\n");
        md.append("- **注意事项**: 执行后需在 15 秒内输入 `/hilltop deploy confirmclearvillagers` 确认。清除后需重新登记，否则无法开局（村民为必填项）。\n\n");

        md.append("### `/hilltop deploy confirmclearvillagers`\n\n");
        md.append("- **功能**: 确认清除所有村民。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop deploy confirmclearvillagers`\n");
        md.append("- **注意事项**: 此命令仅在 `/hilltop deploy clearvillagers` 执行后 15 秒内有效。\n\n");

        md.append("### `/hilltop setup`\n\n");
        md.append("- **功能**: 8 步引导式部署向导（世界 → 核心 → 出生点+刷怪点 → 节点 → 村民 → 商店 → 预览 → 完成）。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无（向导进行中可用 `goto <步骤>` 回跳）。\n");
        md.append("- **使用示例**: `/hilltop setup`\n");
        md.append("- **注意事项**: 需站在目标竞技场世界内执行；每步自动保存，可随时 [保存并返回大厅] 后回来继续。完成收尾时该竞技场自动加入启用队列（`enabled: true` 时）。一般通过聊天栏可点击按钮操作，无需手动输入子命令。\n\n");

        md.append("### `/hilltop manage`\n\n");
        md.append("- **功能**: 打开竞技场管理 GUI。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop manage`\n");
        md.append("- **注意事项**: 仅限玩家在游戏内使用。列表页展示全部已配置的竞技场（含已禁用、未部署、世界未加载的），点击进入详情页可查看生成点 / 能量节点 / 村民 / 商店 NPC 与对局参数；左键条目可传送到该坐标，右键删除单条，Shift+左键预览粒子。详情页还提供启用 / 禁用竞技场与「恢复初始状态」（重置）：重置会踢出场内玩家、清空该竞技场的全部部署数据与节点，并强制回滚地图方块，需要二次确认；对局进行中或正在启用队列加载中的竞技场会被拒绝，重置后因核心坐标被清空需重新部署才能再次启用。\n\n");

        md.append("### `/hilltop reloadconfig`\n\n");
        md.append("- **功能**: 重新加载游戏配置文件。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop reloadconfig`\n");
        md.append("- **注意事项**: 同时从 `game-settings.yml`（波次配置、怪物基础属性、锤子参数、节点设置等）与 `monsters-config.yml`（怪物实体类型、攻击伤害、装备与模型）重新加载配置，无需重启服务器即可使修改生效。竞技场参数来自 `arenas.yml`，需重启服务器才能生效。\n\n");

        md.append("### `/hilltop shop reload`\n\n");
        md.append("- **功能**: 重载商店配置文件 `shop.yml`。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: 无。\n");
        md.append("- **使用示例**: `/hilltop shop reload`\n");
        md.append("- **注意事项**: 无需重启即可刷新商店商品、价格与货币设置。\n\n");

        md.append("### `/hilltop shop give <玩家> [数量]`\n\n");
        md.append("- **功能**: 给在线玩家发放商店货币（能量水晶）。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: `<玩家>` — 在线玩家名；`[数量]` — 发放数量（默认 1，上限 6400）。\n");
        md.append("- **使用示例**: `/hilltop shop give Steve 64`\n");
        md.append("- **注意事项**: 这是服主手工发放货币的**唯一正确方式**。货币靠物品内的 PDC 标记识别，用原版 `/give` 发放或用铁砧改名得到的绿宝石**不会**被商店当作货币，无法用于购买。背包放不下的部分会掉落在目标玩家脚下。**任何世界均可用（含大厅）** —— 目标玩家在大厅时同样可以收到货币，可用于活动奖励、补偿等场景。\n\n");

        md.append("### `/hilltop wave clear [竞技场]`\n\n");
        md.append("- **功能**: 强制清除当前波所有存活怪物并结束该波（进入休整期）。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: `[竞技场]` — 可选，竞技场 ID（不填时优先按管理员所在世界解析）。\n");
        md.append("- **使用示例**: `/hilltop wave clear`, `/hilltop wave clear arena2`\n");
        md.append("- **注意事项**: 仅在波次进行中（RUNNING）有效；强制清除的怪物**不掉落货币、不计击杀统计**，并向全场广播说明。休整期执行会被拒绝并提示改用 `wave skip`。字段 `wave-timeout-seconds` 默认为 0（自动超时保险已禁用），本命令是卡怪时的手动兜底。\n\n");

        md.append("### `/hilltop wave skip [竞技场]`\n\n");
        md.append("- **功能**: 跳过休整期，立即开始下一波（一步到位）。\n");
        md.append("- **权限**: `hilltopvillage.admin`\n");
        md.append("- **参数**: `[竞技场]` — 可选，竞技场 ID（不填时优先按管理员所在世界解析）。\n");
        md.append("- **使用示例**: `/hilltop wave skip`, `/hilltop wave skip arena2`\n");
        md.append("- **注意事项**: 休整期直接开新波；波次进行中执行则先强制清波再立即开始下一波。人数低于该竞技场下限时拒绝（不判负）。越过最后一波时正常走胜利结算。\n\n");

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

        md.append("---\n\n");
        md.append("## 权限节点\n\n");
        md.append("| 权限节点 | 说明 |\n");
        md.append("|---|---|\n");
        md.append("| `hilltopvillage.admin` | 管理员权限，包含 start/stop/deploy/help/reloadconfig/enable/disable/arenas/hammer/fireball/build/tps/wave 等 |\n");
        md.append("| `hilltopvillage.config.admin` | 配置中心管理员权限，包含 config 主命令 |\n");
        md.append("| `hilltopvillage.config.rules` | 游戏规则配置权限 |\n");
        md.append("| `hilltopvillage.config.items` | 锤子与物品参数配置权限 |\n");
        md.append("| `hilltopvillage.config.nodes` | 能量节点配置权限 |\n");
        md.append("| `hilltopvillage.config.monsters` | 怪物属性配置权限 |\n");
        md.append("| `hilltopvillage.config.waves` | 波次组合配置权限 |\n\n");

        md.append("---\n\n");
        md.append("## 注意事项\n\n");
        md.append("1. **命令别名**: `/hilltop` 命令也可使用 `/hv` 或 `/htv` 作为别名。\n");
        md.append("2. **控制台限制**: 采用黑名单模型——仅以下命令不可由控制台执行：`join` / `leave` / `lobby` / `gui` / `hammer` / `fireball` / `setup` / `deploy` / `config` / `manage` / `build`。其余命令（含 `status` / `reloadconfig` / `start` / `stop` / `enable` / `disable` / `arenas` / `list` / `stats`（带玩家名） / `top` / `tps` / `help` / `lang` / `shop reload` / `shop give` / `wave clear` / `wave skip`）均可在控制台执行（管理命令仍需 OP / `hilltopvillage.admin` 权限）。\n");
        md.append("3. **部署流程**: 建议按照 `setcore → addspawn → addmobspawn → reloadnodes → start` 的顺序操作（玩家出生点与怪物刷怪点两套均需至少 1 个）。\n");
        md.append("4. **配置保存**: 使用 GUI 关闭菜单时配置会自动保存，`/hilltop reloadconfig` 用于手动重载外部编辑的配置。\n");
        md.append("5. **粒子展示**: `/hilltop deploy showparticles` 仅展示当前已设置的玩家出生点对应粒子。若没有出生点则不会有任何效果。\n");
        md.append("6. **多语言**: 使用 `/hilltop lang <en|zh>` 切换语言。所有消息文本可在 `plugins/HilltopVillage/messages.yml` 中自定义编辑。\n");

        final String content = md.toString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!docFile.getParentFile().exists()) {
                    docFile.getParentFile().mkdirs();
                }
                java.nio.file.Files.write(docFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
                plugin.getLogger().info("命令文档已生成: " + docFile.getAbsolutePath());
            } catch (IOException e) {
                plugin.getLogger().severe("无法生成命令文档 commands.md: " + e.getMessage());
            }
        });
    }
}
