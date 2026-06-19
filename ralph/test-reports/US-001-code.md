# 代码质量报告 US-001
### 判定：FAIL
| # | 维度 | 位置 | 原因 |
|---|------|------|------|
| 1 | i18n | TpsDiagnostic.java:25-72 | `printDiagnostic()` 中所有用户可见消息均为硬编码中文 (`ChatColor.GOLD + "=== TPS 诊断 ==="` 等)，`LanguageManager lang` 字段可用但未被使用。共涉及约 20 处硬编码消息。 |

---

## 轮次 2 — 重测 1.1.0 新建文件

审查范围：TpsDiagnostic.java + HilltopCommandHandler.java + HilltopTabCompleter.java + DeployMenuSender.java + CommandsDocumentGenerator.java + HammerEffectRenderer.java

### 判定：PASS
- **配置读取路径** ✅ — 全部通过构造函数注入依赖，无 `plugin.getConfig()` 直接调用
- **i18n 覆盖** ✅ — TpsDiagnostic 所有消息改用 `msg(sender, "tps.xxx")` 经过 LanguageManager；其余文件用户可见消息均通过 `lang.getFor()` 解析。CommandsDocumentGenerator 写入磁盘的行为文档文件，非游戏内用户消息，不计入 i18n 违规
- **线程安全** ✅ — 无共享可变状态或已使用恰当并发容器
- **资源清理** ✅ — 不涉及监听器注册或定时器创建

所有 1.1.0 新建文件硬编码中文已清除。
