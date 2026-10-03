package com.mars.cloud.rocketmq.coldstart;

import com.mars.cloud.common.messaging.EventEnvelope;
import com.mars.cloud.rocketmq.publish.TransactionStateChecker;
import com.mars.cloud.rocketmq.publish.TransactionalEventPublisher;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 仅契约测试使用：本地事务提交或回滚后立即退出，恢复进程没有发送路径。 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class ColdStartApplication {
    static String database;
    static Path directory;
    static String outcome;
    static String mode;
    static final AtomicInteger checks = new AtomicInteger();

    static Connection connection() throws SQLException { return DriverManager.getConnection(database, "sa", ""); }

    public static void main(String[] args) throws Exception {
        mode = args[0];
        directory = Path.of(args[1]);
        outcome = args[2];
        database = "jdbc:h2:file:" + directory.resolve("facts").toAbsolutePath() + ";WRITE_DELAY=0";
        Files.createDirectories(directory);
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS fact (id VARCHAR PRIMARY KEY, completed_at VARCHAR, state VARCHAR, audits INT)");
            statement.execute("CREATE TABLE IF NOT EXISTS delivery (id VARCHAR PRIMARY KEY, completed_at VARCHAR, audits INT)");
            if ("recover".equals(mode)) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM delivery")) {
                    rows.next(); if (rows.getInt(1) != 0) throw new IllegalStateException("恢复前已有投递，不能证明冷启动回查");
                }
            }
        }
        try (var context = new SpringApplicationBuilder(ColdStartApplication.class).web(WebApplicationType.NONE).run(
                "--spring.main.banner-mode=off", "--spring.application.name=mars-cloud-cold-service",
                "--spring.cloud.function.definition=coldEvent",
                "--spring.cloud.stream.output-bindings=coldTx-out-0",
                "--spring.cloud.stream.bindings.coldEvent-in-0.destination=cold-event",
                "--spring.cloud.stream.bindings.coldEvent-in-0.group=mars-cloud-cold-service-cold-event",
                "--spring.cloud.stream.bindings.coldTx-out-0.destination=cold-event",
                "--spring.cloud.stream.rocketmq.bindings.coldTx-out-0.producer.group=mars-cloud-cold-service-tx",
                "--spring.cloud.stream.rocketmq.bindings.coldTx-out-0.producer.producer-type=Trans",
                "--spring.cloud.stream.rocketmq.bindings.coldTx-out-0.producer.transaction-listener=marsTransactionListener",
                "--mars.rocketmq.topology=provision", "--logging.level.root=WARN")) {
            if ("send".equals(mode)) {
                var envelope = new EventEnvelope<>(java.util.UUID.randomUUID().toString(), "COMPLETED",
                        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), "mars-cloud-cold-service", null,
                        outcome, Map.of("value", "original"));
                Files.writeString(directory.resolve("event.txt"), envelope.eventId() + "\n" + envelope.occurredAt());
                context.getBean(TransactionalEventPublisher.class).publish("coldTx-out-0", envelope, () -> {
                    try (var db = connection(); var insert = db.prepareStatement("INSERT INTO fact VALUES (?, ?, ?, 1)")) {
                        db.setAutoCommit(false);
                        insert.setString(1, envelope.eventId()); insert.setString(2, envelope.occurredAt().toString());
                        insert.setString(3, outcome); insert.executeUpdate();
                        if ("ROLLBACK".equals(outcome)) db.rollback(); else db.commit();
                    } catch (SQLException error) { throw new IllegalStateException(error); }
                    Runtime.getRuntime().halt(86);
                });
                throw new IllegalStateException("退出点未执行");
            }
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
            boolean promoted = false;
            while (System.nanoTime() < deadline) {
                if ("UNKNOWN".equals(outcome) && !promoted && checks.get() >= 2) {
                    try (var db = connection(); var query = db.createStatement()) {
                        try (var rows = query.executeQuery("SELECT COUNT(*) FROM delivery")) {
                            rows.next(); if (rows.getInt(1) != 0) throw new IllegalStateException("未确定事务已被投递");
                        }
                        query.executeUpdate("UPDATE fact SET state='COMMIT' WHERE state='UNKNOWN'");
                        promoted = true;
                    }
                }
                if ("ROLLBACK".equals(outcome) && checks.get() > 0) {
                    Thread.sleep(4000);
                    assertDatabase(false);
                    Files.writeString(directory.resolve("result.txt"), "ROLLBACK checks=" + checks.get() + " sends=0");
                    return;
                }
                try (var db = connection(); var query = db.createStatement(); var rows = query.executeQuery("SELECT COUNT(*) FROM delivery")) {
                    rows.next();
                    if (rows.getInt(1) == 1) {
                        if (checks.get() == 0) throw new IllegalStateException("恢复进程尚未执行 broker 回查");
                        assertDatabase(true);
                        Files.writeString(directory.resolve("result.txt"), outcome + " checks=" + checks.get() + " sends=0");
                        return;
                    }
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("90 秒内未完成无发送冷启动回查");
        }
    }

    static void assertDatabase(boolean committed) throws Exception {
        var original = Files.readAllLines(directory.resolve("event.txt"));
        try (var db = connection(); var query = db.createStatement()) {
            try (var rows = query.executeQuery("SELECT COUNT(*) FROM fact")) {
                rows.next(); if (rows.getInt(1) != (committed ? 1 : 0)) throw new IllegalStateException("事实数量错误");
            }
            try (var rows = query.executeQuery("SELECT id, completed_at, audits FROM delivery")) {
                if (!committed) { if (rows.next()) throw new IllegalStateException("回滚消息被投递"); return; }
                if (!rows.next() || !original.get(0).equals(rows.getString(1)) || !original.get(1).equals(rows.getString(2))
                        || rows.getInt(3) != 1 || rows.next()) throw new IllegalStateException("原事件身份、时间或审计次数错误");
            }
            try (var rows = query.executeQuery("SELECT id, completed_at, audits FROM fact")) {
                if (!rows.next() || !original.get(0).equals(rows.getString(1)) || !original.get(1).equals(rows.getString(2))
                        || rows.getInt(3) != 1) throw new IllegalStateException("持久事实被重复写入");
            }
        }
    }

    @Bean
    Consumer<Message<EventEnvelope<Map<String, Object>>>> coldEvent() {
        return message -> {
            var event = message.getPayload();
            try (var db = connection(); var insert = db.prepareStatement(
                    "INSERT INTO delivery SELECT ?, ?, 1 WHERE NOT EXISTS (SELECT 1 FROM delivery WHERE id=?)")) {
                insert.setString(1, event.eventId()); insert.setString(2, event.occurredAt().toString()); insert.setString(3, event.eventId());
                insert.executeUpdate();
            } catch (SQLException error) { throw new IllegalStateException(error); }
        };
    }

    @Bean
    TransactionStateChecker coldChecker() {
        return new TransactionStateChecker() {
            public boolean supports(String topic) { return "cold-event".equals(topic); }
            public LocalTransactionState check(EventEnvelope<?> envelope, MessageExt raw) {
                LocalTransactionState state = LocalTransactionState.UNKNOW;
                try (var db = connection(); var query = db.prepareStatement("SELECT state FROM fact WHERE id=?")) {
                    query.setString(1, envelope.eventId());
                    try (var rows = query.executeQuery()) {
                        state = !rows.next() ? LocalTransactionState.ROLLBACK_MESSAGE
                                : "COMMIT".equals(rows.getString(1)) ? LocalTransactionState.COMMIT_MESSAGE : LocalTransactionState.UNKNOW;
                    }
                    Files.writeString(directory.resolve(mode + "-checks.txt"), envelope.eventId() + " " + state + "\n",
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    checks.incrementAndGet();
                    return state;
                } catch (Exception error) { throw new IllegalStateException(error); }
            }
        };
    }
}
