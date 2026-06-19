# 代码质量报告 gui-layout

### 判定：FAIL

| # | 维度 | 位置 | 原因 |
|---|------|------|------|
| 1 | i18n | WaveSettingsMenu.java:73 | `ChatColor.GREEN + "Wave " + waveNum` — 硬编码英文标签，应通过 lang key 解析 |
| 2 | i18n | WaveSettingsMenu.java:195 | `ChatColor.RED + "Wave config not found!"` — 硬编码错误消息（WaveDetailMenu.buildMenu） |
| 3 | i18n | WaveSettingsMenu.java:357 | `ChatColor.GRAY + "HP: "` — AddMonsterMenu lore 硬编码英文字段标签 |
| 4 | i18n | WaveSettingsMenu.java:358 | `ChatColor.GRAY + " \| Speed: "` — 同上 |
| 5 | i18n | WaveSettingsMenu.java:359 | `ChatColor.GRAY + "Damage: "` — 同上 |
| 6 | i18n | WaveSettingsMenu.java:441 | `ChatColor.RED + "Monster config not found!"` — 硬编码错误消息（MonsterPropMenu.buildMenu） |
| 7 | i18n | WaveSettingsMenu.java:463 | `"Enter namespace ID (e.g. myplugin:boss)"` — MonsterPropMenu requestString prompt 硬编码英文 |
| 8 | i18n | WaveSettingsMenu.java:472 | `"Click to cycle weapon"` — addMaterialCycle lore hint 硬编码英文 |
| 9 | i18n | WaveSettingsMenu.java:474 | `"Click to cycle"` — 同上，off-hand cycle hint 硬编码英文 |
| 10 | i18n | WaveSettingsMenu.java:615 | `"Cycle: Leather/Iron/Diamond/Netherite/Gold/None"` — getArmorHint() 方法直接返回硬编码英文 |
| 11 | i18n | MonsterSettingsMenu.java:102 | `"Enter ItemsAdder namespace ID\n(e.g. myplugin:boss, leave blank to clear)"` — requestString prompt 硬编码英文 |
| 12 | i18n | MonsterSettingsMenu.java:140 | `"Enter offhand Material name\n(e.g. SHIELD, leave blank to clear)"` — requestString prompt 硬编码英文 |
| 13 | i18n | MonsterSettingsMenu.java:245 | `"Enter " + label + " Material name\n(e.g. LEATHER_HELMET, leave blank to clear)"` — addArmorSlot prompt 硬编码英文 |
| 14 | i18n | NodeSettingsMenu.java:58-59 | `ChatColor.AQUA + "/hilltop config nodes add <material>"` / `"/hilltop config nodes remove <material>"` — 用户可见命令提示文本硬编码 |
| 15 | i18n | MainConfigMenu.java:144 | `ChatColor.WHITE + " — "` — 权限说明 lore 混入硬编码破折号分隔符，整行应通过 lang key 构建 |
