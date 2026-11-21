[English](CONTRIBUTING-EN.md) | 简体中文

# 为 Hilltop Village 贡献

感谢你愿意花时间帮忙。本文件描述的是**本仓库当前实际可用**的流程，而不是通用模板。

## 开始之前

- 需要 **Java 21** 与 **Maven 3.9.9**。构建会强制校验 JDK 21，版本过低会直接停下并给出可读的报错。
- 目标平台是 **Paper 1.21.4**。只在其他分支或其他 Minecraft 版本上生效的特性不在范围内。
- 除 Paper API 外，项目**没有运行时依赖**。若想引入 shaded / packet 库，请先开 issue 讨论。

## 运行构建

最省事的做法是用仓库 `tools/` 目录下的包装脚本，它会自动找到机器上的 JDK 21，并把所有参数原样透传给 Maven：

```bash
./tools/build.sh                       # 等价于：mvn clean package
./tools/build.sh test                  # 只跑测试
./tools/build.sh -DskipTests clean package
```

`tools/build.sh` 在 Windows 上需要 Git Bash / MSYS。如果你的目录布局不同，可用 `HV_JDK21_HOME`、
`HV_JDK21_GLOB` 或 `HV_MAVEN` 覆盖。

如果你更愿意直接调用 Maven，请先导出 JDK 21 —— 许多机器上默认 shell 的
`JAVA_HOME` 指向 JDK 17，构建会拒绝运行：

```bash
export JAVA_HOME="/path/to/jdk-21"
mvn clean package
```

## 测试

测试套件目前有 **991 个测试**（`0 failures`，`1 skipped`）。被跳过的那个是
仅在 Windows 上才会触发的只读目录用例，属于设计内跳过。

> 本仓库的构建**不能**在同一工作树里与另一个 Maven 构建并发运行 —— `clean` 会把另一个
> 构建正在使用的 `target/` 删掉，测试编译时就会报出令人困惑的「找不到符号」。
> 遇到这种情况，先重跑一次再开始排查。

```bash
./tools/build.sh -o test                          # 全量测试
./tools/build.sh -o test -Dtest=ClassName         # 只跑单个类
```

**任何改动生产代码的提交，都需要附带新测试，或在 pull request 中说明为什么无法添加。**
能自己证明回归问题的修复，合并得最快。

部分测试是纯单元测试（不需要服务器）。需要 Bukkit 运行时的测试使用 MockBukkit，
共享测试脚手架位于 `src/test/java/com/hilltopvillage/testsupport/`。

## 提交 pull request

1. **先开 issue**，除非只是修个错别字。这能避免你写出一份无法合并的补丁。
2. 从 `main` 拉分支 —— 不要直接提交到 `main`。
3. 保持改动聚焦。一个 pull request 只做一件事；无关的重构会让评审变难，通常会被要求拆开。
4. 推送前先跑 `./tools/build.sh -o clean test`。构建变红不会被评审。
5. 提交信息使用 `type(scope): description`，正文解释**为什么**需要这个改动，而不只是改了什么。

   ```
   fix(scoreboard): recompute line scores when the line count changes

   setScore was only called when a team was first created, so lines kept
   stale sort keys after a countdown line appeared and the sidebar order
   jumped. Scores are now recomputed whenever the desired value changes.
   ```

6. 修改行为、配置键或命令时，在同一提交里更新文档。`README.md` 与 `README-EN.md` 保持同步；
   各专题指南位于 `docs/guides/`（英文版为同目录下的 `docs/guides/X-EN.md`）。

## 代码约定

- **所有日志输出必须使用英文。** 日志的读者是服主与运维，而不是玩家，且不做本地化。
- **所有面向玩家的文本都放在 `messages.yml`** 中，`en:` 与 `zh:` 两个块必须拥有相同的键集合。
  绝不要在 Java 里硬编码玩家可见的字符串。
- 本代码库的 Javadoc 与注释用中文书写，解释**为什么**这么做，而不是这行在做什么。
  单纯的复述式注释不要写。
- 优先使用小而具名的方法，避免深层嵌套的条件分支。若干模块保留了记录历史缺陷的长注释 ——
  请保留它们，它们记录了否则不可见的设计决策。

## 报告 bug

请使用 bug 报告模板。让一份报告可行动的三项信息，缺少任何一项都很难帮上忙：

- 插件版本与 Paper 版本，
- 触发问题的确切命令或游戏内操作，
- 你期望的结果与实际发生的结果。

失败前后的 `logs/latest.log` 控制台输出，胜过一大段文字描述。

## 安全问题

请不要为安全问题开公开 issue —— 私密渠道见 [SECURITY.md](SECURITY.md)。
