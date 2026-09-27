# mars-cloud-job-spring-boot-starter

周期任务 starter：服务作为任务调度中心 xxl-job-admin（下称调度中心）的执行器，由调度中心按 cron 触发服务里的 Java 方法，
执行日志与结果在调度中心页面查看，支持手动触发。本组件自己实现执行器一侧的 HTTP 协议，不依赖 xxl-job 发布的任何 Java 构件；
协议按 xxl-job-admin 3.4.2 实现，升级调度中心前先重跑本模块的契约测试。

只适合周期性的运维任务（对账、聚合、清理）。某条数据到点触发一次的定时（订单超时、通知到点发布）留在服务内实现，
不依赖调度中心：调度中心不可用时周期任务只是暂停，服务的其他功能照常。

## 坐标

```xml
<dependency>
    <groupId>com.mars.cloud</groupId>
    <artifactId>mars-cloud-job-spring-boot-starter</artifactId>
</dependency>
```

它带来 `mars-cloud-common`、`spring-boot-autoconfigure` 与 Jackson。HTTP 服务端与客户端使用 JDK 自带的实现，不引入第三方依赖。

## 写一个任务

```java
@Component
class ReportJobs {

    private final ReportService reports;

    ReportJobs(ReportService reports) {
        this.reports = reports;
    }

    @JobHandler("dailyReport")
    void dailyReport(JobContext context) {
        int rows = reports.aggregate(context.param());
        context.log("聚合完成：{} 行", rows);
    }
}
```

- 方法无参，或只接受一个 `JobContext`；返回值被忽略。正常返回是成功，抛出异常是失败：异常摘要作为执行结果回报调度中心，
  堆栈写进本次执行的日志。
- 名称（`@JobHandler` 的值）就是调度中心任务配置里的 JobHandler，在一个服务内唯一。重名、签名不符、静态方法、
  非单例 Bean 上的任务方法都让应用启动失败。
- 方法在 Bean 本身上调用：Bean 被代理时（例如 `@Transactional`）切面生效；JDK 动态代理的 Bean 只能用接口里声明的方法，
  代理的 Bean 上任务方法不能是 private，CGLIB 代理的 Bean 上不能是 final（final 方法不经过代理），否则启动失败。
- 任务方法只调用服务里已有的业务逻辑，并且必须幂等：失败重试、手动补跑与执行器重启都可能让同一次任务执行不止一次。
- `JobContext` 提供任务编号、本次执行的日志编号、参数、分片序号与总数，以及 `log(...)`：一行写进本次执行的日志（调度中心页面可见），
  同时以 INFO 写进服务日志。

执行器只执行这样登记的 Java 方法。调度中心下发脚本类型（GLUE）的任务时，执行器回报失败，不执行任何下发的源码。

## 运行环境变量

| 变量 | 含义 |
| --- | --- |
| `MARS_JOB_ADMIN_ADDRESSES` | 调度中心地址，`http(s)://主机:端口`，不带路径；多个以逗号分隔，按顺序尝试。必填 |
| `MARS_JOB_ACCESS_TOKEN` | 调度中心与执行器共用的访问令牌，与调度中心的 `xxl.job.accessToken` 相同，至少 16 个字符。必填 |
| `MARS_JOB_REGISTER_HOST` | 注册给调度中心的主机名或 IP。不设置时取 `server.address`；调度中心访问执行器要经过另一个地址时设置 |
| `MARS_MQ_PREFIX` | 运行环境前缀，形如 `s1-`，加在执行器名前面（与 RocketMQ 组件共用） |

它们与运行环境相关，只从环境变量来，不写进配置中心的共享配置。本机调度中心运行在 Docker Desktop 的容器里、执行器只监听
`127.0.0.1` 时，`MARS_JOB_REGISTER_HOST` 写 Docker Desktop 提供的宿主机主机名 `host.docker.internal`，容器经它访问宿主机上只监听回环地址的端口。

## 执行器名、端口与地址

| 项 | 规则 |
| --- | --- |
| 执行器名 | 运行环境前缀加 `spring.application.name`，例如 `s1-mars-cloud-sample-service`；须小写字母开头、只含字母数字与连字符、长度 4 到 64。调度中心按它把执行器归组 |
| 执行器端口 | 业务端口加 2000（偏移量 `mars.job.executor.port-offset`）。显式配置 `mars.job.executor.port` 时必须等于这个值；0 表示随机端口，只用于测试；业务端口未配置或是随机端口时必须显式配置 |
| 监听地址 | `server.address` 是具体 IP 时只监听它；未配置、通配地址或主机名时监听全部网卡，此时必须设置 `MARS_JOB_REGISTER_HOST` |
| 注册地址 | `http://<注册主机>:<执行器端口>/`，注册主机默认取监听地址；IPv6 地址加方括号 |

约定在启动期核验，不符即启动失败，失败信息写出规则与收到的值（访问令牌只报长度）。端口被占用时同样启动失败。

