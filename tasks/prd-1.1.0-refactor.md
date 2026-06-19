# PRD: Hilltop Village 1.1.0 — 重构解耦

## Introduction

将 `HilltopVillagePlugin`（875行）按职责拆分为 6 个独立类，提取 `HammerEffectRenderer`，为 `ConfigManager` 引入注解驱动映射。  
目标：主类降至 ~300 行，每个类单一职责，便于维护和测试。

## Goals

- 主插件类仅保留生命周期管理和 DI，命令/补全/菜单/诊断/文档全部独立
- 重锤粒子特效独立为可复用的 `HammerEffectRenderer`
- `ConfigManager.saveToFile()/loadFromFile()` 不再需要逐字段手写，改为注解驱动
- NodeSystem 事件监听注册逻辑统一

## User Stories

---

### US-001: 提取 HilltopCommandHandler — 命令路由

**Description:** 作为开发者，我希望命令处理逻辑从主插件类中分离出来，让主类专注于生命周期管理。

**Acceptance Criteria:**
- [ ] 新建 `command/HilltopCommandHandler.java`
- [ ] 从 `HilltopVillagePlugin.onCommand()` 迁移所有 switch-case 路由逻辑
- [ ] 迁移以下私有方法：`handleStart`, `handleStop`, `handleJoin`, `handleLeave`, `handleStatus`, `handleHammer`, `handleFireball`, `handleHelp`, `handleDeployCommand`, `handleConfigCommand`, `handleLangCommand`, `handleTpsCommand`
- [ ] 构造函数接收：`HilltopVillagePlugin plugin`，通过 getter 获取 GameManager 等依赖
- [ ] `HilltopVillagePlugin.onCommand()` 委托给 `commandHandler.onCommand(sender, command, label, args)`
- [ ] 编译通过（`mvn clean compile`）

---

### US-002: 提取 HilltopTabCompleter — 命令补全

**Description:** 作为开发者，我希望 Tab 补全逻辑独立为一个类，权限过滤逻辑清晰可测。

**Acceptance Criteria:**
- [ ] 新建 `command/HilltopTabCompleter.java`（`implements TabCompleter`）
- [ ] 从 `HilltopVillagePlugin.onTabComplete()` 迁移所有补全逻辑
- [ ] 保留权限感知过滤（admin 命令对有权限者才显示）
- [ ] Deploy 子命令（`args.length == 2`）和 lang 子命令的补全逻辑
- [ ] `HilltopVillagePlugin` 中改为 `getCommand("hilltop").setTabCompleter(tabCompleter)`
- [ ] 编译通过（`mvn clean compile`）

---

### US-003: 提取 DeployMenuSender — 部署菜单 + 可点击命令

**Description:** 作为开发者，我希望部署菜单和可点击命令的发送逻辑独立为一个工具类。

**Acceptance Criteria:**
- [ ] 新建 `command/DeployMenuSender.java`
- [ ] 迁移以下方法：`sendDeployMenu()`, `sendUsage()`, `sendClickableLine()`（两个重载版本）
- [ ] 构造函数接收 `LanguageManager` 实例
- [ ] 所有方法签名保持不变（或仅改为非 private）
- [ ] `HilltopCommandHandler` 和 `SetupWizard` 均可复用此类的发送方法
- [ ] 编译通过（`mvn clean compile`）

---

### US-004: 提取 TpsDiagnostic — TPS 诊断命令

**Description:** 作为开发者，我希望 TPS 诊断逻辑独立为一个类，便于单独测试格式化和告警逻辑。

**Acceptance Criteria:**
- [ ] 新建 `diagnostic/TpsDiagnostic.java`
- [ ] 从 `HilltopVillagePlugin` 迁移 `handleTpsCommand()` 和 `formatTps()` 方法
- [ ] 构造函数接收 `GameManager` 和 `LanguageManager`
- [ ] 保留世界实体/区块/方块实体遍历、内存统计、MSPT 告警
- [ ] `HilltopCommandHandler` 委托 TPS 命令到 `tpsDiagnostic.handle(sender)`
- [ ] 编译通过（`mvn clean compile`）

