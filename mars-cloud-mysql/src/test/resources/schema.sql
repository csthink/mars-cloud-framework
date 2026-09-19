-- 持久化契约测试用的表。
-- 注意 version 列存在，但 BaseEntity 里的 version 字段目前是注释掉的——
-- 这正好用来验证「实体没有该字段时审计填充不会炸」。
DROP TABLE IF EXISTS biz_widget;
CREATE TABLE biz_widget (
    id          VARCHAR(32) PRIMARY KEY,
    name        VARCHAR(64) NOT NULL,
    price       INT,
    create_time TIMESTAMP,
    create_by   VARCHAR(64),
    modify_time TIMESTAMP,
    modify_by   VARCHAR(64),
    version     INT
);
