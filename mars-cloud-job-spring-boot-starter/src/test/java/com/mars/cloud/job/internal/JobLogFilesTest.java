package com.mars.cloud.job.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class JobLogFilesTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2026-09-27T03:10:00+08:00 */
    private static final long TRIGGER_TIME = Instant.parse("2026-09-26T19:10:00Z").toEpochMilli();

    @TempDir
    Path base;

    private JobLogFiles files(Instant now) {
        JobLogFiles files = new JobLogFiles(base, Clock.fixed(now, ZONE));
        files.initialize();
        return files;
    }

    @Test
    void oneFilePerExecutionUnderTheTriggerDay() throws IOException {
        JobLogFiles files = files(Instant.ofEpochMilli(TRIGGER_TIME));
        files.append(TRIGGER_TIME, 42, "hello");
        Path file = base.resolve("2026-09-27").resolve("42.log");
        assertThat(file).exists();
        assertThat(Files.readString(file)).isEqualTo("2026-09-27 03:10:00.000 [" + Thread.currentThread().getName() + "] hello"
                + System.lineSeparator());
    }

    @Test
    void readsAreLineBasedAndReportNothingNewAsFromGreaterThanTo() {
        JobLogFiles files = files(Instant.ofEpochMilli(TRIGGER_TIME));
        files.begin(7);
        files.append(TRIGGER_TIME, 7, "one");
        files.append(TRIGGER_TIME, 7, "two");

        Protocol.LogResult first = files.read(TRIGGER_TIME, 7, 1);
        assertThat(first.fromLineNum()).isEqualTo(1);
        assertThat(first.toLineNum()).isEqualTo(2);
        assertThat(first.logContent()).contains("] one\n").contains("] two\n");
        assertThat(first.isEnd()).as("still running").isFalse();

        Protocol.LogResult nothingNew = files.read(TRIGGER_TIME, 7, 3);
        assertThat(nothingNew.fromLineNum()).isEqualTo(3);
        assertThat(nothingNew.toLineNum()).isEqualTo(2);
        assertThat(nothingNew.logContent()).isEmpty();

        files.append(TRIGGER_TIME, 7, "three");
        files.end(7);
        Protocol.LogResult rest = files.read(TRIGGER_TIME, 7, 3);
        assertThat(rest.toLineNum()).isEqualTo(3);
        assertThat(rest.logContent()).contains("] three");
        assertThat(rest.isEnd()).isTrue();
    }

    @Test
    void aLargeLogIsReturnedInPages() {
        JobLogFiles files = files(Instant.ofEpochMilli(TRIGGER_TIME));
        for (int line = 1; line <= JobLogFiles.MAX_LINES_PER_READ + 5; line++) {
            files.append(TRIGGER_TIME, 8, "line " + line);
        }
        Protocol.LogResult page = files.read(TRIGGER_TIME, 8, 1);
        assertThat(page.toLineNum()).isEqualTo(JobLogFiles.MAX_LINES_PER_READ);
        assertThat(page.isEnd()).isFalse();
        Protocol.LogResult next = files.read(TRIGGER_TIME, 8, page.toLineNum() + 1);
        assertThat(next.toLineNum()).isEqualTo(JobLogFiles.MAX_LINES_PER_READ + 5);
        assertThat(next.logContent().lines().findFirst().orElseThrow()).endsWith("] line 1001");
        assertThat(next.isEnd()).isTrue();
    }

    @Test
    void aMissingFileIsQuietWhileQueuedAndExplainedAfterwards() {
        JobLogFiles files = files(Instant.ofEpochMilli(TRIGGER_TIME));
        files.begin(9);
        assertThat(files.read(TRIGGER_TIME, 9, 1).logContent()).isEmpty();
        files.end(9);
        Protocol.LogResult gone = files.read(TRIGGER_TIME, 9, 1);
        assertThat(gone.logContent()).isEqualTo(JobLogFiles.MISSING_FILE);
        assertThat(gone.fromLineNum()).isGreaterThan(gone.toLineNum());
    }

    @Test
    void daysOlderThanTheRetentionAreDeleted() throws IOException {
        Files.createDirectories(base.resolve("2026-09-20"));
        Files.writeString(base.resolve("2026-09-20").resolve("1.log"), "old");
        Files.createDirectories(base.resolve("2026-09-21"));
        Files.createDirectories(base.resolve("2026-09-27"));
        Files.createDirectories(base.resolve("not-a-day"));
        files(Instant.ofEpochMilli(TRIGGER_TIME)).purge(Duration.ofDays(7));
        assertThat(base.resolve("2026-09-20")).doesNotExist();
        assertThat(base.resolve("2026-09-21")).exists();
        assertThat(base.resolve("2026-09-27")).exists();
        assertThat(base.resolve("not-a-day")).exists();
    }
}
