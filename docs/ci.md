# 持续集成

本仓的流水线定义在 [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)，在 `main` 的 push、
指向 `main` 的 pull request、以及手动触发时运行。

## 流水线做了什么

1. **构建 + 测试**：`mvn clean install`（Java 25 / Temurin）
2. **公开安全扫描**：`tools/check-public-safety-generic.sh`

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

## 让它更严：接入专用 secret 扫描

当前「凭据」规则是 `关键词 + 赋值 + 8 位以上字面量`（占位符与空值不匹配），
这是在没有外部依赖前提下误报最低的写法。若要更强的检测，建议引入专用工具而不是手写关键词 grep——
关键词 grep 会在扫描脚本自身、测试代码、`password: ""` 这类合法配置上产生大量误报。

## 触发 mars-cloud-service

本仓构建成功后，会向 `csthink/mars-cloud-service` 发送 `framework-updated` 事件，
让下游在框架变更后自动重建——否则「框架改了、服务没跟上」只能靠人记得去跑。

跨仓触发**必须用 PAT**：工作流自带的 `GITHUB_TOKEN` 只能操作本仓。
需要配置一个名为 `SERVICE_DISPATCH_TOKEN` 的仓库 secret：

1. 生成一个 fine-grained PAT，仓库范围只勾 `csthink/mars-cloud-service`，
   权限给 **Contents: Read and write**（dispatch 事件由 contents 权限覆盖）
2. 存为本仓 secret：

   ```bash
   gh secret set SERVICE_DISPATCH_TOKEN -R csthink/mars-cloud-framework
   ```

**未配置该 secret 时该步骤会跳过并打印提示，不会让流水线失败**——
否则新克隆的仓一提交就是红的。
