package com.easy.cd.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.deploy.DeployService;
import com.easy.cd.deploy.model.DeployRequest;
import com.easy.cd.deploy.model.DeployResult;
import com.easy.cd.dto.ImageVersionDTO;
import com.easy.cd.dto.ReplicaDetailDTO;
import com.easy.cd.dto.ServiceCreateDTO;
import com.easy.cd.dto.ServiceUpdateDTO;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.entity.ReplicaMetrics;
import com.easy.cd.entity.ReplicaStatus;
import com.easy.cd.entity.ServiceMetrics;
import com.easy.cd.entity.ServiceStatus;
import com.easy.cd.exception.BusinessException;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ReplicaMetricsMapper;
import com.easy.cd.mapper.ReplicaStatusMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.mapper.ServiceMetricsMapper;
import com.easy.cd.mapper.ServiceStatusMapper;
import com.easy.cd.service.ServiceManagementService;
import com.easy.cd.vo.ServiceDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 服务管理业务实现类
 */
@Slf4j
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class ServiceManagementServiceImpl implements ServiceManagementService {
    
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MIN_REPLICAS = 1;
    private static final int MAX_REPLICAS = 100;
    
    private final ServiceMapper serviceMapper;
    private final ServiceStatusMapper serviceStatusMapper;
    private final ServiceMetricsMapper serviceMetricsMapper;
    private final ReplicaStatusMapper replicaStatusMapper;
    private final ReplicaMetricsMapper replicaMetricsMapper;
    private final EnvironmentMapper environmentMapper;
    private final DeployService deployService;
    
    /**
     * 创建服务
     * 核心流程：
     * 1. 参数校验（必填字段、副本数等）
     * 2. 先保存服务信息到数据库（利用唯一索引校验重复）
     * 3. 调用部署服务执行实际部署
     * 4. 部署成功后保存服务状态
     * 5. 部署失败则事务回滚，删除数据库记录
     * 6. 返回创建的服务信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceDetailVO create(ServiceCreateDTO createDTO) {
        log.info("创建服务: {}, 环境ID: {}", createDTO.getName(), createDTO.getEnvironmentId());
        
        // 步骤1: 参数校验
        Environment environment = validateAndGetEnvironment(createDTO);
        
        // 步骤2: 先保存到数据库（利用唯一索引校验服务名重复）
        AppService service = saveService(createDTO);
        log.info("服务信息已保存，ID: {}", service.getId());
        
        // 步骤3: 部署服务
        DeployResult deployResult = deployToEnvironment(createDTO, environment);
        
        // 步骤4: 保存外部服务ID和名称
        if (deployResult.getExternalServiceId() != null) {
            service.setExternalServiceId(deployResult.getExternalServiceId());
            service.setExternalServiceName(deployResult.getExternalServiceName());
            serviceMapper.updateById(service);
            log.debug("保存外部服务ID: {}, 名称: {}", deployResult.getExternalServiceId(), deployResult.getExternalServiceName());
        }
        
        // 步骤5: 保存服务状态
        ServiceStatus serviceStatus = saveServiceStatus(service.getId(), deployResult);
        
        log.info("服务创建成功: {}, ID: {}", service.getName(), service.getId());
        return buildServiceDetailVO(service, serviceStatus);
    }
    
    /**
     * 更新服务
     * 核心流程：
     * 1. 查询原服务信息
     * 2. 参数校验（服务名不可修改）
     * 3. 构建部署请求对象
     * 4. 调用部署服务执行更新
     * 5. 更新成功后修改数据库
     * 6. 返回更新后的服务信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceDetailVO update(Long id, ServiceUpdateDTO updateDTO) {
        log.info("更新服务: ID={}, 服务名={}", id, updateDTO.getName());
        
        // 步骤1: 查询原服务
        AppService service = serviceMapper.selectById(id);
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        
        // 步骤2: 校验和获取环境
        Environment environment = getEnvironmentOrThrow(service.getEnvironmentId());
        validateUpdateFields(updateDTO);
        
        // 步骤3: 构建部署请求
        DeployRequest deployRequest = buildUpdateDeployRequest(service, updateDTO);
        
        // 步骤4: 调用部署服务更新
        log.info("开始部署更新: {}", service.getName());
        DeployResult deployResult = deployService.deploy(environment.getDeployType(), deployRequest);
        
        if (!deployResult.getSuccess()) {
            throw new BusinessException("部署更新失败: " + deployResult.getMessage());
        }
        
        // 步骤5: 更新数据库
        updateServiceInfo(service, updateDTO);
        updateExternalServiceInfo(service, deployResult);
        updateServiceStatus(service.getId(), deployResult);
        
        // 步骤6: 查询最新状态并返回
        ServiceStatus status = serviceStatusMapper.selectOne(
            new LambdaQueryWrapper<ServiceStatus>()
                .eq(ServiceStatus::getServiceId, id)
        );
        
        log.info("服务更新成功: {}, ID: {}", service.getName(), id);
        return buildServiceDetailVO(service, status);
    }
    
    /**
     * 查询环境下的所有服务
     */
    @Override
    public List<ServiceDetailVO> listByEnvironment(Long environmentId) {
        log.info("查询环境服务列表, environmentId: {}", environmentId);
        
        // 查询环境下的所有服务
        LambdaQueryWrapper<AppService> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AppService::getEnvironmentId, environmentId)
                   .orderByDesc(AppService::getCreatedTime);
        List<AppService> services = serviceMapper.selectList(queryWrapper);
        
        // 转换为VO列表
        List<ServiceDetailVO> result = new ArrayList<>();
        for (AppService service : services) {
            // 查询服务状态
            ServiceStatus status = serviceStatusMapper.selectOne(
                new LambdaQueryWrapper<ServiceStatus>()
                    .eq(ServiceStatus::getServiceId, service.getId())
            );
            result.add(buildServiceDetailVO(service, status));
        }
        
        log.info("查询到 {} 个服务", result.size());
        return result;
    }
    
    /**
     * 根据ID查询服务详情
     */
    @Override
    public ServiceDetailVO getById(Long id) {
        log.info("查询服务详情, id: {}", id);
        
        // 查询服务基本信息
        AppService service = serviceMapper.selectById(id);
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        
        // 查询服务状态
        ServiceStatus status = serviceStatusMapper.selectOne(
            new LambdaQueryWrapper<ServiceStatus>()
                .eq(ServiceStatus::getServiceId, id)
        );
        
        return buildServiceDetailVO(service, status);
    }
    
    /**
     * 删除服务（包括 Docker 服务和数据库记录）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        log.info("删除服务, id: {}", id);
        
        // 步骤1: 查询服务信息
        AppService service = serviceMapper.selectById(id);
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        
        // 步骤2: 获取环境信息
        Environment environment = getEnvironmentOrThrow(service.getEnvironmentId());
        
        // 步骤3: 调用 Docker 删除服务
        try {
            DeployResult deployResult = deployService.delete(environment.getDeployType(), id);
            if (!deployResult.getSuccess()) {
                log.warn("Docker服务删除失败: {}, 继续删除数据库记录", deployResult.getMessage());
            }
        } catch (Exception e) {
            log.error("Docker服务删除异常", e);
            // 不抛出异常，继续删除数据库记录
        }
        
        // 步骤4: 删除数据库记录（级联删除，按从子表到父表的顺序）
        
        // 4.1 删除副本指标（先删子表）
        int deletedReplicaMetrics = replicaMetricsMapper.delete(
            new LambdaQueryWrapper<ReplicaMetrics>()
                .eq(ReplicaMetrics::getServiceId, id)
        );
        log.info("已删除服务[{}]的副本指标记录: {} 条", id, deletedReplicaMetrics);
        
        // 4.2 删除副本状态
        int deletedReplicaStatus = replicaStatusMapper.delete(
            new LambdaQueryWrapper<ReplicaStatus>()
                .eq(ReplicaStatus::getServiceId, id)
        );
        log.info("已删除服务[{}]的副本状态记录: {} 条", id, deletedReplicaStatus);
        
        // 4.3 删除服务指标（历史监控数据）
        int deletedServiceMetrics = serviceMetricsMapper.delete(
            new LambdaQueryWrapper<ServiceMetrics>()
                .eq(ServiceMetrics::getServiceId, id)
        );
        log.info("已删除服务[{}]的服务指标记录: {} 条", id, deletedServiceMetrics);
        
        // 4.4 删除服务状态
        int deletedServiceStatus = serviceStatusMapper.delete(
            new LambdaQueryWrapper<ServiceStatus>()
                .eq(ServiceStatus::getServiceId, id)
        );
        log.info("已删除服务[{}]的服务状态记录: {} 条", id, deletedServiceStatus);
        
        // 4.5 删除服务基本信息（最后删父表）
        int deletedService = serviceMapper.deleteById(id);
        log.info("已删除服务[{}]的基本信息: {} 条", id, deletedService);
        
        log.info("服务删除成功: {}, ID: {}, 共删除 {} 条记录", 
            service.getName(), id, 
            deletedReplicaMetrics + deletedReplicaStatus + deletedServiceMetrics + deletedServiceStatus + deletedService);
    }
    
    /**
     * 重启服务
     */
    @Override
    public void restart(Long id) {
        log.info("重启服务, id: {}", id);
        
        AppService service = serviceMapper.selectById(id);
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        
        Environment environment = getEnvironmentOrThrow(service.getEnvironmentId());
        
        DeployResult result = deployService.restart(environment.getDeployType(), id);
        if (!result.getSuccess()) {
            throw new BusinessException("重启失败: " + result.getMessage());
        }
        
        log.info("服务重启成功: {}", service.getName());
    }
    
    /**
     * 停止服务
     */
    @Override
    public void stop(Long id) {
        log.info("停止服务, id: {}", id);
        
        AppService service = serviceMapper.selectById(id);
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        
        Environment environment = getEnvironmentOrThrow(service.getEnvironmentId());
        
        DeployResult result = deployService.stop(environment.getDeployType(), id);
        if (!result.getSuccess()) {
            throw new BusinessException("停止失败: " + result.getMessage());
        }
        
        log.info("服务停止成功: {}", service.getName());
    }
    
    /**
     * 回滚服务
     */
    @Override
    public void rollback(Long id, String targetVersion) {
        log.info("回滚服务, id: {}, targetVersion: {}", id, targetVersion);
        
        AppService service = serviceMapper.selectById(id);
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        
        Environment environment = getEnvironmentOrThrow(service.getEnvironmentId());
        
        DeployResult result = deployService.rollback(environment.getDeployType(), id, targetVersion);
        if (!result.getSuccess()) {
            throw new BusinessException("回滚失败: " + result.getMessage());
        }
        
        log.info("服务回滚成功: {}", service.getName());
    }
    
    /**
     * 调整副本数
     */
    @Override
    public void scale(Long id, Integer replicas) {
        log.info("调整副本数, id: {}, replicas: {}", id, replicas);
        
        // 校验副本数范围
        if (replicas < MIN_REPLICAS || replicas > MAX_REPLICAS) {
            throw new BusinessException(
                String.format("副本数必须在%d-%d之间", MIN_REPLICAS, MAX_REPLICAS)
            );
        }
        
        AppService service = serviceMapper.selectById(id);
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        
        Environment environment = getEnvironmentOrThrow(service.getEnvironmentId());
        
        DeployResult result = deployService.scale(environment.getDeployType(), id, replicas);
        if (!result.getSuccess()) {
            throw new BusinessException("调整副本数失败: " + result.getMessage());
        }
        
        log.info("服务副本数调整成功: {}, 新副本数: {}", service.getName(), replicas);
    }
    
    /**
     * 查看服务副本列表
     */
    @Override
    public List<?> getReplicas(Long id) {
        log.info("查看服务副本, id: {}", id);
        
        AppService service = serviceMapper.selectById(id);
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        
        // 从 replica_status 表查询副本信息（只查询活跃的副本）
        List<ReplicaStatus> replicaStatusList = replicaStatusMapper.selectList(
            new LambdaQueryWrapper<ReplicaStatus>()
                .eq(ReplicaStatus::getServiceId, id)
                .notIn(ReplicaStatus::getStatus, "shutdown", "complete", "remove")
                .orderByAsc(ReplicaStatus::getTaskSlot)
        );
        
        // 转换为DTO列表
        List<ReplicaDetailDTO> result = new ArrayList<>();
        for (ReplicaStatus replicaStatus : replicaStatusList) {
            result.add(convertToReplicaDetailDTO(replicaStatus));
        }
        
        log.info("查询到 {} 个副本", result.size());
        return result;
    }
    
    /**
     * 转换副本状态为DTO
     */
    private ReplicaDetailDTO convertToReplicaDetailDTO(ReplicaStatus status) {
        ReplicaDetailDTO dto = ReplicaDetailDTO.builder()
            .id(status.getReplicaId())
            .name(status.getReplicaName())
            .index(status.getReplicaIndex())
            .status(status.getStatus())
            .node(status.getNodeName())
            .nodeIp(status.getNodeIp())
            .containerId(status.getContainerIdShort() != null ? status.getContainerIdShort() : status.getContainerId())
            .restartCount(status.getRestartCount())
            .errorMessage(status.getErrorMessage())
            .build();
        
        // 运行时间
        if (status.getUptimeSeconds() != null) {
            dto.setUptime(formatUptime(status.getUptimeSeconds()));
        } else if (status.getStartTime() != null) {
            long seconds = java.time.Duration.between(status.getStartTime(), LocalDateTime.now()).getSeconds();
            dto.setUptime(formatUptime(seconds));
        }
        
        return dto;
    }
    
    /**
     * 格式化运行时间
     */
    private String formatUptime(long seconds) {
        if (seconds < 60) {
            return "刚刚";
        } else if (seconds < 3600) {
            return (seconds / 60) + "分钟";
        } else if (seconds < 86400) {
            return (seconds / 3600) + "小时";
        } else {
            return (seconds / 86400) + "天";
        }
    }
    
    /**
     * 验证请求并获取环境信息
     */
    private Environment validateAndGetEnvironment(ServiceCreateDTO createDTO) {
        validateBasicFields(createDTO);
        validateReplicasField(createDTO.getReplicas());
        
        Environment environment = getEnvironmentOrThrow(createDTO.getEnvironmentId());
        
        // 不需要再手动校验唯一性，交给数据库唯一索引处理
        // checkServiceNameUnique(createDTO.getEnvironmentId(), createDTO.getName());
        
        return environment;
    }
    
    /**
     * 校验基本字段
     */
    private void validateBasicFields(ServiceCreateDTO createDTO) {
        if (createDTO.getEnvironmentId() == null) {
            throw new BusinessException("环境ID不能为空");
        }
        if (createDTO.getName() == null || createDTO.getName().trim().isEmpty()) {
            throw new BusinessException("服务名称不能为空");
        }
        if (createDTO.getDockerImage() == null || createDTO.getDockerImage().trim().isEmpty()) {
            throw new BusinessException("Docker镜像不能为空");
        }
        if (createDTO.getDescription() != null && createDTO.getDescription().length() > 20) {
            throw new BusinessException("服务描述最多20个字符");
        }
    }
    
    /**
     * 获取环境信息，不存在则抛异常
     */
    private Environment getEnvironmentOrThrow(Long environmentId) {
        Environment environment = environmentMapper.selectById(environmentId);
        if (environment == null) {
            throw new BusinessException("环境不存在");
        }
        return environment;
    }
    
    /**
     * 检查服务名在环境下是否唯一
     */
    private void checkServiceNameUnique(Long environmentId, String serviceName) {
        LambdaQueryWrapper<AppService> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AppService::getEnvironmentId, environmentId)
                   .eq(AppService::getName, serviceName);
        Long count = serviceMapper.selectCount(queryWrapper);
        if (count > 0) {
            throw new BusinessException("该环境下已存在同名服务: " + serviceName);
        }
    }
    
    /**
     * 验证副本数范围
     */
    private void validateReplicasField(Integer replicas) {
        if (replicas == null) {
            throw new BusinessException("副本数不能为空");
        }
        if (replicas < MIN_REPLICAS || replicas > MAX_REPLICAS) {
            throw new BusinessException(
                String.format("副本数必须在%d-%d之间", MIN_REPLICAS, MAX_REPLICAS)
            );
        }
    }
    
    /**
     * 部署服务到指定环境
     */
    private DeployResult deployToEnvironment(ServiceCreateDTO createDTO, Environment environment) {
        DeployRequest deployRequest = buildDeployRequest(createDTO, null);
        
        log.info("部署服务到 {} 环境", environment.getDeployType());
        DeployResult deployResult = deployService.deploy(environment.getDeployType(), deployRequest);
        
        if (!deployResult.getSuccess()) {
            throw new BusinessException("部署失败: " + deployResult.getMessage());
        }
        
        return deployResult;
    }
    
    /**
     * 构建部署请求
     */
    private DeployRequest buildDeployRequest(ServiceCreateDTO createDTO, Long serviceId) {
        Integer replicas = createDTO.getReplicas() != null ? createDTO.getReplicas() : 1;
        
        return DeployRequest.builder()
            .serviceId(serviceId)
            .serviceName(createDTO.getName())
            .version(null)  // 不指定版本号，由刷新任务从实际镜像提取
            .dockerImage(createDTO.getDockerImage())
            .replicas(replicas)
            .dockerParams(createDTO.getDockerParams())
            .environmentId(createDTO.getEnvironmentId())
            .build();
    }
    
    /**
     * 解析Docker参数JSON
     */
    private Map<String, Object> parseDockerParams(String dockerParamsJson) {
        try {
            return JSON.parseObject(dockerParamsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException("解析Docker参数失败: " + e.getMessage());
        }
    }
    
    /**
     * 从Docker参数中提取副本数
     */
    private Integer extractReplicas(Map<String, Object> dockerParams) {
        Object replicasObj = dockerParams.get("replicas");
        return replicasObj != null ? Integer.parseInt(replicasObj.toString()) : 1;
    }
    
    /**
     * 保存服务基础信息
     */
    private AppService saveService(ServiceCreateDTO createDTO) {
        AppService service = buildServiceEntity(createDTO);
        serviceMapper.insert(service);
        log.debug("保存服务: ID={}, Name={}", service.getId(), service.getName());
        return service;
    }
    
    /**
     * 构建服务实体
     */
    private AppService buildServiceEntity(ServiceCreateDTO createDTO) {
        AppService service = new AppService();
        service.setName(createDTO.getName());
        service.setDescription(createDTO.getDescription());
        service.setEnvironmentId(createDTO.getEnvironmentId());
        service.setDockerImage(createDTO.getDockerImage());
        service.setDockerParams(createDTO.getDockerParams());
        
        // 从镜像中提取版本号并保存到 app_service.version
        Environment environment = environmentMapper.selectById(createDTO.getEnvironmentId());
        if (environment != null) {
            String version = deployService.extractVersionFromImage(environment.getDeployType(), createDTO.getDockerImage());
            service.setVersion(version);
            log.info("从镜像[{}]提取版本号: {}", createDTO.getDockerImage(), version);
        }
        
        service.setCreatedTime(LocalDateTime.now());
        service.setUpdatedTime(LocalDateTime.now());
        return service;
    }
    
    /**
     * 保存服务状态
     */
    private ServiceStatus saveServiceStatus(Long serviceId, DeployResult deployResult) {
        ServiceStatus status = buildServiceStatus(serviceId, deployResult);
        serviceStatusMapper.insert(status);
        log.debug("保存服务状态: ServiceId={}, Status={}", serviceId, status.getStatus());
        return status;
    }
    
    /**
     * 构建服务状态实体
     */
    private ServiceStatus buildServiceStatus(Long serviceId, DeployResult deployResult) {
        ServiceStatus status = new ServiceStatus();
        status.setServiceId(serviceId);
        status.setStatus(deployResult.getStatus() != null ? deployResult.getStatus() : "running");
        status.setHealthyInstances(deployResult.getHealthyInstances() != null ? deployResult.getHealthyInstances() : 0);
        status.setInstances(deployResult.getInstances() != null ? deployResult.getInstances() : 0);
        status.setDesiredInstances(deployResult.getDesiredInstances() != null ? deployResult.getDesiredInstances() : 0);
        status.setLastDeployTime(LocalDateTime.now());
        status.setLastDeployBy("system");
        status.setCreatedTime(LocalDateTime.now());
        status.setUpdatedTime(LocalDateTime.now());
        return status;
    }
    
    /**
     * 校验更新字段
     */
    private void validateUpdateFields(ServiceUpdateDTO updateDTO) {
        if (updateDTO.getDockerImage() != null && updateDTO.getDockerImage().trim().isEmpty()) {
            throw new BusinessException("Docker镜像不能为空");
        }
        if (updateDTO.getDescription() != null && updateDTO.getDescription().length() > 20) {
            throw new BusinessException("服务描述最多20个字符");
        }
    }
    
    /**
     * 构建更新部署请求
     */
    private DeployRequest buildUpdateDeployRequest(AppService service, ServiceUpdateDTO updateDTO) {
        // 使用更新的字段，如果为null则使用原有值
        String dockerImage = updateDTO.getDockerImage() != null ? updateDTO.getDockerImage() : service.getDockerImage();
        String dockerParams = updateDTO.getDockerParams() != null ? updateDTO.getDockerParams() : service.getDockerParams();
        
        Map<String, Object> params = parseDockerParams(dockerParams);
        Integer replicas = extractReplicas(params);
        
        return DeployRequest.builder()
            .serviceId(service.getId())
            .serviceName(service.getName())
            .version(null)  // 不指定版本号，由刷新任务从实际镜像提取
            .dockerImage(dockerImage)
            .replicas(replicas)
            .dockerParams(dockerParams)
            .environmentId(service.getEnvironmentId())
            .build();
    }
    
    /**
     * 更新服务信息
     */
    private void updateServiceInfo(AppService service, ServiceUpdateDTO updateDTO) {
        if (updateDTO.getDescription() != null) {
            service.setDescription(updateDTO.getDescription());
        }
        if (updateDTO.getDockerImage() != null) {
            service.setDockerImage(updateDTO.getDockerImage());
            
            // 如果更新了镜像，重新提取版本号并保存到 app_service.version
            Environment environment = environmentMapper.selectById(service.getEnvironmentId());
            if (environment != null) {
                String version = deployService.extractVersionFromImage(environment.getDeployType(), updateDTO.getDockerImage());
                service.setVersion(version);
                log.info("从更新镜像[{}]提取版本号: {}", updateDTO.getDockerImage(), version);
            }
        }
        if (updateDTO.getDockerParams() != null) {
            service.setDockerParams(updateDTO.getDockerParams());
        }
        service.setUpdatedTime(LocalDateTime.now());
        serviceMapper.updateById(service);
        log.debug("更新服务信息: ID={}, Name={}", service.getId(), service.getName());
    }
    
    /**
     * 更新外部服务ID（从部署结果中更新）
     */
    private void updateExternalServiceInfo(AppService service, DeployResult deployResult) {
        if (deployResult.getExternalServiceId() != null) {
            service.setExternalServiceId(deployResult.getExternalServiceId());
            service.setExternalServiceName(deployResult.getExternalServiceName());
            serviceMapper.updateById(service);
            log.info("更新外部服务信息: ID={}, Name={}", 
                deployResult.getExternalServiceId(), 
                deployResult.getExternalServiceName());
        } else {
            log.warn("部署结果中没有外部服务信息");
        }
    }
    
    /**
     * 更新服务状态
     */
    private void updateServiceStatus(Long serviceId, DeployResult deployResult) {
        ServiceStatus status = serviceStatusMapper.selectOne(
            new LambdaQueryWrapper<ServiceStatus>()
                .eq(ServiceStatus::getServiceId, serviceId)
        );
        
        if (status == null) {
            // 如果不存在状态记录，创建新的
            saveServiceStatus(serviceId, deployResult);
        } else {
            // 更新现有状态（不更新版本号）
            status.setStatus(deployResult.getStatus() != null ? deployResult.getStatus() : "running");
            status.setHealthyInstances(deployResult.getHealthyInstances() != null ? deployResult.getHealthyInstances() : 0);
            status.setInstances(deployResult.getInstances() != null ? deployResult.getInstances() : 0);
            status.setDesiredInstances(deployResult.getDesiredInstances() != null ? deployResult.getDesiredInstances() : 0);
            status.setLastDeployTime(LocalDateTime.now());
            status.setLastDeployBy("system");
            status.setUpdatedTime(LocalDateTime.now());
            serviceStatusMapper.updateById(status);
            log.debug("更新服务状态: ServiceId={}, Status={}", serviceId, status.getStatus());
        }
    }
    
    /**
     * 构建服务详情VO
     */
    private ServiceDetailVO buildServiceDetailVO(AppService service, ServiceStatus status) {
        ServiceDetailVO vo = new ServiceDetailVO();
        
        // 基础信息
        vo.setId(service.getId());
        vo.setEnvironmentId(service.getEnvironmentId());
        vo.setName(service.getName());
        vo.setDescription(service.getDescription());
        vo.setDockerImage(service.getDockerImage());
        vo.setDockerParams(service.getDockerParams());
        vo.setCreatedTime(service.getCreatedTime().format(DATE_TIME_FORMATTER));
        
        // 版本号：从 app_service.version 读取
        vo.setVersion(service.getVersion() != null ? service.getVersion() : "unknown");
        
        // 状态信息
        if (status != null) {
            vo.setStatus(status.getStatus());
            vo.setInstances(status.getInstances());
            vo.setHealthyInstances(status.getHealthyInstances());
            vo.setDesiredInstances(status.getDesiredInstances());
            if (status.getLastDeployTime() != null) {
                vo.setDeployTime(status.getLastDeployTime().format(DATE_TIME_FORMATTER));
            }
        }
        
        // 监控指标（获取最新的一条记录）
        ServiceMetrics metrics = serviceMetricsMapper.selectOne(
            new LambdaQueryWrapper<ServiceMetrics>()
                .eq(ServiceMetrics::getServiceId, service.getId())
                .orderByDesc(ServiceMetrics::getCollectedTime)
                .last("LIMIT 1")
        );
        
        if (metrics != null) {
            vo.setCpuPercent(metrics.getCpuPercent());
            vo.setMemoryUsage(metrics.getMemoryUsage());
            vo.setMemoryLimit(metrics.getMemoryLimit());
            vo.setMemoryPercent(metrics.getMemoryPercent());
            vo.setNetworkRxRate(metrics.getNetworkRxRate());
            vo.setNetworkTxRate(metrics.getNetworkTxRate());
            vo.setDiskReadRate(metrics.getDiskReadRate());
            vo.setDiskWriteRate(metrics.getDiskWriteRate());
        }
        
        return vo;
    }
    
    /**
     * Get available versions for a service image from registry
     */
    @Override
    public List<ImageVersionDTO> getAvailableVersions(Long serviceId) {
        log.info("Getting available versions for service: {}", serviceId);
        
        // Query service info
        AppService service = serviceMapper.selectById(serviceId);
        if (service == null) {
            throw new BusinessException("Service not found");
        }
        
        // Get environment
        Environment environment = environmentMapper.selectById(service.getEnvironmentId());
        if (environment == null) {
            throw new BusinessException("Environment not found");
        }
        
        // Call deploy service to get available versions
        List<ImageVersionDTO> versions = deployService.getAvailableVersions(
            environment.getDeployType(), 
            service.getDockerImage()
        );
        
        // Mark current version
        String currentVersion = service.getVersion();
        if (currentVersion != null) {
            for (ImageVersionDTO version : versions) {
                if (currentVersion.equals(version.getVersion())) {
                    version.setIsCurrent(true);
                    break;
                }
            }
        }
        
        log.info("Found {} available versions for service {}", versions.size(), service.getName());
        return versions;
    }
}
