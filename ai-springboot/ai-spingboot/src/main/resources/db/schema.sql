-- ============================================================
-- 心理健康助手 数据库初始化脚本
-- 说明：原项目缺少建表脚本，此文件依据实体类字段补齐，
--       可直接执行：mysql -uroot -p < schema.sql
--
-- 注意：库名 mental_health_assisant 沿用了本项目实际使用的名称
--       （拼写少了 t，但与 application.yml 的连接串保持一致）。
--       如需改名，请同步修改 application.yml 中的 jdbc url。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `mental_health_assisant`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `mental_health_assisant`;

-- ------------------------------------------------------------
-- 用户表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`   VARCHAR(50)  NOT NULL COMMENT '用户名',
    `email`      VARCHAR(100) NOT NULL COMMENT '邮箱',
    `phone`      VARCHAR(20)           DEFAULT NULL COMMENT '手机号',
    `password`   VARCHAR(255) NOT NULL COMMENT '密码（BCrypt加密）',
    `nickname`   VARCHAR(50)           DEFAULT NULL COMMENT '昵称',
    `avatar`     VARCHAR(255)          DEFAULT NULL COMMENT '头像地址',
    `gender`     INT                   DEFAULT NULL COMMENT '性别 1:男 2:女',
    `birthday`   DATE                  DEFAULT NULL COMMENT '生日',
    `user_type`  INT                   DEFAULT 1 COMMENT '用户类型 1:普通用户 2:管理员',
    `status`     INT                   DEFAULT 1 COMMENT '状态 0:禁用 1:正常',
    `created_at` DATETIME              DEFAULT NULL COMMENT '创建时间',
    `updated_at` DATETIME              DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户表';

-- ------------------------------------------------------------
-- 咨询会话表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `consultation_session` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    `user_id`               BIGINT       NOT NULL COMMENT '归属用户ID',
    `session_title`         VARCHAR(200)          DEFAULT NULL COMMENT '会话标题',
    `started_at`            DATETIME              DEFAULT NULL COMMENT '开始时间',
    `last_emotion_analysis` TEXT                  DEFAULT NULL COMMENT '最近一次情绪分析结果(JSON)',
    `last_emotion_updated_at` DATETIME            DEFAULT NULL COMMENT '情绪分析更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_started_at` (`started_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='咨询会话表';

-- ------------------------------------------------------------
-- 咨询消息表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `consultation_message` (
    `id`           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `session_id`   BIGINT   NOT NULL COMMENT '会话ID',
    `sender_type`  INT      NOT NULL COMMENT '发送者 1:用户 2:AI助手',
    `message_type` INT      NOT NULL DEFAULT 1 COMMENT '消息类型 1:文本',
    `content`      TEXT     NOT NULL COMMENT '消息内容',
    `emotion_tag`  VARCHAR(50)       DEFAULT NULL COMMENT '情绪标签',
    `ai_model`     VARCHAR(50)       DEFAULT NULL COMMENT '使用的AI模型',
    `created_at`   DATETIME          DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_session_id` (`session_id`),
    KEY `idx_session_created` (`session_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='咨询消息表';

-- ------------------------------------------------------------
-- 知识文章分类表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `knowledge_category` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    `parent_id`     BIGINT                DEFAULT 0 COMMENT '父级分类ID，顶级为0',
    `category_name` VARCHAR(100) NOT NULL COMMENT '分类名称',
    `category_code` VARCHAR(50)           DEFAULT NULL COMMENT '分类编码，唯一',
    `description`   TEXT                  COMMENT '分类描述',
    `sort_order`    INT                   DEFAULT 0 COMMENT '排序值，越小越靠前',
    `status`        TINYINT               DEFAULT 1 COMMENT '状态 0:禁用 1:启用',
    `created_at`    DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_category_code` (`category_code`),
    KEY `idx_parent_id` (`parent_id`),
    KEY `idx_sort_order` (`sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='知识文章分类表';

-- ------------------------------------------------------------
-- 知识文章表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `knowledge_article` (
    `id`           VARCHAR(36)  NOT NULL COMMENT '文章ID(UUID)',
    `title`        VARCHAR(200) NOT NULL COMMENT '标题',
    `category_id`  BIGINT                DEFAULT NULL COMMENT '分类ID',
    `author_id`    BIGINT                DEFAULT NULL COMMENT '作者ID（库里不存作者名，作者名由 author_id 关联 user 表得到）',
    `cover_image`  VARCHAR(500)          DEFAULT NULL COMMENT '封面图地址',
    `summary`      VARCHAR(500)          DEFAULT NULL COMMENT '摘要',
    `content`      LONGTEXT              DEFAULT NULL COMMENT '正文内容(HTML)',
    `tags`         VARCHAR(255)          DEFAULT NULL COMMENT '标签，逗号分隔',
    `read_count`   INT                   DEFAULT 0 COMMENT '阅读量',
    `status`       INT                   DEFAULT 1 COMMENT '状态 0:草稿 1:已发布',
    `published_at` DATETIME              DEFAULT NULL COMMENT '发布时间',
    `created_at`   DATETIME              DEFAULT NULL COMMENT '创建时间',
    `updated_at`   DATETIME              DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_category_id` (`category_id`),
    KEY `idx_status_published` (`status`, `published_at`),
    KEY `idx_read_count` (`read_count`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='知识文章表';

-- ------------------------------------------------------------
-- 情绪日记表
-- 说明：本项目核心表之一，AI 情绪分析结果与"情绪趋势"工具都依赖它。
--       (user_id, diary_date) 唯一，保证同一用户同一天只记一条。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `emotion_diary` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日记ID',
    `user_id`               BIGINT       NOT NULL COMMENT '用户ID',
    `diary_date`            DATE         NOT NULL COMMENT '日记日期',
    `mood_score`            TINYINT      NOT NULL COMMENT '情绪评分(1-10)',
    `dominant_emotion`      VARCHAR(50)  DEFAULT NULL COMMENT '主要情绪',
    `emotion_triggers`      TEXT         COMMENT '情绪触发因素',
    `diary_content`         TEXT         COMMENT '日记内容',
    `sleep_quality`         TINYINT      DEFAULT NULL COMMENT '睡眠质量(1-5)',
    `stress_level`          TINYINT      DEFAULT NULL COMMENT '压力水平(1-5)',
    `ai_emotion_analysis`   TEXT         COMMENT 'AI情绪分析结果(JSON格式)',
    `ai_analysis_updated_at` DATETIME    DEFAULT NULL COMMENT 'AI分析更新时间',
    `created_at`            DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `user_date_unique` (`user_id`, `diary_date`),
    KEY `idx_user_diary` (`user_id`, `diary_date`),
    KEY `idx_ai_analysis_time` (`ai_analysis_updated_at`),
    CONSTRAINT `emotion_diary_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='情绪日记表';

-- ------------------------------------------------------------
-- 用户收藏表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_favorite` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '收藏ID',
    `user_id`    BIGINT      NOT NULL COMMENT '用户ID',
    `article_id` VARCHAR(36) NOT NULL COMMENT '文章ID',
    `created_at` DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `user_article_unique` (`user_id`, `article_id`),
    KEY `article_id` (`article_id`),
    CONSTRAINT `user_favorite_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `user_favorite_ibfk_2` FOREIGN KEY (`article_id`) REFERENCES `knowledge_article` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户收藏表';

-- ------------------------------------------------------------
-- 系统文件信息表（文件上传后记录元数据）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_file_info` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文件ID，主键自增',
    `original_name`  VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_path`      VARCHAR(500) NOT NULL COMMENT '文件访问路径',
    `file_size`      BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    `file_type`      VARCHAR(20)  NOT NULL COMMENT '文件类型(IMG/PDF/TXT/DOC/XLS等)',
    `business_type`  VARCHAR(50)  DEFAULT NULL COMMENT '业务类型(avatar/document/attachment)',
    `business_id`    VARCHAR(255) DEFAULT NULL COMMENT '业务对象ID',
    `business_field` VARCHAR(50)  DEFAULT NULL COMMENT '业务字段名',
    `upload_user_id` BIGINT       DEFAULT NULL COMMENT '上传用户ID',
    `is_temp`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否临时文件(0:否 1:是)',
    `status`         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '状态(0:删除 1:正常)',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `expire_time`    DATETIME     DEFAULT NULL COMMENT '过期时间(仅临时文件)',
    PRIMARY KEY (`id`),
    KEY `idx_business` (`business_type`, `business_id`),
    KEY `idx_upload_user` (`upload_user_id`),
    KEY `idx_status` (`status`),
    KEY `idx_temp_expire` (`is_temp`, `expire_time`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='系统文件信息表';

-- ------------------------------------------------------------
-- AI 分析任务表（异步分析队列）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_analysis_task` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    `diary_id`        BIGINT      NOT NULL COMMENT '日记ID',
    `user_id`         BIGINT      NOT NULL COMMENT '用户ID',
    `status`          VARCHAR(20) NOT NULL COMMENT 'PENDING/PROCESSING/COMPLETED/FAILED',
    `task_type`       VARCHAR(20) NOT NULL COMMENT 'AUTO/MANUAL/ADMIN/BATCH',
    `priority`        INT         NOT NULL DEFAULT 2 COMMENT '优先级 1-4',
    `retry_count`     INT         NOT NULL DEFAULT 0 COMMENT '重试次数',
    `max_retry_count` INT         NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    `error_message`   TEXT        COMMENT '错误信息',
    `started_at`      DATETIME    DEFAULT NULL COMMENT '处理开始时间',
    `completed_at`    DATETIME    DEFAULT NULL COMMENT '处理完成时间',
    `created_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_diary_id` (`diary_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_status` (`status`),
    KEY `idx_task_type` (`task_type`),
    KEY `idx_priority` (`priority`),
    KEY `idx_created_at` (`created_at`),
    KEY `idx_status_priority` (`status`, `priority`),
    KEY `idx_status_created_at` (`status`, `created_at`),
    KEY `idx_task_type_created_at` (`task_type`, `created_at`),
    KEY `idx_retry_status` (`status`, `retry_count`, `max_retry_count`),
    CONSTRAINT `fk_ai_task_diary` FOREIGN KEY (`diary_id`) REFERENCES `emotion_diary` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_ai_task_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='AI分析任务表';
