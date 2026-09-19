package com.mars.cloud.mysql.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * @since 2025-10-30 15:18
 * 通用实体类，定义所有实体类公用的属性
 */
@Setter
@Getter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class BaseEntity implements Serializable {

    /**
     * 主键ID（雪花）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID) // 使用自定义 IdentifierGenerator
    private String id;

    /**
     * 创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 创建人（用户ID/用户名，按你系统统一）
     */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    private String createBy;

    /**
     * 修改时间
     */
    @TableField(value = "modify_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime modifyTime;

    /**
     * 修改人
     */
    @TableField(value = "modify_by", fill = FieldFill.INSERT_UPDATE)
    private String modifyBy;

    /**
     * 乐观锁
     */
    //@Version
    //@TableField(value = "version")
    //private Integer version;
}
