package com.mars.cloud.job.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 执行日志文件：一次执行一个文件，{@code <目录>/<yyyy-MM-dd>/<logId>.log}，日期取调度中心给出的触发时间。
 *
 * <p>调度中心的执行日志页面经执行器的 {@code /log} 接口按行分页读取这些文件；它们是执行器唯一写入的文件。
 * 服务日志另有同样内容的 INFO 行，按 traceId 可以在日志系统里查到。
 */
public final class JobLogFiles {

    /** 单次读取最多返回的行数。 */
    static final int MAX_LINES_PER_READ = 1000;
    static final String MISSING_FILE = "执行日志文件不存在：已超过保留期被清理，或执行器在这次执行之后重启过";

    private static final Logger log = LoggerFactory.getLogger(JobLogFiles.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path base;
    private final Clock clock;
    private final ZoneId zone;
    /** 已接受、尚未结束的执行（排队中与执行中）。结束前读取不会报告结束。 */
    private final Set<Long> active = ConcurrentHashMap.newKeySet();
    private volatile boolean writeFailureReported;

    public JobLogFiles(Path base, Clock clock) {
        this.base = base;
        this.clock = clock;
        this.zone = clock.getZone();
    }

    /** 创建日志目录；目录不可写时启动失败。 */
    public void initialize() {
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建执行日志目录 " + base + "：" + e.getMessage(), e);
        }
        if (!Files.isWritable(base)) {
            throw new IllegalStateException("执行日志目录不可写：" + base);
        }
    }

    Path path(long logDateTime, long logId) {
        return base.resolve(DAY.format(Instant.ofEpochMilli(logDateTime).atZone(zone))).resolve(logId + ".log");
    }

    /** 一次触发被接受：标记为进行中。 */
    void begin(long logId) {
        active.add(logId);
    }

    /** 一次触发结束（执行完成或排队中被取消）。 */
    void end(long logId) {
        active.remove(logId);
    }

    boolean isActive(long logId) {
        return active.contains(logId);
    }

    /**
     * 追加一行（消息里的换行原样保留）。写入失败不影响任务执行：第一次失败打一条告警，之后不再重复。
     */
    void append(long logDateTime, long logId, String message) {
        Path file = path(logDateTime, logId);
        String line = LINE_TIME.format(clock.instant().atZone(zone)) + " [" + Thread.currentThread().getName() + "] "
                + message + System.lineSeparator();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!writeFailureReported) {
                writeFailureReported = true;
                log.warn("写执行日志文件失败，调度中心页面将看不到这部分日志：file={}，原因：{}", file, e.toString());
            }
        }
    }

    /**
     * 从第 {@code fromLineNum} 行（从 1 开始）读起，最多 {@link #MAX_LINES_PER_READ} 行。
     * 没有新行时 {@code toLineNum = fromLineNum - 1}，调度中心据此与执行结果一起判断结束。
     */
    Protocol.LogResult read(long logDateTime, long logId, int fromLineNum) {
        int from = Math.max(1, fromLineNum);
        Path file = path(logDateTime, logId);
        StringBuilder content = new StringBuilder();
        int count = 0;
        boolean endOfFile = true;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (int skipped = 1; skipped < from; skipped++) {
                if (reader.readLine() == null) {
                    return new Protocol.LogResult(from, from - 1, "", false);
                }
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (count == MAX_LINES_PER_READ) {
                    endOfFile = false;
                    break;
                }
                content.append(line).append('\n');
                count++;
            }
        } catch (NoSuchFileException missing) {
            if (isActive(logId)) {
                return new Protocol.LogResult(from, from - 1, "", false);
            }
            return new Protocol.LogResult(from, from - 1, MISSING_FILE, false);
        } catch (IOException e) {
            throw new UncheckedIOException("读取执行日志失败：" + file, e);
        }
        boolean finished = endOfFile && !isActive(logId);
        return new Protocol.LogResult(from, from - 1 + count, content.toString(), finished);
    }

    /** 删除早于保留期的日期目录；目录名不是日期的不动。 */
    void purge(Duration retention) {
        LocalDate oldestKept = LocalDate.now(clock).minusDays(retention.toDays() - 1);
        try (DirectoryStream<Path> days = Files.newDirectoryStream(base, Files::isDirectory)) {
            for (Path day : days) {
                LocalDate date;
                try {
                    date = LocalDate.parse(day.getFileName().toString(), DAY);
                } catch (DateTimeParseException notADay) {
                    continue;
                }
                if (date.isBefore(oldestKept)) {
                    deleteTree(day);
                }
            }
        } catch (IOException e) {
            log.warn("清理过期执行日志失败，下次清理时重试：dir={}，原因：{}", base, e.toString());
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
