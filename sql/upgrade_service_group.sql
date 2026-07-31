-- 服务分组与卡片排序升级脚本
-- 适用版本：MySQL 8.0+

CREATE TABLE `service_group` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '分组ID',
  `environment_id` bigint NOT NULL COMMENT '所属环境ID',
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '分组名称',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '分组展示顺序',
  `created_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_service_group_env_name`(`environment_id`, `name`) USING BTREE,
  INDEX `idx_service_group_env_sort`(`environment_id`, `sort_order`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务展示分组表' ROW_FORMAT = Dynamic;

ALTER TABLE `app_service`
  ADD COLUMN `group_id` bigint NULL DEFAULT NULL COMMENT '服务展示分组ID，NULL表示未分组' AFTER `environment_id`,
  ADD COLUMN `sort_order` int NOT NULL DEFAULT 0 COMMENT '服务在分组内的展示顺序' AFTER `group_id`,
  ADD INDEX `idx_env_group_sort` (`environment_id`, `group_id`, `sort_order`);

UPDATE `app_service` AS service
INNER JOIN (
  SELECT
    `id`,
    ROW_NUMBER() OVER (
      PARTITION BY `environment_id`
      ORDER BY `created_time` DESC, `id` DESC
    ) - 1 AS `initial_sort_order`
  FROM `app_service`
) AS ranked ON ranked.`id` = service.`id`
SET service.`group_id` = NULL,
    service.`sort_order` = ranked.`initial_sort_order`;
