package com.mars.cloud.mysql;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mars.cloud.mysql.entity.BaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;

/**
 * 持久化契约测试的宿主。
 *
 * <p>只装配两个自动配置：MyBatis-Plus 的（由 starter 的 imports 提供）与核心 ID 的
 * （显式 import）。这样测试覆盖的是框架给出的持久化契约，不掺业务配置。
 */
public class MysqlTestApplication {

    /**
     * 测试实体：只加业务字段，审计字段全部来自 {@link BaseEntity}。
     */
    @TableName("biz_widget")
    public static class Widget extends BaseEntity {

        private String name;

        private Integer price;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getPrice() {
            return price;
        }

        public void setPrice(Integer price) {
            this.price = price;
        }
    }

    @Mapper
    public interface WidgetMapper extends BaseMapper<Widget> {
    }

    /**
     * 显式指定两个自动配置：
     * <ul>
     *   <li>{@code MysqlMybatisPlusAutoConfiguration} —— 被测对象（拦截器、ID 生成器、审计填充）</li>
     *   <li>{@code IdAutoConfiguration} —— 提供 IdGenerator，验证「ID 生成器接入雪花」这条链路</li>
     * </ul>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ImportAutoConfiguration({
            com.mars.cloud.mysql.autoconfigure.MysqlMybatisPlusAutoConfiguration.class,
            com.mars.cloud.core.autoconfigure.IdAutoConfiguration.class
    })
    @MapperScan(basePackageClasses = WidgetMapper.class)
    public static class App {
    }
}
