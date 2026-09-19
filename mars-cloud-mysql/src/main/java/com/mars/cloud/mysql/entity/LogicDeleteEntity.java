package com.mars.cloud.mysql.entity;

/**
 * @since 2025-10-30 16:36
 */

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 软删除实体：在 BaseEntity 基础上增加 deleted 字段
 */
@Getter
@Setter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public abstract class LogicDeleteEntity extends BaseEntity {

    /**
     * 逻辑删除：0-未删，1-已删
     */
    @TableLogic(value = "0", delval = "1")
    @TableField(value = "deleted")
    private Integer deleted;

    /**
     * 逻辑删除时间：null-未删，有值-已删
     */
    @TableField(value = "delete_time")
    private LocalDateTime deleteTime;

}
