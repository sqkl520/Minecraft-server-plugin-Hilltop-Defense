[English](README-EN.md) | 简体中文

> ⚠️ **这是一个初中生用AI做的插件，有任何问题建议欢迎提交Issues，如果您喜欢这个项目，就收藏关注一下吧！**

# Hilltop Village

一个面向 Minecraft 服务器的合作 PvE 塔防插件。玩家需要团结协作，保护村庄核心，抵御一波波不断增强的怪物进攻。

## 功能特性

- **合作塔防玩法** —— 多名玩家组队，守护村庄核心不被怪物摧毁
- **波次进攻系统** —— 怪物逐波来袭，难度递增，撑过全部波次即为胜利
- **神圣重锤** —— 右键腾空蓄力猛击，从高处砸向地面，造成三档范围伤害 + 粒子冲击波 + 余震减速，支持 ItemsAdder 自定义模型
- **烈焰蛋** —— 仿起床战争 Fireball，右键投掷、飞行焰火粒子轨迹、命中爆炸范围伤害 + 击退 + 点燃，冷却可配置
- **能量节点系统** —— 地图上信标/附魔台等特殊方块作为能量节点，为附近玩家提供抗性提升 + 生命恢复 Buff；节点可被怪物破坏，可用世界树汁液修复
- **特殊怪物 AI** —— 自爆甲虫（冲向节点自爆）、钩爪猎手（远程钩抓玩家传送 + 失明）、飞行抛投者（空降僵尸群），每种怪物可配盔甲/武器/自定义模型
- **图形化配置中心** —— 管理员通过 GUI 可视化配置游戏规则、波次组合、怪物属性、物品参数和能量节点，所见即所得
- **可点击命令菜单** —— 部署命令 `/hilltop deploy` 展示为聊天栏可点击菜单，点击自动填入命令
- **多语言支持** —— 内置中英文双语，玩家可自行切换 `/hilltop lang <en|zh>`
- **生成点粒子展示** —— 管理员部署生成点后，可触发 30 秒彩色粒子动画展示所有生成位置

## 命令

| 命令 | 说明 |
|------|------|
| `/hilltop join` | 加入游戏等待队列 |
| `/hilltop leave` | 退出游戏 |
| `/hilltop status` | 查看游戏状态（波次/玩家/怪物/节点） |
| `/hilltop hammer` | 获取神圣重锤 |
| `/hilltop fireball` | 获取烈焰蛋 |
| `/hilltop lang <en\|zh>` | 切换语言 |
| `/hilltop start` | 启动游戏（管理员） |
| `/hilltop stop` | 强制停止游戏（管理员） |
| `/hilltop help` | 查看命令帮助 |
| `/hilltop deploy` | 打开部署菜单（管理员） |
| `/hilltop config` | 打开配置中心 GUI（管理员） |
| `/hilltop reloadconfig` | 重载游戏配置文件（管理员） |

### 部署子命令 (`/hilltop deploy`)

| 命令 | 说明 |
|------|------|
| `setcore` | 将当前位置设为游戏核心 |
| `addspawn` | 添加当前位置为怪物生成点 |
| `listspawns` | 列出所有已设置的生成点 |
| `delspawn <id>` | 删除指定编号的生成点 |
| `clearspawns` | 清除所有生成点（需 `confirmclear` 确认） |
| `reloadnodes` | 重新扫描能量节点 |
| `showparticles` | 展示所有生成点粒子效果（30 秒） |

### 配置子命令 (`/hilltop config`)

| 命令 | 说明 |
|------|------|
| `nodes repairitem <材料>` | 设置节点修复物品 |
| `nodes add <材料>` | 添加节点方块类型 |
| `nodes remove <材料>` | 移除节点方块类型 |

## 权限

| 权限节点 | 默认 | 说明 |
|------|------|------|
| `hilltopvillage.player` | true | 普通玩家基础权限 |
| `hilltopvillage.admin` | OP | 管理员权限（包含所有子权限） |
| `hilltopvillage.config.admin` | OP | 配置中心 GUI 访问权限 |
| `hilltopvillage.config.rules` | false | 游戏规则配置权限 |
| `hilltopvillage.config.waves` | false | 波次配置权限 |
| `hilltopvillage.config.monsters` | false | 怪物属性配置权限 |
| `hilltopvillage.config.items` | false | 重锤/物品配置权限 |
| `hilltopvillage.config.nodes` | false | 能量节点配置权限 |

## 安装

1. 下载最新版本的 JAR 文件
2. 放入服务器的 `plugins/` 目录
3. 重启服务器或使用 `/plugman load HilltopVillage`
4. 编辑 `plugins/HilltopVillage/config.yml` 进行基础配置
5. 使用 `/hilltop deploy` 设置核心和生成点
6. 使用 `/hilltop config` 通过 GUI 调整游戏参数
7. 使用 `/hilltop start` 开始游戏

## 配置

主要配置项（`config.yml`）：

```yaml
language: "zh"

game:
  world-name: "world"
  min-players: 2
  max-players: 6
  lobby-wait-seconds: 60
  wave-interval-seconds: 30
  victory-waves: 20

nodes:
  block-types: [BEACON, ENCHANTING_TABLE, ENDER_CHEST, RESPAWN_ANCHOR]
  base-health: 100.0
  buff-radius: 20.0
  repair-cost-item: SLIME_BALL

spawning:
  spawn-radius-min: 20
  spawn-radius-max: 40
  mob-cap-per-player: 15
  global-mob-cap: 80
```

完整配置说明请参考 `plugins/HilltopVillage/config.yml` 和 `game-settings.yml`。

## 特殊怪物

| 怪物 | 实体类型 | 技能 |
|------|------|------|
| **自爆甲虫** | 洞穴蜘蛛 | 冲向能量节点自爆，造成大量节点伤害 |
| **钩爪猎手** | 骷髅 | 远程射出钩爪，将玩家传送到身边并施加失明+缓慢 |
| **飞行抛投者** | 幻翼 | 飞至玩家上方空投僵尸群 |

## 依赖

- Paper 1.19+
- Java 17+
- ItemsAdder（可选，用于自定义物品/怪物模型）

## 构建

```bash
mvn clean package
```

JAR 文件将生成在 `target/` 目录。

## 许可证

本项目采用 [PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0/)。

任何使用、修改或分发，都必须保留项目根目录下的 `LICENSE` 和 `NOTICE` 文件，并确保其内容完整。

## Star History

<a href="https://www.star-history.com/?repos=sqkl520%2FMinecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left" />
 </picture>
</a>