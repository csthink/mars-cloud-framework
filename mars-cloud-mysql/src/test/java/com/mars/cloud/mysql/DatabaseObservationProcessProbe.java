package com.mars.cloud.mysql;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real JDBC process probe; SQL and test parameters must remain absent from observation logs. */
@Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
public class DatabaseObservationProcessProbe {
    interface SlowMapper { @Select("SELECT SLEEP(1.05)") int slow(); }
    public static void main(String[] args) {
        try(var context=SpringApplication.run(DatabaseObservationProcessProbe.class,args)) { }
    }
    @Bean ApplicationRunner observe(DataSource source,JdbcTemplate jdbc,SqlSessionFactory sessions) {
        return args->{
            HikariDataSource pool=source.unwrap(HikariDataSource.class);
            if(pool.getMaximumPoolSize()!=2 || pool.getConnectionTimeout()!=1000 || pool.getValidationTimeout()!=500)
                throw new IllegalStateException("Actual pool limits do not match the probe configuration");
            String table="observation_batch_"+ProcessHandle.current().pid();
            jdbc.execute("CREATE TABLE "+table+"(id integer primary key, value_text varchar(100))");
            MDC.put("traceId","jdbc-observation-probe");
            try {
                jdbc.queryForObject("SELECT SLEEP(1.05)",Integer.class);
                sessions.getConfiguration().addMapper(SlowMapper.class);
                new SqlSessionTemplate(sessions).getMapper(SlowMapper.class).slow();
                try(var connection=source.getConnection();var query=connection.prepareStatement("SELECT SLEEP(?), ?")) {
                    query.setDouble(1,1.05);query.setString(2,"parameter-probe-sensitive");query.execute();
                }
                try(var connection=source.getConnection();var batch=connection.prepareStatement("INSERT INTO "+table+" VALUES (?, IF(SLEEP(?), ?, ?))")) {
                    for(int id=1;id<=2;id++) {
                        batch.setInt(1,id);batch.setDouble(2,.55);batch.setString(3,"batch-probe-sensitive");batch.setString(4,"batch-probe-sensitive");batch.addBatch();
                    }
                    if(batch.executeBatch().length!=2)throw new IllegalStateException("Unexpected batch result");
                }
                jdbc.queryForObject("SELECT 1",Integer.class);
                try(var connection=source.getConnection();var query=connection.createStatement()) {
                    try {query.execute("SELECT exception_probe_sensitive_missing_column");throw new IllegalStateException("Expected SQL failure");}
                    catch(SQLException expected) { }
                }
                try(var first=source.getConnection();var second=source.getConnection()) {
                    long started=System.nanoTime();
                    try(var unexpected=source.getConnection()) {throw new IllegalStateException("Expected pool exhaustion");}
                    catch(SQLException expected) {
                        long millis=(System.nanoTime()-started)/1_000_000;
                        if(millis<800 || millis>1800)throw new IllegalStateException("Pool timeout outside expected range");
                        System.out.println("CHECK pool-exhaustion elapsed_ms="+millis);
                    }
                }
                try(var recovered=source.getConnection()) {if(!recovered.isValid(1))throw new IllegalStateException("Pool did not recover");}
                System.out.println("CHECK jdbc-observation-complete");
            } finally {MDC.remove("traceId");}
        };
    }
}
