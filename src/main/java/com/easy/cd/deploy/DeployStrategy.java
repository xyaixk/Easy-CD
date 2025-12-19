package com.easy.cd.deploy;

import com.easy.cd.deploy.model.DeployRequest;
import com.easy.cd.deploy.model.DeployResult;
import com.easy.cd.dto.ImageVersionDTO;
import com.easy.cd.dto.ServiceMetricsInfo;
import com.easy.cd.dto.ServiceStatusInfo;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;

import java.util.List;

/**
 * 部署策略接口
 * 定义所有部署方式的统一行为（部署操作 + 监控指标收集）
 */
public interface DeployStrategy {
    
    /**
     * 部署服务（创建或更新）
     * @param request 部署请求参数
     * @return 部署结果
     */
    DeployResult deploy(DeployRequest request);
    
    /**
     * 停止服务
     * @param serviceId 服务ID
     * @return 操作结果
     */
    DeployResult stop(Long serviceId);
    
    /**
     * 重启服务
     * @param serviceId 服务ID
     * @return 操作结果
     */
    DeployResult restart(Long serviceId);
    
    /**
     * 回滚服务
     * @param serviceId 服务ID
     * @param targetVersion 目标版本
     * @return 操作结果
     */
    DeployResult rollback(Long serviceId, String targetVersion);
    
    /**
     * 调整副本数
     * @param serviceId 服务ID
     * @param replicas 副本数量
     * @return 操作结果
     */
    DeployResult scale(Long serviceId, Integer replicas);
    
    /**
     * 删除服务
     * @param serviceId 服务ID
     * @return 操作结果
     */
    DeployResult delete(Long serviceId);
    
    /**
     * 获取服务状态
     * @param serviceId 服务ID
     * @return 服务状态信息
     */
    DeployResult getStatus(Long serviceId);
    
    /**
     * 获取支持的部署类型
     * @return 部署类型名称
     */
    String getDeployType();
    
    /**
     * 从镜像名称中提取版本号
     * @param dockerImage Docker镜像名称（如 nginx:1.25.3 或 registry.com/nginx:1.25.3@sha256:xxx）
     * @return 版本号（如 1.25.3、latest 等）
     */
    String extractVersionFromImage(String dockerImage);
    
    /**
     * 获取镜像的所有可用版本（从镜像仓库）
     * @param dockerImage Docker镜像名称（如 nginx 或 registry.com/nginx:1.25.3）
     * @return 可用版本列表
     */
    List<ImageVersionDTO> getAvailableVersions(String dockerImage);
    
    /**
     * 收集服务状态信息
     * @param environment 环境信息
     * @param services 服务列表
     * @return 服务状态信息列表
     */
    List<ServiceStatusInfo> collectServiceStatus(Environment environment, List<AppService> services);
    
    /**
     * 收集服务监控指标
     * @param environment 环境信息
     * @param services 服务列表
     * @return 服务监控指标列表
     */
    List<ServiceMetricsInfo> collectServiceMetrics(Environment environment, List<AppService> services);
}
