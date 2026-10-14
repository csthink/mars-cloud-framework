# 持续集成与正式验证

验证分两层：

| 层 | 在哪里运行 | 内容 |
| --- | --- | --- |
| 正式验证 | 开发者本机，推送前 | `tools/verify.sh`：两仓源码的全量构建与全部测试，加日志审查；framework 候选与 service main 一起构建，验证使用方兼容性 |
| 基础检查 | [CI](../.github/workflows/ci.yml)，所有分支的 push、指向 main 的 pull request 和手动触发 | 编译本仓（含测试源码，不运行测试）、验证工具单元测试、本仓公开内容扫描 |

基础检查只证明推送的提交能编译、扫描通过；测试是否全部通过以正式验证报告为准。工作流不推送 main。

## 统一构建入口

正式验证的唯一入口是 `tools/verify.sh` / `tools/verify.py`。工具链是 **Amazon Corretto JDK 25、Apache Maven 3.9.14、Python 3**。`tools/install-maven.sh` 下载 Maven 官方分发包并核验固定 SHA512，基础检查用它安装 Maven；完整运行版本写进报告。测试 JVM 参数来自 BOM，不覆盖 `argLine`。

正式验证明确两仓源码路径和完整 SHA，源码必须已提交且干净：

```bash
bash tools/verify.sh \
  --framework-sha "$(git rev-parse HEAD)" \
  --service "$SERVICE_DIR" --service-sha "$(git -C "$SERVICE_DIR" rev-parse HEAD)" \
  --framework-source candidate --purpose compatibility \
  --cache "$CACHE_DIR" --output "$NEW_REPORT_DIR"
```

`SERVICE_DIR` 是待验证的 service main 源码，`CACHE_DIR` 是本次开发环境的依赖缓存，`NEW_REPORT_DIR` 必须是源码树外尚不存在的目录。路径由调用方选择。带 `mars.slot` 配置的工作树只能使用对应隔离缓存。开发中可加 `--development`，其报告不可作为正式验证证据。

驱动在缓存内为每次运行创建新 Maven 仓（运行仓，位于 `.verification/` 下）。从缓存的依赖目录只取第三方依赖，移除复制来的 `com/mars/cloud`（项目制品只来自本次构建或下文的存档），并拒绝 symlink；不会把项目制品写回缓存。构建持有缓存锁和各源码 checkout 的构建锁，第二个构建或刷新遇锁冲突时拒绝，释放后重试。先执行 framework `clean install`，再执行 service `clean verify`，不接受任意 Maven 参数或跳过测试。

### framework 构建复用

framework 构建通过（退出码 0、没有未登记的日志诊断、测试报告完整）后，正式验证把这次的 framework 构建存档到缓存的 `.framework-builds/<键>/`：运行仓里的 `com/mars/cloud`、framework 的 Surefire XML、报告中 framework 的构建记录（`build.json`），以及列出上述每个文件 SHA-256 的 `manifest.json`。键由 framework 提交 SHA、完整工具版本与日志规则文件的 SHA-256 计算，三者任一变化都对应新的存档。存档先写入临时目录再整体改名，完成后设为只读；同键存档已存在时不覆盖，`--rebuild-framework` 的构建除外（见下段）。上次运行中断留下的临时目录在下一次写存档时清除。

之后同一输入的正式验证不再构建 framework：先逐个核对存档文件与 `manifest.json` 一致、没有多余或缺失的文件、清单记录的三项与本次相同，再把存档的 `com/mars/cloud` 复制进本次运行仓，只执行 service `clean verify`。核对不通过时验证失败并给出存档位置，不会改为重新构建；检查后删除该存档（目录只读，先用 `chmod -R u+w` 恢复写权限）再重新验证。需要 framework 重新执行全部测试时（例如排查偶发失败的测试），加 `--rebuild-framework`：这次构建不读已有存档，framework 通过后用这次的结果替换同键存档，之后的复用以这次构建为准。`--development` 运行既不存档也不复用。

### 运行仓清理

每次构建在取得缓存锁之后、创建本次运行仓之前，删除 `.verification/` 下除最新一个以外的全部运行仓。构建结束后的服务启动检查要在该次构建的运行仓里解析测试类路径，所以保留最新的一个，任一时刻最多存在上一次与本次两个运行仓。清理只作用于 `.verification/`，不涉及 `.framework-builds/` 存档与缓存里的第三方依赖。

## 日志与报告

`report.json` 记录两仓实际 SHA、工作区状态、构建目的、依赖来源、完整工具版本、Maven 命令、退出状态、测试数量与日志诊断；完整 Maven 日志和 Surefire XML 与它放在同一个报告目录。报告只对它记录的两个 SHA 有效，源码或依赖改变须重新验证。

`builds` 固定为 framework 在前、service 在后。复用 framework 存档时，framework 一项是存档里的构建记录原文，另加 `reused`（存档的键与 `manifest.json` 的 SHA-256），报告顶层 `framework_reused` 为 `true`，framework 的 Surefire XML 从存档复制到本次报告目录，Maven 日志只有 service 的。本次运行新建了存档时，顶层 `framework_archive` 记录它的键与 `manifest.json` 的 SHA-256，其余情况为 `null`。复用项所证明的测试执行，来自新建该存档的那份报告。

所有有测试源码的模块必须产生报告，每个测试类必须出现；失败、错误、跳过或缺失报告都失败。JUnit 嵌套容器按实际 testcase 计数，不能因外层计数为零而漏掉子测试。

`.ci/log-policy.json` 仅允许已知负向契约测试的精确诊断及已有 JVM 提示，每条附原因；未知 Maven、JVM、应用 WARN / ERROR 使验证失败。新增允许项需解释对应测试或运行条件，不允许忽略整类警告。已有 Mockito bootstrap instrumentation 的 CDS 提示不影响测试执行，单独列出。

应用日志按级别所在行识别，诊断写成「级别 类名: 消息」。有的消息从下一行才开始，级别所在行在类名处结束；这时取下一条非空、且不是新日志记录的行作为消息，照常与允许项比对，不会因为级别所在行没有消息而漏检。

## 基础检查与必需检查

CI 只检出本仓，不检出其他仓库，不上传构建产物。步骤：

1. 安装 Corretto 25 与 Maven 3.9.14
2. `python3 -m unittest discover -s tools/tests`：验证工具自身的单元测试
3. `mvn -B -ntp -DskipTests verify`：编译全部模块与测试源码，不运行测试
4. `tools/check-public-safety-generic.sh .`：本仓公开内容扫描

必需检查名称是 `编译 + 安全扫描`；汇总始终运行，必要步骤缺失、跳过、取消或不是 success 都不能通过。

安全扫描只覆盖凭据 / 私钥、本机路径、内网地址等已知模式；公开内容仍需人工复核。`tools/check-public-safety-generic.sh` 是 hook 与 CI 的同一实现。当前不发布 Maven 制品。
