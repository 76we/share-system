-- ============================================================================
-- 尘叙阁（CHEN XU PAVILION）数据库结构脚本
--
-- 用法（服务器上）：
--   mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS sharing DEFAULT CHARSET utf8mb4;"
--   mysql -ushare -p sharing < schema.sql
--
-- 重要：本脚本可重复执行且【不会删除任何已有数据】。
--   早期版本由 mysqldump 导出，每张表前都带 DROP TABLE IF EXISTS，
--   上线后如果有人照着文档再执行一次，整个库会被清空。
--   现在全部改成 CREATE TABLE IF NOT EXISTS + INSERT IGNORE。
--
-- 说明：只包含表结构 + 话题初始数据，不含用户/动态等业务数据。
-- ============================================================================

-- ---------------------------------------------------------------- 用户
CREATE TABLE IF NOT EXISTS `user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(64) NOT NULL COMMENT '用户名',
  `password` varchar(128) NOT NULL COMMENT 'BCrypt加密后的密码',
  `nickname` varchar(64) DEFAULT NULL COMMENT '昵称',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像URL',
  `email` varchar(128) DEFAULT NULL COMMENT '邮箱',
  `bio` varchar(500) DEFAULT NULL COMMENT '个人简介',
  `status` tinyint DEFAULT '1' COMMENT '1正常 0禁用（登录与 token 校验都会检查）',
  `wall_visibility` tinyint NOT NULL DEFAULT '0' COMMENT '照片墙可见性 0私密 1公开 2仅好友',
  `token_version` int NOT NULL DEFAULT '0' COMMENT 'token 版本号，改密码后 +1 使旧 token 失效',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

-- ---------------------------------------------------------------- 话题
CREATE TABLE IF NOT EXISTS `topic` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL COMMENT '话题名，如旅行',
  `description` varchar(255) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_topic_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 动态
CREATE TABLE IF NOT EXISTS `moment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(200) DEFAULT NULL COMMENT '标题',
  `content` text NOT NULL COMMENT '所见所感正文',
  `user_id` bigint NOT NULL COMMENT '作者',
  `topic_id` bigint DEFAULT NULL COMMENT '话题',
  `location` varchar(128) DEFAULT NULL COMMENT '地点',
  `mood` varchar(32) DEFAULT NULL COMMENT '心情',
  `view_count` int DEFAULT '0',
  `like_count` int DEFAULT '0',
  `comment_count` int DEFAULT '0',
  `collect_count` int DEFAULT '0',
  `status` tinyint DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_topic_time` (`topic_id`,`create_time`),
  KEY `idx_status_time` (`status`,`create_time`),
  -- 个人中心 / 照片墙都是「某人的已发布动态按时间倒序」，走这条复合索引可免掉 filesort
  KEY `idx_user_status_time` (`user_id`,`status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分享动态表';

-- ---------------------------------------------------------------- 动态图片
CREATE TABLE IF NOT EXISTS `moment_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `moment_id` bigint NOT NULL COMMENT '所属分享',
  `url` varchar(255) NOT NULL COMMENT '图片URL',
  `sort_order` int DEFAULT '0' COMMENT '显示顺序',
  PRIMARY KEY (`id`),
  KEY `idx_moment` (`moment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分享图片表';

-- ---------------------------------------------------------------- 评论
CREATE TABLE IF NOT EXISTS `moment_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `moment_id` bigint NOT NULL COMMENT '动态ID',
  `user_id` bigint NOT NULL COMMENT '评论用户ID',
  `parent_id` bigint DEFAULT '0' COMMENT '父评论ID，0=一级评论',
  `reply_user_id` bigint DEFAULT NULL COMMENT '回复目标用户ID',
  `content` varchar(1000) NOT NULL COMMENT '评论内容',
  `like_count` int DEFAULT '0' COMMENT '点赞数',
  `status` tinyint DEFAULT '1' COMMENT '状态 1正常 0删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_moment_status` (`moment_id`,`status`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态评论表';

-- ---------------------------------------------------------------- 点赞关系
CREATE TABLE IF NOT EXISTS `user_like` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户id',
  `target_id` bigint NOT NULL COMMENT '目标id（动态/评论/照片id）',
  `target_type` tinyint NOT NULL COMMENT '1动态 2评论 3照片',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_target` (`user_id`,`target_id`,`target_type`),
  KEY `idx_target` (`target_id`,`target_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户点赞关系表';

-- ---------------------------------------------------------------- 收藏关系
CREATE TABLE IF NOT EXISTS `user_collect` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户id',
  `moment_id` bigint NOT NULL COMMENT '分享帖子id',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_moment` (`user_id`,`moment_id`),
  KEY `idx_moment` (`moment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户收藏表';

-- ---------------------------------------------------------------- 好友关系
CREATE TABLE IF NOT EXISTS `friendship` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_a` bigint NOT NULL COMMENT '较小用户id',
  `user_b` bigint NOT NULL COMMENT '较大用户id',
  `from_user` bigint NOT NULL COMMENT '发起申请者',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0申请中 1已同意',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pair` (`user_a`,`user_b`),
  KEY `idx_user_status` (`user_a`,`status`),
  KEY `idx_user_b_status` (`user_b`,`status`),
  KEY `idx_from` (`from_user`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='好友关系表(双向)';

-- ---------------------------------------------------------------- 通知
CREATE TABLE IF NOT EXISTS `notification` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '接收者',
  `actor_id` bigint NOT NULL COMMENT '发起人',
  `type` tinyint NOT NULL COMMENT '1赞动态 2赞照片 3评论动态 4回复评论 5好友申请 6同意申请',
  `target_id` bigint NOT NULL COMMENT '目标id(动态id/照片id/用户id)',
  `content` varchar(300) DEFAULT NULL COMMENT '通知摘要',
  `is_read` tinyint NOT NULL DEFAULT '0' COMMENT '0未读 1已读',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`,`is_read`),
  KEY `idx_user_time` (`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知表';

-- ---------------------------------------------------------------- 相册
CREATE TABLE IF NOT EXISTS `album` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL DEFAULT '0' COMMENT '相册主人',
  `title` varchar(100) NOT NULL,
  `description` varchar(500) DEFAULT '',
  `cover` varchar(255) DEFAULT '',
  `sort` int DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_album_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------- 相册照片
CREATE TABLE IF NOT EXISTS `photo` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `album_id` bigint NOT NULL,
  `url` varchar(255) NOT NULL,
  `caption` varchar(200) DEFAULT '',
  `orientation` varchar(20) DEFAULT 'landscape',
  `sort_order` int DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_photo_album` (`album_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- 话题初始数据（INSERT IGNORE：重复执行不会报错，也不会覆盖已有话题）
-- ============================================================================
INSERT IGNORE INTO `topic` (`id`, `name`, `description`) VALUES
  (1, '旅行', '记录路途所见'),
  (2, '美食', '人间烟火'),
  (3, '摄影', '光影之间'),
  (4, '日常', '平凡日子'),
  (9, '影视', '光影故事');
