package com.mars.cloud.rocketmq.coldstart;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** 真进程加持久数据库，恢复进程禁止新发送。目录由契约脚本保留。 */
class RocketMqColdStartContractTest {
    @Test
    void restartRecoversCommittedRolledBackAndUnknownFactsWithoutNewSend() throws Exception {
        Path root = Path.of(System.getenv("ROCKETMQ_CONTRACT_REPORT_DIR"), "cold-start");
        for (String outcome : List.of("COMMIT", "ROLLBACK", "UNKNOWN")) {
            Path directory = root.resolve(outcome.toLowerCase());
            Files.createDirectories(directory);
            String prefix = System.getenv("MARS_MQ_PREFIX").replace("-", "") + outcome.toLowerCase() + "-";
            assertThat(run("send", outcome, directory, prefix)).as("send process %s", outcome).isEqualTo(86);
            assertThat(directory.resolve("send-checks.txt")).doesNotExist();
            assertThat(run("recover", outcome, directory, prefix)).as("recover process %s", outcome).isZero();
            assertThat(Files.readString(directory.resolve("result.txt"))).contains("sends=0");
            String checks = Files.readString(directory.resolve("recover-checks.txt"));
            assertThat(checks).contains(outcome.equals("ROLLBACK") ? "ROLLBACK_MESSAGE" : "COMMIT_MESSAGE");
            if (outcome.equals("UNKNOWN")) assertThat(checks.lines().filter(s -> s.endsWith("UNKNOW")).count()).isGreaterThanOrEqualTo(2);
        }
    }

    private int run(String mode, String outcome, Path directory, String prefix) throws Exception {
        var command = List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"), ColdStartApplication.class.getName(),
                mode, directory.toAbsolutePath().toString(), outcome);
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(mode + ".log").toFile());
        builder.environment().put("MARS_MQ_PREFIX", prefix);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("process finished: %s %s", mode, outcome).isTrue();
            return process.exitValue();
        } finally {
            if (process.isAlive()) { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly(); }
        }
    }
}