---

### US-005: 提取 CommandsDocumentGenerator — 命令文档自动生成

**Description:** 作为开发者，我希望 commands.md 的自动生成逻辑独立为一个类。

**Acceptance Criteria:**
- [ ] 新建 `doc/CommandsDocumentGenerator.java`
- [ ] 从 `HilltopVillagePlugin.generateCommandsDocument()` 迁移全部逻辑
- [ ] 构造函数接收 `Plugin` 实例（用于 `getDataFolder()`）
- [ ] 保留 UTF-8 编码写入、目录自动创建、日志输出
- [ ] `HilltopVillagePlugin.onEnable()` 中改为 `new CommandsDocumentGenerator(this).generate()`
- [ ] 编译通过（`mvn clean compile`）

---

### US-006: 提取 HammerEffectRenderer — 重锤粒子特效

**Description:** 作为开发者，我希望三档重锤的粒子特效代码从 HammerListener 中分离出来，便于独立调整粒子效果。

**Acceptance Criteria:**
- [ ] 新建 `mechanics/HammerEffectRenderer.java`
- [ ] 从 `HammerListener.java`（约第 360-480 行）迁移三档特效方法：
  - `spawnLowTierEffect(Location location)` — 尘土+烟雾
  - `spawnMediumTierEffect(Location location)` — 爆炸+双环+碎石+火花
  - `spawnHighTierEffect(Location location)` — 巨型爆炸+三层环+闪电+龙息+火焰旋涡
- [ ] 所有方法为 `public static`
- [ ] `HammerListener.executeSmash()` 中改为调用 `HammerEffectRenderer.spawnXxxTierEffect(loc)`
- [ ] 编译通过（`mvn clean compile`）

---

### US-007: 统一 NodeSystem 事件监听注册

**Description:** 作为开发者，我希望 NodeSystem 不再直接持有对 `config.yml` 的引用（在 1.0.9 统一配置读取后，彻底清理遗留引用）。

**Acceptance Criteria:**
- [ ] 审查 `NodeSystem` 类中是否还有对 `plugin.getConfig()` 的直接调用
- [ ] 如有，改为通过 `ConfigManager.getActiveConfig()` 获取
- [ ] NodeListener 的注册逻辑确认已在 `HilltopVillagePlugin.onEnable()` 中明确定义
- [ ] NodeSystem 不再直接注册事件监听器（统一由主插件类管理）
- [ ] 编译通过（`mvn clean compile`）

---

## Functional Requirements

- FR-1: 主插件类从 875 行降至约 300 行，仅保留 `onEnable/onDisable` 生命周期 + getter DI
- FR-2: 命令路由、Tab 补全、部署菜单、TPS 诊断、命令文档生成各自独立为一个类
- FR-3: 重锤三档粒子特效独立为静态工具类
- FR-4: NodeSystem 不直接持有 config.yml 引用

## Non-Goals

- 不引入注解驱动 ConfigManager（工作量过大，影响面广，本版本仅做简单提取重构）
- 不修改任何业务逻辑行为
- 不新增功能
- 不添加单元测试（留给 1.2.0）

## Technical Considerations

- 所有新类放在现有包结构中：`command/`, `diagnostic/`, `doc/`, `mechanics/`
- 主插件类的 getter 方法保持不变，确保其他模块（如 GameManager）的 DI 不受影响
- `DeployMenuSender` 需要能被 `HilltopCommandHandler` 和 `SetupWizard`（1.0.9 新增）共同使用
- 重构过程必须保持编译通过 — 每完成一个 Story 就编译验证一次

## Success Metrics

- `HilltopVillagePlugin.java` ≤ 350 行
- `HammerListener.java` ≤ 400 行
- 所有拆分出的新类有明确的单一职责
- 编译通过，功能行为与重构前一致
