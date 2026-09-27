package com.mars.cloud.mysql.resilience;

import java.sql.SQLException;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Keeps the original pool available to health, transactions, metrics and explicit unwrapping. */
public final class ObservedDataSource extends DelegatingDataSource implements AutoCloseable {
    private final DataSource original;

    public ObservedDataSource(DataSource original, SlowQueryListener listener) {
        super(ProxyDataSourceBuilder.create(original).listener(listener).build());
        this.original = original;
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) { return type.cast(this); }
        return type.isInstance(original) ? type.cast(original) : original.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || type.isInstance(original) || original.isWrapperFor(type);
    }

    @Override
    public void close() throws Exception {
        if (original instanceof AutoCloseable closeable) { closeable.close(); }
    }
}
