package com.easy.cd.deploy;

import com.easy.cd.deploy.factory.DeployStrategyFactory;
import com.easy.cd.deploy.model.DeployRequest;
import com.easy.cd.deploy.model.DeployResult;
import com.easy.cd.dto.ImageVersionDTO;
import com.easy.cd.dto.ServiceMetricsInfo;
import com.easy.cd.dto.ServiceStatusInfo;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 部署服务
 * 统一管理部署操作和监控指标收集
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeployService {
    
    private final DeployStrategyFactory strategyFactory;
    
    /**
     * 部署服务（创建或更新）
     */
    public DeployResult deploy(String deployType, DeployRequest request) {
        log.info("执行部署操作, 类型: {}, 服务: {}", deployType, request.getServiceName());
        DeployStrategy strategy = strategyFactory.getStrategy(deployType);
        return strategy.deploy(request);
    }
    
    /**
     * 停止服务
     */
    public DeployResult stop(String deployType, Long serviceId) {
        log.info("执行停止操作, 类型: {}, 服务ID: {}", deployType, serviceId);
        DeployStrategy strategy = strategyFactory.getStrategy(deployType);
        return strategy.stop(serviceId);
    }
    
    /**
     * 重启服务
     */
    public DeployResult restart(String deployType, Long serviceId) {
        log.info("执行重启操作, 类型: {}, 服务ID: {}", deployType, serviceId);
        DeployStrategy strategy = strategyFactory.getStrategy(deployType);
        return strategy.restart(serviceId);
    }
    
    /**
     * 回滚服务
     */
    public DeployResult rollback(String deployType, Long serviceId, String targetVersion) {
        log.info("执行回滚操作, 类型: {}, 服务ID: {}, 目标版本: {}", deployType, serviceId, targetVersion);
        DeployStrategy strategy = strategyFactory.getStrategy(deployType);
        return strategy.rollback(serviceId, targetVersion);
    }
    
    /**
     * 调整副本数
     */
    public DeployResult scale(String deployType, Long serviceId, Integer replicas) {
        log.info("执行调整副本操作, 类型: {}, 服务ID: {}, 副本数: {}", deployType, serviceId, replicas);
        DeployStrategy strategy = strategyFactory.getStrategy(deployType);
        return strategy.scale(serviceId, replicas);
    }
    
    /**
     * 删除服务
     */
    public DeployResult delete(String deployType, Long serviceId) {
        log.info("删除服务, 类型: {}, 服务ID: {}", deployType, serviceId);
        DeployStrategy strategy = strategyFactory.getStrategy(deployType);
        return strategy.delete(serviceId);
    }
    
    /**
     * 获取服务状态
     */
    public DeployResult getStatus(String deployType, Long serviceId) {
        log.info("获取服务状态, 类型: {}, 服务ID: {}", deployType, serviceId);
        DeployStrategy strategy = strategyFactory.getStrategy(deployType);
        return strategy.getStatus(serviceId);
    }
    
    /**
     * 从镜像名称中提取版本号
     */
    public String extractVersionFromImage(String deployType, String dockerImage) {
        DeployStrategy strategy = strategyFactory.getStrategy(deployType);
        return strategy.extractVersionFromImage(dockerImage);
    }
    
    /**
     * 获取镜像的所有可用版本（从镜像仓库）
     */
    public List<ImageVersionDTO> getAvailableVersions(String deployType, String dockerImage) {
        DeployStrategy strategy = strategyFactory.getStrategy(deployType);
        return strategy.getAvailableVersions(dockerImage);
    }
    
    /**
     * 收集服务状态信息
     */
    public List<ServiceStatusInfo> collectServiceStatus(String deployType, Environment environment, List<AppService> services) {
        DeployStrategy strategy = strategyFactory.getStrategy(deployType);
        return strategy.collectServiceStatus(environment, services);
    }
    
    /**
     * 收集服务监控指标
     */
    public List<ServiceMetricsInfo> collectServiceMetrics(String deployType, Environment environment, List<AppService> services) {
        DeployStrategy strategy = strategyFactory.getStrategy(deployType);
        return strategy.collectServiceMetrics(environment, services);
    }
}
