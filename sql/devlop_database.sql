/*
 Navicat Premium Dump SQL

 Source Server         : Ysb_Local
 Source Server Type    : MySQL
 Source Server Version : 80042 (8.0.42)
 Source Host           : 10.10.0.2:23306
 Source Schema         : devlop_database

 Target Server Type    : MySQL
 Target Server Version : 80042 (8.0.42)
 File Encoding         : 65001

 Date: 16/12/2025 14:03:14
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for app_service
-- ----------------------------
DROP TABLE IF EXISTS `app_service`;
CREATE TABLE `app_service`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `environment_id` bigint NOT NULL COMMENT '关联的环境ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '服务名称',
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '服务描述',
  `docker_image` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'Docker镜像地址',
  `docker_params` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'Docker运行参数（JSON格式）',
  `replicas` int NULL DEFAULT 1 COMMENT '副本数量',
  `service_mode` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'replicated' COMMENT '部署模式：replicated(副本模式) / global(全局模式)',
  `external_service_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '外部服务ID（Docker: Service ID, K8s: Deployment Name）',
  `external_service_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '外部服务名称（Docker: 环境-服务名, K8s: Deployment Name）',
  `version` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '版本号',
  `created_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_env_name`(`environment_id` ASC, `name` ASC) USING BTREE,
  INDEX `idx_environment_id`(`environment_id` ASC) USING BTREE,
  INDEX `idx_name`(`name` ASC) USING BTREE,
  INDEX `idx_external_service_id`(`external_service_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务基本信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for environment
-- ----------------------------
DROP TABLE IF EXISTS `environment`;
CREATE TABLE `environment`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '环境名称',
  `color` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '环境颜色',
  `deploy_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '部署方式：docker, jar, k8s',
  `config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '环境配置信息（JSON格式）',
  `need_login` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否需要登录后可见',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '环境表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for replica_metrics
-- ----------------------------
DROP TABLE IF EXISTS `replica_metrics`;
CREATE TABLE `replica_metrics`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `replica_status_id` bigint NOT NULL COMMENT '关联的副本状态ID',
  `service_id` bigint NOT NULL COMMENT '关联的服务ID',
  `replica_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '副本唯一标识',
  `replica_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '副本名称',
  `platform` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '部署平台：docker, k8s',
  `node_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '所在节点名称',
  `namespace` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'K8s命名空间',
  `status` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '当前状态',
  `phase` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'K8s Pod Phase',
  `previous_status` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '上一次的状态（用于检测状态变更）',
  `previous_phase` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '上一次的Phase',
  `is_status_changed` tinyint(1) NULL DEFAULT 0 COMMENT '本次采集是否发生状态变更',
  `cpu_percent` decimal(5, 2) NULL DEFAULT NULL COMMENT 'CPU使用率（百分比，0-100）',
  `memory_usage` bigint NULL DEFAULT NULL COMMENT '内存使用量（字节）',
  `memory_limit` bigint NULL DEFAULT NULL COMMENT '内存限制（字节）',
  `memory_percent` decimal(5, 2) NULL DEFAULT NULL COMMENT '内存使用率（百分比，0-100）',
  `network_rx_bytes` bigint NULL DEFAULT NULL COMMENT '网络接收总字节数',
  `network_tx_bytes` bigint NULL DEFAULT NULL COMMENT '网络发送总字节数',
  `network_rx_rate` bigint NULL DEFAULT NULL COMMENT '网络接收速率（字节/秒）',
  `network_tx_rate` bigint NULL DEFAULT NULL COMMENT '网络发送速率（字节/秒）',
  `disk_read_bytes` bigint NULL DEFAULT NULL COMMENT '磁盘读取总字节数',
  `disk_write_bytes` bigint NULL DEFAULT NULL COMMENT '磁盘写入总字节数',
  `disk_read_rate` bigint NULL DEFAULT NULL COMMENT '磁盘读取速率（字节/秒）',
  `disk_write_rate` bigint NULL DEFAULT NULL COMMENT '磁盘写入速率（字节/秒）',
  `uptime_seconds` bigint NULL DEFAULT NULL COMMENT '运行时间（秒）',
  `restart_count` int NULL DEFAULT 0 COMMENT '重启次数',
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '错误信息',
  `exit_code` int NULL DEFAULT NULL COMMENT '退出码',
  `termination_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '终止原因',
  `event_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '事件类型：Normal, Warning, Error',
  `event_reason` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '事件原因（K8s Event Reason）',
  `event_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '事件消息',
  `collected_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '采集时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_replica_status_id`(`replica_status_id` ASC) USING BTREE,
  INDEX `idx_service_id`(`service_id` ASC) USING BTREE,
  INDEX `idx_replica_id`(`replica_id` ASC) USING BTREE,
  INDEX `idx_platform`(`platform` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_collected_time`(`collected_time` ASC) USING BTREE,
  INDEX `idx_service_collected`(`service_id` ASC, `collected_time` ASC) USING BTREE,
  INDEX `idx_replica_collected`(`replica_id` ASC, `collected_time` ASC) USING BTREE,
  INDEX `idx_status_changed`(`is_status_changed` ASC, `collected_time` ASC) USING BTREE,
  INDEX `idx_event_type`(`event_type` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 816 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '副本监控指标表（包含状态变更历史）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for replica_status
-- ----------------------------
DROP TABLE IF EXISTS `replica_status`;
CREATE TABLE `replica_status`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `service_id` bigint NOT NULL COMMENT '关联的服务ID',
  `replica_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '副本唯一标识（Docker: Task ID, K8s: Pod UID）',
  `replica_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '副本名称（Docker: service.1, K8s: pod-name）',
  `replica_index` int NULL DEFAULT NULL COMMENT '副本索引号（从1开始）',
  `platform` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '部署平台：docker, k8s',
  `namespace` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'K8s命名空间（仅K8s使用）',
  `node_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '所在节点名称',
  `node_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '节点IP地址',
  `status` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '副本状态：running, starting, preparing, ready, assigned, accepted, stopped, shutdown, complete, remove, failed, rejected, error, pending, terminating',
  `phase` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'K8s Pod Phase: Pending, Running, Succeeded, Failed, Unknown',
  `container_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '容器ID（完整ID）',
  `container_id_short` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '容器ID（短ID，前12位）',
  `image` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '镜像名称',
  `image_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '镜像ID',
  `task_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'Docker Swarm Task ID',
  `task_slot` int NULL DEFAULT NULL COMMENT 'Docker Swarm Task Slot',
  `service_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'Docker Swarm Service Name',
  `pod_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'K8s Pod Name',
  `pod_uid` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'K8s Pod UID',
  `deployment_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'K8s Deployment/StatefulSet Name',
  `replicaset_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'K8s ReplicaSet Name',
  `labels` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'K8s Labels (JSON格式)',
  `annotations` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'K8s Annotations (JSON格式)',
  `pod_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'Pod IP地址',
  `host_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'Host IP地址',
  `uptime_seconds` bigint NULL DEFAULT NULL COMMENT '运行时间（秒）',
  `restart_count` int NULL DEFAULT 0 COMMENT '重启次数',
  `start_time` datetime NULL DEFAULT NULL COMMENT '启动时间',
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '错误信息（状态为failed/error时）',
  `exit_code` int NULL DEFAULT NULL COMMENT '退出码',
  `termination_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '终止原因',
  `last_state_change` datetime NULL DEFAULT NULL COMMENT '最后状态变更时间',
  `cpu_request` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'CPU请求量（如：500m, 1）',
  `cpu_limit` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'CPU限制量',
  `memory_request` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '内存请求量（如：512Mi, 1Gi）',
  `memory_limit` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '内存限制量',
  `created_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_service_replica`(`service_id` ASC, `replica_id` ASC) USING BTREE,
  INDEX `idx_service_id`(`service_id` ASC) USING BTREE,
  INDEX `idx_replica_id`(`replica_id` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_platform`(`platform` ASC) USING BTREE,
  INDEX `idx_node_name`(`node_name` ASC) USING BTREE,
  INDEX `idx_pod_name`(`pod_name` ASC) USING BTREE,
  INDEX `idx_namespace`(`namespace` ASC) USING BTREE,
  INDEX `idx_deployment_name`(`deployment_name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 12 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '副本状态表（兼容Docker和K8s）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for service_metrics
-- ----------------------------
DROP TABLE IF EXISTS `service_metrics`;
CREATE TABLE `service_metrics`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `service_id` bigint NOT NULL COMMENT '关联的服务ID',
  `cpu_percent` decimal(5, 2) NULL DEFAULT NULL COMMENT 'CPU使用率（百分比，0-100）',
  `memory_usage` bigint NULL DEFAULT NULL COMMENT '内存使用量（字节）',
  `memory_limit` bigint NULL DEFAULT NULL COMMENT '内存限制（字节）',
  `memory_percent` decimal(5, 2) NULL DEFAULT NULL COMMENT '内存使用率（百分比，0-100）',
  `network_rx_rate` bigint NULL DEFAULT NULL COMMENT '网络接收速率（字节/秒）',
  `network_tx_rate` bigint NULL DEFAULT NULL COMMENT '网络发送速率（字节/秒）',
  `disk_read_rate` bigint NULL DEFAULT NULL COMMENT '磁盘读取速率（字节/秒）',
  `disk_write_rate` bigint NULL DEFAULT NULL COMMENT '磁盘写入速率（字节/秒）',
  `collected_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '采集时间',
  `created_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_service_id`(`service_id` ASC) USING BTREE,
  INDEX `idx_collected_time`(`collected_time` ASC) USING BTREE,
  INDEX `idx_service_collected`(`service_id` ASC, `collected_time` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务监控指标表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for service_status
-- ----------------------------
DROP TABLE IF EXISTS `service_status`;
CREATE TABLE `service_status`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `service_id` bigint NOT NULL COMMENT '关联的服务ID',
  `status` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '服务状态：running, stopped, error等',
  `healthy_instances` int NULL DEFAULT 0 COMMENT 'Healthy instance count',
  `instances` int NULL DEFAULT 0 COMMENT 'Actual running instance count',
  `desired_instances` int NULL DEFAULT 0 COMMENT 'Desired instance count',
  `last_deploy_time` datetime NULL DEFAULT NULL COMMENT 'Last deploy time',
  `last_deploy_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'Last deployed by',
  `created_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_service_id`(`service_id` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务状态表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for cd_user
-- ----------------------------
DROP TABLE IF EXISTS `cd_user`;
CREATE TABLE `cd_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户名',
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '密码',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_username`(`username` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
