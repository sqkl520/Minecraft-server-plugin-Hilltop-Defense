# 村民商店自定义（shop.yml 全字段）

本文由 README 对应章节下移而来，README 侧保留摘要与入口链接。

## 商店掉落与货币口径

本节先给出购买入口与掉落口径（这一段与 README 的「水晶怎么来、怎么花」节同源，两处并存便于本文单独阅读）：

击杀波次怪物**每次击杀必掉 1~2 个能量水晶**（货币；`game-settings.yml` 中掉落概率门 `shop.currency-drop-chance` 默认 1.0 = 必掉，掉落数量区间由 `shop.currency-drop-min` / `shop.currency-drop-max` 控制、默认 1~2，均可调）。空投僵尸**不掉币**（反挂机设计）。**掉落按怪种区分**：自爆甲虫 1 个、钩爪猎手 1~2 个、飞行抛投者 2~3 个（越难打的怪越值）——可在 `game-settings.yml` 的 monsters 段按怪配置 `drop-min` / `drop-max`（写 -1 回退全局值，写 0/0 即该怪不掉）。结算战报、侧边栏与 `/hilltop stats`、`/hilltop top crystals` 会显示本局/历史获取水晶数。走到商店村民旁**右键**即可打开商店 GUI，左键商品图标完成购买。

**经济闭环**：怪物掉能量水晶 → 右键商店村民购买道具/世界树汁液 → 汁液右键失活节点修复 → 节点重新提供 Buff 帮助守村。

## 商店村民部署

**商店村民部署**：

- 部署向导第 6 步（`/hilltop setup`）走到目标位置点 [设置商店位置]（必填，未设置无法完成部署）；设置后停留本步，可随时再点 [设置商店位置] 覆盖位置，或点 [清除商店位置] 删除重来（清除后留在本步），准备好后点 [继续] 进入预览
- 也可用命令：`/hilltop deploy setshop` 设当前位置并立即生成 NPC、`/hilltop deploy clearshop` 清除本竞技场的商店（位置条目 + 实体）；`/hilltop manage` 详情页右键商店条目同样可清除
- 清除入口在**对局进行中会被拒绝**（商店 NPC 是插件级单例，清除会当场撤掉场内玩家正在使用的商店），请先 `/hilltop stop`
- 位置按竞技场持久化在 `arenas.yml` 的 `arenas.<id>.shop-npc` 节点，服务器重启自动恢复；启动时自动清理崩溃残留的重复实体
- 商店村民无敌（任何伤害无效）、不可推动交互，仅作为商店入口
- 朝向只保留水平方向：部署时只取玩家的 yaw、pitch 归零（村民不低头/抬头，旧存档里写坏的低头角度也会在重启恢复时自动纠正）；生成后还会水平转身看向 8 格内最近的玩家

## 默认商品构成

商店默认 16 件商品（54 格 GUI，分区摆放）：工具/消耗品区（神圣重锤 5 / 烈焰蛋×8 3 / 世界树汁液×4 2 / **弓 15 / 箭×8 2**——新增弓箭线，弥补无远程手段打飞行抛投者的缺口）、武器区（**铁剑 8 / 钻石剑 25 / 下界合金剑 48**）、护甲区（**铁套散件各 6 / 钻石套散件各 14**，散件按件卖）。装备全部为**原版白板**（无附魔——附魔计划下个版本加入）。**开局自动发放皮革四件套**（连同石剑/面包/火把，见 `game-settings.yml` 的 `starter-kit` 段）。

## shop.yml 自定义商品（全样例）

**shop.yml 自定义商品**（`plugins/HilltopVillage/shop.yml`）：

```yaml
shop:
  currency:
    material: EMERALD      # 货币物品材质
    name: "&a能量水晶"      # 货币显示名（支持颜色码）
  npc:
    name: "&6商店村民"      # NPC 头顶名称
  gui:
    title: "&8村民商店"      # GUI 标题（留空走语言文件 shop.gui.title）
    size: 54               # GUI 尺寸（9 的倍数，最大 54；默认 54 = 6 行）
  items:
    sacred_hammer:
      slot: 10             # GUI 槽位号（0 ~ size-1）
      price: 5             # 价格（能量水晶数量）
      type: HAMMER         # HAMMER / FIREBALL / REPAIR_ITEM / MATERIAL / EQUIPMENT
      amount: 1            # 一次购买给的数量（HAMMER 固定 1，忽略）
      icon:
        material: NETHERITE_AXE
      name: "&6神圣重锤"     # 显示名（支持颜色码）
      lore:                # lore 行（价格行自动追加）
        - "&7右键腾空蓄力，落地猛击"
    iron_sword:
      slot: 11             # 装备商品示例
      price: 8
      type: EQUIPMENT      # 装备：按 icon.material 发 1 件到手里
      icon:
        material: IRON_SWORD # EQUIPMENT 必填：既是图标也是发货材质
      name: "&f铁剑"         # name/lore 会写到发到手里的装备上
      lore:
        - "&7比石剑更能打——第一件升级武器"
      unbreakable: false   # 可选：true = 不可破坏（不配 = 普通耐久）
```

## type 五种取值与生效方式

- 所有商品条目、价格、名称、耐久都在 `shop.yml` 自定义；**服主可自由增删商品、改价格改槽位**
- `type` 五种取值：`HAMMER`（神圣重锤，无视 amount 固定 1 个）/ `FIREBALL`（烈焰蛋）/ `REPAIR_ITEM`（世界树汁液）/ `MATERIAL`（任意原版物品，按 `icon.material` 给 amount 个）/ `EQUIPMENT`（装备，按 `icon.material` 发 1 件；`icon.material` **必填** = 发货材质，`name` / `lore` 可选但会原样写到发货物上，`unbreakable` 可选 = 不可破坏）
- 修改后执行 `/hilltop shop reload`（管理员）无需重启即可生效
- 防刷保护：商店 GUI 内所有点击/拖拽一律取消（拿不走商品图标），购买有 250ms 冷却防连点重复扣费
- 货币防伪：能量水晶靠物品内的 PDC 标记识别，**用铁砧 / 命名牌把普通绿宝石改成货币名不会被当成钱**。服主手工发币请用 `/hilltop shop give <玩家> [数量]`（原版 `/give` 发的绿宝石没有标记，商店不认）

## 存量服务器升级提示

> **存量服务器升级提示**：`shop.yml` 是"**文件存在即不释放模板**"——升级到本版后，若服务器上已有旧 `shop.yml`，**新商品（弓/箭/剑/护甲等）不会自动出现**。请用新版模板更新服务器的 `shop.yml`（手动补入商品条目，或备份旧文件后删除让其重新生成再改回自定义项），然后执行 `/hilltop shop reload`。
