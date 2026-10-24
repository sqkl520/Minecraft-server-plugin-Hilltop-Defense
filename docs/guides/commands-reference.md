# 完整命令参考

本文由 README 对应章节下移而来，README 侧保留摘要与入口链接。

## 命令总表

> 「控制台」列：✅ = 可在服务器控制台执行（管理命令仍需 OP / `hilltopvillage.admin` 权限，即控制台默认拥有）；
> ❌ = 必须由玩家在游戏内执行。

| 命令 | 说明 | 控制台 |
|------|------|--------|
| `/hilltop join [竞技场]` | 加入竞技场（不填则自动匹配可用竞技场） | ❌ |
| `/hilltop leave` | 退出当前竞技场 | ❌ |
| `/hilltop lobby` | 返回大厅（子命令 `setspawn`：站在大厅世界内执行，把当前位置设为大厅出生点并立即生效，管理员） | ❌ |
| `/hilltop gui` | 打开竞技场选择菜单（带状态与人数） | ❌ |
| `/hilltop status [竞技场]` | 查看游戏状态（波次/玩家/怪物/节点/等待复活） | ✅ |
| `/hilltop stats [玩家名]` | 查看自己或他人的统计 | ✅（带玩家名时） |
| `/hilltop top <类型>` | 查看排行榜（kills/wins/games/best-wave/nodes/deaths/crystals） | ✅ |
| `/hilltop hammer` | 获取神圣重锤（管理员；玩家请去商店购买） | ❌ |
| `/hilltop fireball` | 获取烈焰蛋（管理员；玩家请去商店购买） | ❌ |
| `/hilltop lang <en\|zh>` | 切换语言（普通玩家改自己的会话语言；管理员/控制台改全服默认并落盘） | ✅ |
| `/hilltop start [竞技场]` | 启动游戏（管理员） | ✅ |
| `/hilltop stop [竞技场]` | 强制停止游戏（管理员） | ✅ |
| `/hilltop wave clear [竞技场]` | 强制清除当前波所有怪物并结束本波（进入休整期；被清怪物不产生掉落、不计击杀）（管理员 `hilltopvillage.admin`） | ✅ |
| `/hilltop wave skip [竞技场]` | 跳过休整期立即开始下一波（RUNNING 时等效"清波 + 跳过"一步到位；人数不足会拒绝且不判负）（管理员 `hilltopvillage.admin`） | ✅ |
| `/hilltop help` | 查看命令帮助（所有玩家可用；内容按权限分档） | ✅ |
| `/hilltop list` | 列出所有竞技场 | ✅ |
| `/hilltop arenas` | 查看详细竞技场状态与故障原因（管理员） | ✅ |
| `/hilltop manage` | 打开竞技场管理 GUI：查看任意竞技场、删除单个节点/出生点/刷怪点/村民/商店（详情页右键商店条目）、编辑游戏参数、恢复初始状态（管理员） | ❌ |
| `/hilltop enable [竞技场]` | 将竞技场加入启用队列（管理员） | ✅ |
| `/hilltop disable [竞技场]` | 禁用竞技场用于维护（管理员） | ✅ |
| `/hilltop deploy` | 打开部署菜单（管理员） | ❌ |
| `/hilltop setup` | 8 步引导式部署向导（管理员） | ❌ |
| `/hilltop config` | 打开配置中心 GUI（管理员） | ❌ |
| `/hilltop reloadconfig` | 重载游戏配置（game-settings.yml + monsters-config.yml + messages.yml）（管理员；arenas.yml 仍需重启） | ✅ |
| `/hilltop shop reload` | 重载村民商店配置（管理员） | ✅ |
| `/hilltop shop give <玩家> [数量]` | 给在线玩家发放商店货币（能量水晶，默认 1、上限 6400）（管理员；任何世界均可用，含大厅） | ✅ |
| `/hilltop build` | 切换大厅建造模式（管理员，用于整修大厅） | ❌ |
| `/hilltop tps` | TPS/MSPT 诊断（管理员） | ✅ |

## 部署子命令 (`/hilltop deploy`)

| 命令 | 说明 |
|------|------|
| `confirmclear` | 确认清除所有生成点（`clearspawns` 的二次确认） |
| `setcore` | 将当前位置设为游戏核心 |
| `addspawn` | 添加当前位置为玩家出生点 |
| `listspawns` | 列出所有已设置的玩家出生点 |
| `delspawn <id>` | 删除指定编号的玩家出生点 |
| `clearspawns` | 清除所有玩家出生点（需 `confirmclear` 确认） |
| `addmobspawn` | 添加当前位置为怪物刷怪点 |
| `listmobspawns` | 列出所有已设置的怪物刷怪点 |
| `delmobspawn <id>` | 删除指定编号的怪物刷怪点 |
| `clearmobspawns` | 清除所有怪物刷怪点（需 `confirmclearmob` 确认） |
| `reloadnodes` | 从 nodes.yml 重新读取节点注册表（不扫描方块；节点注册用向导第 4 步 [添加节点]） |
| `showparticles` | 展示所有玩家出生点的粒子效果（30 秒，绿色光柱） |
| `addvillager` | 将当前位置登记为村民位置（可多次，村民全灭 = 失败） |
| `listvillagers` | 列出所有已登记的村民 |
| `delvillager <id>` | 删除指定编号的村民 |
| `clearvillagers` | 清除所有村民 |
| `setshop` | 将当前位置设为商店 NPC 位置并立即生成 NPC |
| `clearshop` | 清除本竞技场的商店 NPC（位置条目 + 实体；对局中拒绝，需先 `stop`） |

## 手动命令速查（熟手）

不走向导、纯命令完成部署时的顺序参考（熟手）：

```
/hilltop deploy setcore        # 站在核心位置执行
/hilltop deploy addspawn       # 站在玩家出生点位置执行（可多次）
/hilltop deploy listspawns     # 查看已设玩家出生点
/hilltop deploy addmobspawn    # 站在怪物刷怪点位置执行（可多次）
/hilltop deploy listmobspawns  # 查看已设怪物刷怪点
/hilltop deploy showparticles  # 粒子效果确认位置
/hilltop deploy reloadnodes    # 重新加载节点配置
/hilltop deploy addvillager    # 站在村民位置执行（可多次）
/hilltop deploy listvillagers  # 查看已登记村民
/hilltop deploy setshop        # 站在商店位置执行（立即生成商店 NPC）
/hilltop deploy clearshop      # 清除本竞技场的商店（对局中会拒绝）
```

开局两条：

```
/hilltop join      # 玩家加入（或 /hilltop gui 打开选择菜单）
/hilltop start     # 管理员开局（需满足 min-players）
```

## 配置子命令 (`/hilltop config`)

| 命令 | 说明 |
|------|------|
| `nodes repairitem <材料>` | 设置节点修复物品 |

## 部署向导子命令的去处

- 部署向导 `/hilltop setup` 的子命令全表（16 条，含已废弃的 `rescan`）在 `setup-wizard.md`
- 权限节点（7 个）与命令的控制台可用性口径见 README 的权限一节
