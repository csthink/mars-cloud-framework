package com.mars.cloud.mysql.resilience;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs fixed execution metadata and a digest, never JDBC text, parameters or exceptions. */
public final class SlowQueryListener implements QueryExecutionListener {
    private static final Logger LOG = LoggerFactory.getLogger(SlowQueryListener.class);
    private final String dataSourceName;
    private final long thresholdMillis;

    public SlowQueryListener(String dataSourceName, Duration threshold) {
        if (threshold.isNegative() || threshold.isZero() || threshold.compareTo(Duration.ofSeconds(1)) > 0) {
            throw new IllegalArgumentException("Slow query threshold must be positive and no greater than one second");
        }
        this.dataSourceName = dataSourceName.matches("[A-Za-z0-9_.-]{1,80}") ? dataSourceName : "dataSource";
        this.thresholdMillis = Math.max(1, (threshold.toNanos() + 999_999) / 1_000_000);
    }

    @Override
    public void beforeQuery(ExecutionInfo execution, List<QueryInfo> queries) { }

    @Override
    public void afterQuery(ExecutionInfo execution, List<QueryInfo> queries) {
        if (execution.getElapsedTime() < thresholdMillis) { return; }
        String fingerprint = fingerprint(queries);
        int batchSize = execution.isBatch() ? execution.getBatchSize() : 0;
        LOG.atWarn().addKeyValue("event.type", "jdbc.slow")
                .addKeyValue("duration.ms", execution.getElapsedTime())
                .addKeyValue("datasource.name", dataSourceName)
                .addKeyValue("statement.type", execution.getStatementType())
                .addKeyValue("batch.size", batchSize)
                .addKeyValue("success", execution.isSuccess())
                .addKeyValue("sql.fingerprint", fingerprint)
                .log("event.type=jdbc.slow duration.ms={} datasource.name={} statement.type={} batch.size={} success={} sql.fingerprint={}",
                        execution.getElapsedTime(), dataSourceName, execution.getStatementType(), batchSize, execution.isSuccess(), fingerprint);
    }

    private static String fingerprint(List<QueryInfo> queries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (QueryInfo query : queries) {
                byte[] bytes = query.getQuery().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
