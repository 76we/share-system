-- 动态图片记录原图宽高。
--
-- 为什么需要：前端九宫格用固定宽高比（4/3、16/8）占位，竖图会被裁成横条；
-- 单图卡片想把原比例展示出来，就必须知道这张图是几比几。
-- 更重要的是占位：先按真实比例留出盒子，图片到达时页面不会被顶下去（CLS）。
--
-- 允许 NULL 而不是 NOT NULL DEFAULT 0：
--   1) 升级前已有的老图没有这个信息，只能是 NULL；
--   2) 0 会让前端算出 0 高度，比"没有信息"更糟。前端见到 NULL 就走固定比例兜底。
ALTER TABLE `moment_image`
  ADD COLUMN `width` int DEFAULT NULL COMMENT '原图像素宽（转正后、缩放前），老数据为空' AFTER `url`,
  ADD COLUMN `height` int DEFAULT NULL COMMENT '原图像素高（转正后、缩放前），老数据为空' AFTER `width`;
