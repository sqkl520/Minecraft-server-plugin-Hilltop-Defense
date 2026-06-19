# 代码质量报告 gui-layout-code-r2

### 判定：FAIL

| # | 维度 | 位置 | 原因 |
|---|------|------|------|
| 1 | i18n | WaveSettingsMenu.java:65 | 硬编码英文字符串 `" (Weight:"` 直接拼接在 lore 中，未通过 `lang.getFor()` 读取 |
| 2 | i18n | WaveSettingsMenu.java:569 | `addStrItem` 方法中硬编码英文后缀 `" \| leave blank to clear"`，直接字符串拼接到 prompt 参数 |
| 3 | i18n | MainConfigMenu.java:144 | 权限信息行硬编码分隔符 `" - "` 拼接在 `ChatColor.DARK_GRAY` 后，属于用户可见的格式字符串 |
