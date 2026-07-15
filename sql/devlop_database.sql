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
  `status` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '当前状态',
  `cpu_percent` decimal(5, 2) NULL DEFAULT NULL COMMENT 'CPU使用率（百分比，0-100）',
  `memory_usage` bigint NULL DEFAULT NULL COMMENT '内存使用量（字节）',
  `memory_limit` bigint NULL DEFAULT NULL COMMENT '内存限制（字节）',
  `memory_percent` decimal(5, 2) NULL DEFAULT NULL COMMENT '内存使用率（百分比，0-100）',
  `collected_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '采集时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_replica_status_id`(`replica_status_id` ASC) USING BTREE,
  INDEX `idx_service_id`(`service_id` ASC) USING BTREE,
  INDEX `idx_replica_id`(`replica_id` ASC) USING BTREE,
  INDEX `idx_platform`(`platform` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_collected_time`(`collected_time` ASC) USING BTREE,
  INDEX `idx_service_collected`(`service_id` ASC, `collected_time` ASC) USING BTREE,
  INDEX `idx_replica_collected`(`replica_id` ASC, `collected_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '副本监控指标表（每1s一行时序数据）' ROW_FORMAT = Dynamic;

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

-- ----------------------------
-- Table structure for host_info（监控 · 宿主机静态信息）
-- ----------------------------
DROP TABLE IF EXISTS `host_info`;
CREATE TABLE `host_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `environment_id` bigint NOT NULL COMMENT '环境ID',
  `host_key` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '主机唯一键 user@ip:port',
  `ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'IP地址',
  `hostname` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '主机名',
  `swarm_node_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'Swarm 节点 ID',
  `swarm_role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'manager/worker',
  `swarm_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'ready/down',
  `cpu_cores` int NULL DEFAULT NULL COMMENT 'CPU 核数',
  `mem_total` bigint NULL DEFAULT NULL COMMENT '总内存（字节）',
  `disk_total` bigint NULL DEFAULT NULL COMMENT '根分区总量（字节）',
  `last_seen_time` datetime NULL DEFAULT NULL COMMENT '最近一次采集成功时间',
  `created_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_env_host_key`(`environment_id`, `host_key`) USING BTREE,
  INDEX `idx_env`(`environment_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '宿主机静态信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for host_metrics（监控 · 宿主机时序指标 1s）
-- ----------------------------
DROP TABLE IF EXISTS `host_metrics`;
CREATE TABLE `host_metrics` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `host_id` bigint NOT NULL COMMENT '关联 host_info.id',
  `environment_id` bigint NOT NULL COMMENT '环境ID',
  `cpu_percent` decimal(5,2) NULL DEFAULT NULL COMMENT 'CPU使用率0-100',
  `load1` decimal(10,2) NULL DEFAULT NULL COMMENT '1分钟负载',
  `load5` decimal(10,2) NULL DEFAULT NULL COMMENT '5分钟负载',
  `load15` decimal(10,2) NULL DEFAULT NULL COMMENT '15分钟负载',
  `mem_used` bigint NULL DEFAULT NULL COMMENT '内存已用（字节）',
  `mem_total` bigint NULL DEFAULT NULL COMMENT '内存总量（字节）',
  `mem_percent` decimal(5,2) NULL DEFAULT NULL COMMENT '内存使用率0-100',
  `disk_used` bigint NULL DEFAULT NULL COMMENT '根分区已用（字节）',
  `disk_total` bigint NULL DEFAULT NULL COMMENT '根分区总量（字节）',
  `disk_percent` decimal(5,2) NULL DEFAULT NULL COMMENT '根分区使用率0-100',
  `collected_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '采集时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_host_collected`(`host_id`, `collected_time`) USING BTREE,
  INDEX `idx_env_collected`(`environment_id`, `collected_time`) USING BTREE,
  INDEX `idx_collected`(`collected_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '宿主机时序指标表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for alert_rule（监控 · 告警规则）
-- ----------------------------
DROP TABLE IF EXISTS `alert_rule`;
CREATE TABLE `alert_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '规则名称',
  `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  `target_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'host/service',
  `metric` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '指标 key',
  `comparator` varchar(4) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '> < >= <=',
  `threshold` decimal(12,2) NOT NULL COMMENT '阈值',
  `duration_seconds` int NOT NULL DEFAULT 60 COMMENT '持续多少秒满足条件才触发',
  `severity` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'warning' COMMENT 'info/warning/critical',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '描述',
  `created_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_enabled`(`enabled`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '告警规则表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for alert_event（监控 · 告警事件）
-- ----------------------------
DROP TABLE IF EXISTS `alert_event`;
CREATE TABLE `alert_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `rule_id` bigint NOT NULL COMMENT '关联 alert_rule.id',
  `target_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'host/service',
  `target_key` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '目标 ID',
  `metric` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `value` decimal(12,2) NULL DEFAULT NULL COMMENT '触发时观测值',
  `threshold` decimal(12,2) NULL DEFAULT NULL COMMENT '触发时阈值',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'firing/resolved',
  `fired_time` datetime NOT NULL COMMENT '触发时间',
  `resolved_time` datetime NULL DEFAULT NULL COMMENT '恢复时间',
  `message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '描述',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_rule`(`rule_id`) USING BTREE,
  INDEX `idx_target`(`target_type`, `target_key`) USING BTREE,
  INDEX `idx_fired_time`(`fired_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '告警事件表' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
