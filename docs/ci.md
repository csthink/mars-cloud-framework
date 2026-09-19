# 持续集成

本仓的流水线定义在 [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)，在 `main` 的 push、
指向 `main` 的 pull request、以及手动触发时运行。

## 流水线做了什么

1. **构建 + 测试**：`mvn clean install`（Java 25 / Temurin）
2. **公开安全扫描**：`tools/check-public-safety-generic.sh`

> **本仓目前不发布制品。** 私有制品仓库尚未选定，所以流水线只构建到本地仓库。
> 下游服务仓的 CI 会检出本仓源码并 `mvn install` 来拿到依赖——
> 等制品库定下来，再补发布步骤与下游的拉取配置（见
> [docs/architecture.md](architecture.md) 的版本策略）。

## 为什么安全扫描在 CI 里也要跑

本仓装了 pre-commit hook，但 hook 可以被 `git commit --no-verify` 绕过。
**内容一旦 push 到公开仓，即使随后删除，Git 历史里仍在**——只能改写历史或轮换凭据。
所以 CI 是最后一道，不能只靠本地 hook。

本地随时可以自己跑一遍：

```bash
bash tools/check-public-safety-generic.sh .
```

扫描器拦下的是三类：凭据与私钥、本机绝对路径与家目录引用、内网地址段。
**扫描器只能拦已知模式**——它拦不住「语气里透出的内部判断」，写文档与注释时仍要自己把关。

**因此「CI 绿」不等于「没有内部信息外泄」**：上述三类之外的判断（内部代号与里程碑、
对其他系统的评价、未公开的计划与结论）没有任何自动化检查，只能靠人工复核。
CI 通过只说明它检查过的那三类没问题。

## 让它更严：接入专用 secret 扫描

当前「凭据」规则是 `关键词 + 赋值 + 8 位以上字面量`（占位符与空值不匹配），
这是在没有外部依赖前提下误报最低的写法。若要更强的检测，建议引入专用工具而不是手写关键词 grep——
关键词 grep 会在扫描脚本自身、测试代码、`password: ""` 这类合法配置上产生大量误报。

## 触发 mars-cloud-service

本仓构建成功后，会向 `csthink/mars-cloud-service` 发送 `framework-updated` 事件，
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
   # 触发一次本仓流水线，看 dispatch 步骤是否真的发出事件
   gh workflow run ci.yml -R csthink/mars-cloud-framework
   gh run list -R csthink/mars-cloud-service --limit 5   # 应出现 event=repository_dispatch 的 run
   ```

> ⚠️ **不要用个人 CLI token（`gho_…`）充当这个 secret。** 它带 `repo` 全范围权限，
> 远超「向一个仓发一个事件」所需。跨仓只读目标仓 + 发事件，用上面的细粒度权限就够了。

### 轮换

到期或怀疑泄漏时：删旧 token → 按上面步骤生成新的 → 覆盖 secret。删 secret 用：

```bash
gh secret delete SERVICE_DISPATCH_TOKEN -R csthink/mars-cloud-framework
```
