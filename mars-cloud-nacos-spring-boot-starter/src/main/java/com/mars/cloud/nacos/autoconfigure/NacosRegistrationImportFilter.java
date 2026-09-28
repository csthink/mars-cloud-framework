package com.mars.cloud.nacos.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

/** Disables the SDK registration path while retaining configuration and discovery. */
public final class NacosRegistrationImportFilter implements AutoConfigurationImportFilter {
    @Override public boolean[] match(String[] candidates, AutoConfigurationMetadata metadata) {
        boolean[] accepted=new boolean[candidates.length];
        for (int i=0;i<candidates.length;i++) accepted[i]=!"com.alibaba.cloud.nacos.registry.NacosServiceRegistryAutoConfiguration".equals(candidates[i]);
        return accepted;
    }
}
