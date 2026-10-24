# 部署阶段 1 / 2 / 2.5：准备世界、配置竞技场、进入竞技场世界

本文由 README 对应章节下移而来，README 侧保留摘要与入口链接。

从零到开一局，共 6 个阶段。**首次部署请按顺序执行**。本文是**阶段 1**、**阶段 2**、**阶段 2.5** 三段的完整版：准备世界、配置竞技场、以及第一次部署时怎么把自己弄进竞技场世界。阶段 3（部署向导）的逐步骤说明见 `setup-wizard.md`，阶段 4—6（调参、自检、开局）仍在 README 的「从零到开一局：6 个阶段」一节。

## 阶段 1：准备世界

插件采用「大厅 + 竞技场」分离架构（多竞技场模式），至少需要 2 个世界：

| 世界 | 作用 | 说明 |
|------|------|------|
| **大厅世界** | 玩家进服等待、浏览菜单 | 例如 `lobby` |
| **竞技场世界** | 实际对局地图 | 例如 `hilltop_arena1`，每个竞技场一个独立世界 |

竞技场世界可以用 WorldEdit 复制、Multiverse 或直接复制世界文件夹创建。

> 大厅与竞技场**必须是不同世界** —— 插件据此区分保护规则与游戏逻辑。

### 关于 `server.properties` 的 `level-name`

`level-name` 指定**服务器根目录下哪个文件夹被加载为主世界**（默认 `world`）。名字不匹配时服务器会新建一个世界，而不是加载你想要的那个。

Paper 启动时**只自动加载这一个世界**，其他世界即使文件夹存在也不会加载。

**本插件会自动加载缺少的世界**：只要世界文件夹（含 `level.dat`）存在于服务器根目录，插件启动时会用 `Bukkit.createWorld` 加载它（与 BedWars1058 的做法一致）。因此：

- **推荐**：把 `level-name` 设为你的**大厅世界名**，玩家进服直接落在大厅，无传送闪烁
  ```properties
  level-name=lobby
  ```
- **也可以不改**：插件会加载 `arenas.yml` 里引用的竞技场/大厅世界
- **大厅兜底**：若 `lobby.world` 留空或指向不存在的世界，插件自动回退到服务器主世界（`level-name` 世界），保证开箱可用

## 阶段 2：配置竞技场

安装插件后，编辑 `plugins/HilltopVillage/arenas.yml`：

```yaml
default-arena: arena1

lobby:
  world: world          # 大厅世界名
  spawn:                # 大厅出生点（玩家进服落点）
    x: 0.5
    y: 100.0
    z: 0.5
    yaw: 0.0
    pitch: 0.0

arenas:
  arena1:
    display-name: "&6&l天空堡垒"
    enabled: true
    world: hilltop_arena1        # 竞技场世界（与大厅不同）
    waiting-location:            # 等待区（玩家加入后、开局前的落点）
      x: 100.5
      y: 80.0
      z: 100.5
    core:                        # 核心位置（玩家重生点）
      x: 100.0
      y: 64.0
      z: 100.0
    spawn-points: []             # 玩家出生点（用部署向导填，见阶段 3）
    mob-spawn-points: []         # 怪物刷怪点（与出生点分离的第二套坐标，同样用向导/命令填）
    game:
      min-players: 2
      max-players: 6
      victory-waves: 20
      wave-interval-seconds: 30
```

> 生效方式：**重启服务器**（`/hilltop reloadconfig` 不重读 arenas.yml；例外：管理 GUI 改 per-arena 参数即时生效，见 arena-lifecycle.md）。

## 阶段 2.5：进入竞技场世界

本插件**没有**传送进竞技场世界的命令（`setup`、`deploy` 等部署命令都要求你人已经站在目标世界里），所以第一次部署时，先用下面任一方式**自己传过去**：

| 方式 | 命令 | 说明 |
|------|------|------|
| 原版传送（需 OP） | `/execute in hilltop_arena1 run tp @s 100 64 100` | 把 `hilltop_arena1` 换成你的竞技场世界名；坐标换成地图中央。第一次用建议先飞起来（`/gamemode creative`）再传，免得掉进没加载好的区块 |
| Multiverse（如已安装） | `/mvtp hilltop_arena1` | 传到该世界的出生点 |
| 跨服传送插件（如已安装 MyWarp 等） | 按该插件用法 | 在竞技场世界设个地标，以后部署多个场都方便 |

> 传过去之后建议先飞一圈确认两件事：① 世界确实是你准备的那张地图（别传错世界）；② 当前模式是创造（`/gamemode creative`），后面放重生锚、摆村民、调地形都要用。向导第 1 步会显示当前所在世界名，对不上就点取消重传。

> 注：阶段 3（运行部署向导）的完整逐步骤说明见同目录的 `setup-wizard.md`；阶段 3 的 8 步简表与阶段 4—6 仍在 README 的「从零到开一局：6 个阶段」一节。
