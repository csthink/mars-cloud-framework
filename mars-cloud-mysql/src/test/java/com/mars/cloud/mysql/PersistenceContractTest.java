package com.mars.cloud.mysql;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mars.cloud.mysql.MysqlTestApplication.App;
import com.mars.cloud.mysql.MysqlTestApplication.Widget;
import com.mars.cloud.mysql.MysqlTestApplication.WidgetMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 持久化契约测试（T0.4 补，也是 T0.5 的验收前置）。
 *
 * <p>固定下来的是框架承诺给业务侧的那部分行为：审计字段自动填充、ID 分配、分页可用。
 * 这些一旦漂移，所有业务表的写入都会受影响，所以必须有测试而不是靠人工验证。
 */
@SpringBootTest(classes = App.class)
class PersistenceContractTest {

    @Autowired
    private WidgetMapper widgetMapper;

    @Test
    @DisplayName("插入：ID 由雪花生成器赋值")
    void insert_assignsSnowflakeId() {
        Widget widget = new Widget();
        widget.setName("id-check");
        widget.setPrice(1);

        widgetMapper.insert(widget);

        assertThat(widget.getId()).isNotBlank();
        assertThat(widget.getId()).matches("\\d{15,20}");
    }

    @Test
    @DisplayName("插入：审计字段自动填充，操作人默认 system")
    void insert_fillsAuditFields() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(5);

        Widget widget = new Widget();
        widget.setName("audit-check");
        widget.setPrice(2);
        widgetMapper.insert(widget);

        Widget saved = widgetMapper.selectById(widget.getId());
        assertThat(saved.getCreateTime()).isNotNull().isAfter(before);
        assertThat(saved.getModifyTime()).isNotNull();
        assertThat(saved.getCreateBy()).isEqualTo("system");
        assertThat(saved.getModifyBy()).isEqualTo("system");
    }

    @Test
    @DisplayName("更新：modify* 被刷新，create* 保持不变")
    void update_refreshesOnlyModifyFields() {
        Widget widget = new Widget();
        widget.setName("update-check");
        widget.setPrice(3);
        widgetMapper.insert(widget);

        Widget inserted = widgetMapper.selectById(widget.getId());
        LocalDateTime createdTime = inserted.getCreateTime();

        inserted.setPrice(30);
        widgetMapper.updateById(inserted);

        Widget updated = widgetMapper.selectById(widget.getId());
        assertThat(updated.getPrice()).isEqualTo(30);
        assertThat(updated.getCreateTime()).isEqualTo(createdTime);
        assertThat(updated.getModifyTime()).isAfterOrEqualTo(createdTime);
        assertThat(updated.getModifyBy()).isEqualTo("system");
    }

    @Test
    @DisplayName("实体没有 version 字段时，审计填充不会因 strictInsertFill 失败")
    void insert_toleratesMissingVersionField() {
        Widget widget = new Widget();
        widget.setName("no-version-field");
        widget.setPrice(4);

        // BaseEntity 里的 version 目前是注释掉的，填充逻辑对不存在的字段必须静默跳过
        assertThat(widgetMapper.insert(widget)).isEqualTo(1);
    }

    @Test
    @DisplayName("分页拦截器生效：返回分页对象而不是全量列表")
    void pagination_isIntercepted() {
        for (int i = 0; i < 5; i++) {
            Widget widget = new Widget();
            widget.setName("page-" + i);
            widget.setPrice(i);
            widgetMapper.insert(widget);
        }

        Page<Widget> page = new Page<>(1, 2);
        IPage<Widget> result = widgetMapper.selectPage(page, new QueryWrapper<Widget>()
                .likeRight("name", "page-")
                .orderByAsc("price"));

        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(5);
        assertThat(result.getRecords().get(0).getPrice()).isZero();
    }

    @Test
    @DisplayName("查询：条件构造器与 selectList 正常")
    void query_returnsInsertedRows() {
        Widget widget = new Widget();
        widget.setName("query-check");
        widget.setPrice(99);
        widgetMapper.insert(widget);

        List<Widget> found = widgetMapper.selectList(new QueryWrapper<Widget>().eq("name", "query-check"));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getPrice()).isEqualTo(99);
    }
}
