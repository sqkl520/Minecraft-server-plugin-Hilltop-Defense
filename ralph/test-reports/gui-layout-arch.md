# 架构报告 gui-layout-arch
### 判定：PASS

## 审查范围

| 文件 | 变更摘要 |
|------|---------|
| AbstractConfigMenu.java | setBackButton slot 改为 8；setCloseButton 改为 no-op |
| MainConfigMenu.java | size 统一 54，删 Close 调用 |
| GameRulesMenu.java | size 统一 54，删 Close 调用 |
| WaveSettingsMenu.java | size 统一 54，删 Close 调用 |
| NodeSettingsMenu.java | size 统一 54，删 Close 调用 |
| MonsterSettingsMenu.java | size 统一 54，删 Close 调用 |
| ItemSettingsMenu.java | size 统一 54，删 Close 调用 |

## 维度一：分层正确性

全部文件位于 `com.hilltopvillage.config.menu`，符合"配置 GUI"层规范。无越层引用。

## 维度二：单一职责

| 文件 | 行数 | 状态 |
|------|------|------|
| AbstractConfigMenu.java | ~186 | OK |
| MainConfigMenu.java | ~164 | OK |
| GameRulesMenu.java | ~127 | OK |
| NodeSettingsMenu.java | ~111 | OK |
| ItemSettingsMenu.java | ~272 | OK |
| MonsterSettingsMenu.java | ~350 | WARNING（超 300 行） |
| WaveSettingsMenu.java | ~632 | WARNING（超 300 行，含 4 个内部静态子菜单，属有意聚合） |

两处警告均不构成 FAIL。MonsterSettingsMenu 超出源于三页 tab 内容；WaveSettingsMenu 超出源于 WaveDetailMenu / AddMonsterMenu / MonsterPropMenu 三个内部类的聚合，架构意图明确。

## 维度三：DI 注入完整性

- 所有子菜单通过构造函数注入 `ConfigManager`、`LanguageManager`、`parent`。
- `plugin` 字段统一由 `HilltopVillagePlugin.getPlugin()` 静态获取，与项目既有惯例一致。
- `import org.bukkit.plugin.Plugin` 在所有需要该类型的文件中均存在（7 个文件均已确认）。
- `setCloseButton` 调用已在所有子类中彻底移除，AbstractConfigMenu 保留空实现，无残留调用。
- `setBackButton` 调用存在于全部 5 个子菜单（GameRules / Node / Monster / Item / Wave），slot 统一由 AbstractConfigMenu 第 111 行硬编码为 8，实现一致。

## 维度四：事件注册

- 所有菜单类采用动态注册模式：`open()`/`reopen()` 时按需注册，`onClose` 事件处理器中调用 `HandlerList.unregisterAll(this)` 自动注销。
- `onEnable()` 中无需静态注册 GUI 菜单，现有注册列表（hammerListener / nodeListener / fireballListener / NumericInputHandler）覆盖完整。
- `MainConfigMenu` 无新增命令，`plugin.yml` 无需变更。

## 结论

四个维度零违规。两处行数警告（MonsterSettingsMenu、WaveSettingsMenu）不影响判定。