## 访问控制

- 调度中心的每次调用都要在请求头 `XXL-JOB-ACCESS-TOKEN` 携带访问令牌，执行器在读请求体之前做常量时间比较，不符时
  不读请求体、不执行任何操作，回报 `The access token is wrong.`。
- 执行器端口不经网关；部署时由网络访问控制（安全组或防火墙）只放行调度中心所在的地址。
- 只接受 `POST /beat`、`/idleBeat`、`/run`、`/kill`、`/log`，其他方法与路径分别回 405 与 404，请求体超过 1 MiB 回 413。

## 执行、阻塞策略与关闭

- 每个任务编号一个串行的工作线程，空闲 90 秒后退出，下次触发时重建。`/run` 只校验与入队，立即返回。
- 阻塞处理策略：串行（排队）、丢弃后续调度（执行中则本次触发回报失败）、覆盖之前调度（中断执行中的一次、取消排队的触发）。
- 任务超时（调度中心任务配置里的超时时间）到达时中断执行线程，结果为超时。任务方法要响应中断，否则会一直执行到结束。
- 执行或排队期间不重复接受同一个执行日志编号；调度中心终止任务时，排队的触发被取消，执行中的一次被中断，都以失败回报。
- 执行结果经有界队列批量回报调度中心，失败时退避重发（最长 30 秒一次），队列满时丢弃最旧的一条并告警。
- 关闭时先从调度中心摘除并停止接收，再在 `mars.job.executor.shutdown-timeout`（默认 30 秒）内等待执行中的任务，超时则中断；
  最后在剩余时间内发送回调。生命周期阶段排在 Web 服务器之后启动、在 Web 服务器优雅关闭之后停止。
- 执行器启动后立即注册，此后每 30 秒一次；调度中心不可达时只在状态变化时写一条告警，恢复时写一条 INFO。

## 执行日志

一次执行一个文件：`<日志目录>/<yyyy-MM-dd>/<日志编号>.log`，日期取调度中心给出的触发时间。调度中心页面经 `/log` 按行分页读取
（每次最多 1000 行）。日志目录默认是临时目录下的 `mars-job/<执行器名>`，保留 7 天，每天清理一次。这是本组件唯一写入的文件；
容器重启后旧文件随之消失，只影响在调度中心页面查看此前的执行日志，服务日志里的同样内容不受影响。

## 链路追踪

每次执行开一个没有父级的根 span，名称 `job <任务名>`，标签 `job.id` 与 `job.log_id`；执行日志第一行写出 traceId，
可以从调度中心的执行日志跳到调用链与服务日志。调度中心的协议不携带 `traceparent`，所以不从调度中心续接。
容器里没有唯一的 Micrometer `Tracer` 时不建 span。

周期任务不代表任何终端用户：执行期间没有调用方身份，任务里经服务调用组件访问其他服务时不带身份头。

## 配置

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `mars.job.enabled` | `true` | 是否启动执行器。单元测试与不连接调度中心的本机运行设为 `false` |
| `mars.job.admin.timeout` | `3s` | 调用调度中心的连接与读取超时，1 到 10 秒 |
| `mars.job.executor.port` | 业务端口加偏移量 | 见上文 |
| `mars.job.executor.port-offset` | `2000` | 执行器端口相对业务端口的偏移量 |
| `mars.job.executor.log-path` | 临时目录下 `mars-job/<执行器名>` | 执行日志目录，须是绝对路径 |
| `mars.job.executor.log-retention` | `7d` | 执行日志保留时长，至少 1 天 |
| `mars.job.executor.shutdown-timeout` | `30s` | 关闭时等待执行中任务的时长。执行器在 Web 服务器优雅关闭之后停止，部署平台的终止宽限期要覆盖这两段等待，另加几秒用于线程收尾与回调发送 |

## 测试

- 单元测试覆盖协议字段、配置核验、任务方法登记、阻塞策略、超时与终止、执行日志分页、注册与回调的失败处理，
  以及自动配置、链路追踪与延迟初始化，随普通构建执行。
- 契约测试对真实的 xxl-job-admin 验证注册、触发、成功与失败回调、调度中心读取执行日志与下线摘除，源码在 `src/contract/java`，
  只在本机执行。参数依次是调度中心地址、注册主机、运行环境前缀与报告目录（源码树外、尚不存在）；本机调度中心在 Docker Desktop
  容器里时，注册主机写 `host.docker.internal`：

```bash
export MARS_JOB_ACCESS_TOKEN=...   # 调度中心的 xxl.job.accessToken
export XXL_ADMIN_PASSWORD=...      # 调度中心管理员 admin 的口令
tools/job-contract.sh http://127.0.0.1:28083 <注册主机> s1- <报告目录>
```

测试经调度中心的管理页面接口建一个带前缀、带随机后缀的临时执行器组与任务，结束时删除它们与执行记录。
