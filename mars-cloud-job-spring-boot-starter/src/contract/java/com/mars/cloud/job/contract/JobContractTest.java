package com.mars.cloud.job.contract;

import com.mars.cloud.job.JobContext;
import com.mars.cloud.job.JobHandler;
import com.mars.cloud.job.autoconfigure.MarsJobAutoConfiguration;
import com.mars.cloud.job.internal.JobExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 对真实 xxl-job-admin 验证执行器协议：注册、触发、成功与失败回调、调度中心读执行日志、下线摘除。
 *
 * <p>只由 {@code tools/job-contract.sh} 经 Maven profile {@code job-contract} 执行。调度中心地址、访问令牌、注册主机与前缀
 * 从环境变量来；测试经管理页面接口（管理员登录）建一个带前缀、带随机后缀的临时执行器组与任务，结束时删除它们与执行记录。
 */
class JobContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration REGISTRY_WAIT = Duration.ofSeconds(90);

    public static class ContractJobs {
        @JobHandler("contractJob")
        public void run(JobContext context) {
            context.log("contract job ran with {}", context.param());
            if ("fail".equals(context.param())) {
                throw new IllegalStateException("requested failure");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(MarsJobAutoConfiguration.class)
    static class ContractApplication {
        @Bean
        ContractJobs contractJobs() {
            return new ContractJobs();
        }
    }

    private final String admin = System.getenv("MARS_JOB_ADMIN_ADDRESSES");
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private final String application = "job-contract-" + UUID.randomUUID().toString().substring(0, 8);
    private final String appName = System.getenv().getOrDefault("MARS_MQ_PREFIX", "") + application;
    private Path logDirectory;
    private ConfigurableApplicationContext executor;
    private int groupId;
    private int jobId;

    @BeforeEach
    void setUp() throws Exception {
        logDirectory = Files.createTempDirectory("job-contract");
        JsonNode login = page("/auth/doLogin", Map.of("userName", "admin", "password", System.getenv("XXL_ADMIN_PASSWORD")));
        assertThat(login.get("code").asInt()).as("admin login: %s", login).isEqualTo(200);
        JsonNode group = page("/jobgroup/insert", Map.of("appname", appName, "title", appName, "addressType", "0", "addressList", ""));
        assertThat(group.get("code").asInt()).as("group insert: %s", group).isEqualTo(200);
        JsonNode groups = page("/jobgroup/pageList", Map.of("offset", "0", "pagesize", "10", "appname", appName, "title", ""));
        groupId = groups.get("data").get("data").get(0).get("id").asInt();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (executor != null) {
            executor.close();
        }
        if (jobId > 0) {
            page("/joblog/clearLog", Map.of("jobGroup", String.valueOf(groupId), "jobId", String.valueOf(jobId), "type", "9"));
            page("/jobinfo/delete", Map.of("ids[]", String.valueOf(jobId)));
        }
        if (groupId > 0) {
            page("/jobgroup/delete", Map.of("ids[]", String.valueOf(groupId)));
        }
    }

    @Test
    void theSchedulerRegistersTriggersReadsLogsAndDeregistersTheExecutor() throws Exception {
        executor = new SpringApplicationBuilder(ContractApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.application.name=" + application, "server.address=127.0.0.1", "mars.job.executor.port=0",
                        "mars.job.executor.log-path=" + logDirectory)
                .run();
        String address = executor.getBean(JobExecutor.class).registeredAddress();

        // 调度中心每 30 秒按心跳重写执行器组的地址列表
        await().atMost(REGISTRY_WAIT).pollInterval(Duration.ofSeconds(2)).until(() -> registryList().contains(address.replaceAll("/$", "")));

        JobDataView job = createJob();
        jobId = job.id();

        JsonNode success = triggerAndAwait("ok");
        assertThat(success.get("handleCode").asInt()).isEqualTo(200);
        assertThat(success.get("executorAddress").asString()).startsWith(address.replaceAll("/$", ""));
        JsonNode log = page("/joblog/logDetailCat", Map.of("logId", success.get("id").asString(), "fromLineNum", "1"));
        assertThat(log.get("code").asInt()).as("log read: %s", log).isEqualTo(200);
        assertThat(log.get("data").get("logContent").asString()).contains("contract job ran with ok").contains("任务执行成功");

        JsonNode failure = triggerAndAwait("fail");
        assertThat(failure.get("handleCode").asInt()).isEqualTo(500);
        assertThat(failure.get("handleMsg").asString()).contains("java.lang.IllegalStateException: requested failure");

        executor.close();
        executor = null;
        await().atMost(REGISTRY_WAIT).pollInterval(Duration.ofSeconds(2)).until(() -> !registryList().contains(address.replaceAll("/$", "")));
    }

    private record JobDataView(int id) {
    }

    private JobDataView createJob() throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("jobGroup", String.valueOf(groupId));
        form.put("jobDesc", "contract " + application);
        form.put("author", "contract");
        form.put("alarmEmail", "");
        form.put("scheduleType", "NONE");
        form.put("scheduleConf", "");
        form.put("misfireStrategy", "DO_NOTHING");
        form.put("executorRouteStrategy", "FIRST");
        form.put("executorHandler", "contractJob");
        form.put("executorParam", "");
        form.put("executorBlockStrategy", "SERIAL_EXECUTION");
        form.put("executorTimeout", "0");
        form.put("executorFailRetryCount", "0");
        form.put("glueType", "BEAN");
        form.put("glueSource", "");
        form.put("glueRemark", "");
        form.put("childJobId", "");
        JsonNode created = page("/jobinfo/insert", form);
        assertThat(created.get("code").asInt()).as("job insert: %s", created).isEqualTo(200);
        return new JobDataView(Integer.parseInt(created.get("data").asString()));
    }

    private JsonNode triggerAndAwait(String param) throws Exception {
        JsonNode triggered = page("/jobinfo/trigger", Map.of("id", String.valueOf(jobId), "executorParam", param, "addressList", ""));
        assertThat(triggered.get("code").asInt()).as("trigger: %s", triggered).isEqualTo(200);
        JsonNode[] found = new JsonNode[1];
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            JsonNode logs = page("/joblog/pageList", Map.of("offset", "0", "pagesize", "20", "jobGroup", String.valueOf(groupId),
                    "jobId", String.valueOf(jobId), "logStatus", "-1", "filterTime", ""));
            for (JsonNode entry : logs.get("data").get("data")) {
                if (param.equals(entry.get("executorParam").asString()) && entry.get("handleCode").asInt() > 0) {
                    found[0] = entry;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    private String registryList() throws Exception {
        JsonNode group = page("/jobgroup/loadById", Map.of("id", String.valueOf(groupId)));
        JsonNode list = group.get("data").get("registryList");
        return list == null || list.isNull() ? "" : list.toString();
    }

    private JsonNode page(String path, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(admin + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("%s -> %s", path, response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
}
