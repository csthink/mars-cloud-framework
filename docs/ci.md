# 持续集成与正式验证

所有分支的 push、指向 main 的 pull request 和手动触发都会运行 [CI](../.github/workflows/ci.yml)。工作流不推送 main。框架候选在合并前即可与 service main 构建，验证使用方兼容性。

## 统一构建入口

本地与 CI 共用 `tools/verify.sh` / `tools/verify.py`。工具链是 **Amazon Corretto JDK 25、Apache Maven 3.9.14、Python 3**。CI 通过 `tools/install-maven.sh` 下载 Maven 官方分发包并核验固定 SHA512；完整运行版本写进报告。测试 JVM 参数来自 BOM，不覆盖 `argLine`。

正式验证明确两仓源码路径和完整 SHA，源码必须已提交且干净：

```bash
bash tools/verify.sh \
  --framework-sha "$(git rev-parse HEAD)" \
  --service "$SERVICE_DIR" --service-sha "$(git -C "$SERVICE_DIR" rev-parse HEAD)" \
  --framework-source candidate --purpose compatibility \
  --cache "$CACHE_DIR" --output "$NEW_REPORT_DIR"
```

`SERVICE_DIR` 是待验证的 service main 源码，`CACHE_DIR` 是本次开发环境的依赖缓存，`NEW_REPORT_DIR` 必须是源码树外尚不存在的目录。路径由调用方选择。带 `mars.slot` 配置的工作树只能使用对应隔离缓存。开发中可加 `--development`，其报告不可作为正式验证证据。

驱动在缓存内为每次运行创建新 Maven 仓。只复用第三方依赖，移除复制来的 `com/mars/cloud`，并拒绝 symlink；不会把项目制品写回缓存。构建持有缓存锁和各源码 checkout 的构建锁，第二个构建或刷新遇锁冲突时拒绝，释放后重试。先执行 framework `clean install`，再执行 service `clean verify`，不接受任意 Maven 参数或跳过测试。

## 日志与报告

`report.json` 记录两仓实际 SHA、工作区状态、构建目的、依赖来源、完整工具版本、Maven 命令、退出状态、测试数量、日志诊断和 CI 运行身份。PR 的临时合并 SHA 与源分支 SHA 分别记录。完整 Maven 日志和 Surefire XML 随报告上传，artifact 名含 run ID 与 attempt；保留 30 天。报告过期后须重新验证。

所有有测试源码的模块必须产生报告，每个测试类必须出现；失败、错误、跳过或缺失报告都失败。JUnit 嵌套容器按实际 testcase 计数，不能因外层计数为零而漏掉子测试。

`.ci/log-policy.json` 仅允许已知负向契约测试的精确诊断及已有 JVM 提示，每条附原因；未知 Maven、JVM、应用 WARN / ERROR 使验证失败。新增允许项需解释对应测试或运行条件，不允许忽略整类警告。已有 Mockito bootstrap instrumentation 的 CDS 提示不影响测试执行，单独列出。

## CI 输入和必需检查

framework CI 直接固定自身提交与 service main 的提交，**不调用 service 的依赖解析器**，因此不会把 framework 候选替换成 main。service CI 的依赖声明与选择规则见 [service CI](https://github.com/csthink/mars-cloud-service/blob/main/docs/ci.md)。

CI 运行工具回归、统一构建、两仓公开扫描并上传证据。必需检查名称保持 `构建 + 测试 + 安全扫描`；汇总始终运行，必要步骤缺失、跳过、取消或不是 success 都不能通过。审核结果须对应指定的 workflow、run、最新 attempt 和源码 SHA。源码或依赖改变须重新验证。

安全扫描只覆盖凭据 / 私钥、本机路径、内网地址等已知模式；公开内容仍需人工复核。`tools/check-public-safety-generic.sh` 是 hook 与 CI 的同一实现。当前不发布 Maven 制品。

## 触发 mars-cloud-service

本仓 main 的 push 构建成功后，会向 `csthink/mars-cloud-service` 发送 `framework-updated` 事件，
让下游在框架变更后自动重建——否则「框架改了、服务没跟上」只能靠人记得去跑。

跨仓触发**必须用 PAT**：工作流自带的 `GITHUB_TOKEN` 只能操作本仓，
而且用 `GITHUB_TOKEN` 创建的事件**不会触发新的工作流运行**（GitHub 的既定行为）。

**未配置该 secret 时该步骤会跳过并打印提示，不会让流水线失败**——
否则新克隆的仓一提交就是红的。

### 配置步骤（需要仓库管理员在网页操作）

fine-grained PAT **只能在 GitHub 网页创建，没有 API 或 CLI 可以生成**，
所以这一步无法由自动化代劳。权限刻意收到最小：

1. 打开 <https://github.com/settings/personal-access-tokens/new>
2. **Token name**：`mars-cloud-framework → service dispatch`
3. **Expiration**：按组织策略选（建议 90 天，到期轮换）
4. **Repository access** → 选 **Only select repositories** → 只勾 `csthink/mars-cloud-service`
5. **Permissions** → Repository permissions → 只开一项：
   **Contents: Read and write**（`repository_dispatch` 事件即由它覆盖）。
   其余全部保持 **No access**——尤其不要给 `Administration`、`Workflows`、`Secrets`
6. 生成后复制 token，存为本仓 secret（用管道，**不要写进命令行参数**，
   否则会留在 shell 历史里）：

   ```bash
   printf '%s' '<粘贴 token>' | gh secret set SERVICE_DISPATCH_TOKEN -R csthink/mars-cloud-framework
   ```

7. 验证：

   ```bash
   # 查看最近一次 main push 的通知 job；手动触发不发送跨仓事件
   gh run list -R csthink/mars-cloud-framework --branch main --event push --limit 5
   gh run list -R csthink/mars-cloud-service --limit 5   # 应出现 event=repository_dispatch 的 run
   ```

> ⚠️ **不要用个人 CLI token（`gho_…`）充当这个 secret。** 它带 `repo` 全范围权限，
> 远超「向一个仓发一个事件」所需。跨仓只读目标仓 + 发事件，用上面的细粒度权限就够了。

### 轮换

到期或怀疑泄漏时：删旧 token → 按上面步骤生成新的 → 覆盖 secret。删 secret 用：

```bash
gh secret delete SERVICE_DISPATCH_TOKEN -R csthink/mars-cloud-framework
```
