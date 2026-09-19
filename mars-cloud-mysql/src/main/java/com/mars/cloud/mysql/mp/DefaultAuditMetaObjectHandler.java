package com.mars.cloud.mysql.mp;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * @since 2025-10-30 16:38
 * 自动填充实现
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultAuditMetaObjectHandler implements MetaObjectHandler {

    /**
     * 获取当前用户名/用户ID等
     */
    private final AuditorProvider auditorProvider;

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "modifyTime", LocalDateTime.class, now);

        String user = auditorProvider.getCurrentAuditor().orElse("system");
        this.strictInsertFill(metaObject, "createBy", String.class, user);
        this.strictInsertFill(metaObject, "modifyBy", String.class, user);

        // 版本默认 0
        this.strictInsertFill(metaObject, "version", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.setFieldValByName("modifyTime", LocalDateTime.now(), metaObject);
        String modifyBy = auditorProvider.getCurrentAuditor().orElse("system");
        this.setFieldValByName("modifyBy", modifyBy, metaObject);
    }

    /**
     * 当前审计人提供器（可从 Security/ThreadLocal/TTL 里拿）
     */
    public interface AuditorProvider {
        Optional<String> getCurrentAuditor();
    }

    /**
     * 一个简单默认实现：总是 "system"（业务可覆盖）
     */
    public static class SystemAuditorProvider implements AuditorProvider {
        @Override
        public Optional<String> getCurrentAuditor() {
            return Optional.of("system");
        }
    }
}
