package com.mars.cloud.sentinel.internal;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.command.CommandCenterProvider;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotChain;
import com.alibaba.csp.sentinel.slotchain.SlotChainProvider;
import com.alibaba.csp.sentinel.slots.DefaultSlotChainBuilder;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.logger.LogSlot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行期足迹：处理链只比默认链少 {@code LogSlot}；拦截后日志目录与 EagleEye 目录没有任何文件，拦截计入指标；
 * 命令中心是不监听端口的实现；日志扩展与 sentinel-core 版本一致。
 */
class SentinelRuntimeFootprintTest {

    @AfterEach
    void clear() {
        FlowRuleManager.loadRules(List.of());
    }

    @Test
    void theSlotChainIsTheDefaultOneWithoutLogSlot() {
        List<Class<?>> defaults = slots(new DefaultSlotChainBuilder().build());
        List<Class<?>> ours = slots(new LogSlotFreeSlotChainBuilder().build());

        assertThat(defaults).contains(LogSlot.class);
        assertThat(ours).isEqualTo(defaults.stream().filter(type -> type != LogSlot.class).toList());
        assertThat(slots(SlotChainProvider.newSlotChain())).isEqualTo(ours);
    }

    @Test
    void blockingWritesNoFilesAndCountsTheBlock() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBlockedRequestRecorder recorder = new MicrometerBlockedRequestRecorder(registry);
        BlockedRequestMetricExtension.bind(recorder);
        try {
            FlowRule rule = new FlowRule("footprint-probe");
            rule.setCount(0);
            FlowRuleManager.loadRules(List.of(rule));
            assertThatThrownBy(() -> {
                Entry entry = SphU.entry("footprint-probe");
                entry.exit();
            }).isInstanceOf(BlockException.class);

            assertThat(registry.get(MicrometerBlockedRequestRecorder.BLOCKED)
                    .tags("resource", "footprint-probe", "exception", "FlowException").counter().count()).isEqualTo(1);
        } finally {
            BlockedRequestMetricExtension.unbind(recorder);
        }
        assertThat(regularFiles(System.getProperty("csp.sentinel.log.dir"))).isEmpty();
        assertThat(regularFiles(System.getProperty("EAGLEEYE.LOG.PATH"))).isEmpty();
    }

    @Test
    void theCommandCenterListensOnNoPort() {
        assertThat(CommandCenterProvider.getCommandCenter()).isInstanceOf(DisabledCommandCenter.class);
    }

    @Test
    void theLoggingExtensionMatchesTheSentinelCoreVersion() throws IOException {
        assertThat(version("sentinel-logging-slf4j"))
                .as("BOM 里的 sentinel-logging-slf4j.version 必须等于 Spring Cloud Alibaba BOM 的 sentinel.version")
                .isEqualTo(version("sentinel-core"));
    }

    private static List<Class<?>> slots(ProcessorSlotChain chain) {
        List<Class<?>> types = new ArrayList<>();
        for (AbstractLinkedProcessorSlot<?> slot = chain.getNext(); slot != null; slot = slot.getNext()) {
            types.add(slot.getClass());
        }
        return types;
    }

    private static List<Path> regularFiles(String directory) throws IOException {
        assertThat(directory).as("测试 JVM 由 surefire 设置了 Sentinel 与 EagleEye 的目录").isNotBlank();
        Path root = Path.of(directory);
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private static String version(String artifactId) throws IOException {
        String resource = "META-INF/maven/com.alibaba.csp/" + artifactId + "/pom.properties";
        try (InputStream input = SentinelRuntimeFootprintTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            Properties properties = new Properties();
            properties.load(input);
            return properties.getProperty("version");
        }
    }
}
