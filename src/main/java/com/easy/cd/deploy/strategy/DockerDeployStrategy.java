package com.easy.cd.deploy.strategy;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.easy.cd.deploy.DeployStrategy;
import com.easy.cd.deploy.model.DeployRequest;
import com.easy.cd.deploy.model.DeployResult;
import com.easy.cd.dto.ImageVersionDTO;
import com.easy.cd.dto.ServiceMetricsInfo;
import com.easy.cd.dto.ServiceStatusInfo;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateServiceCmd;
import com.github.dockerjava.api.command.CreateServiceResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Docker Swarm 部署策略实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerDeployStrategy implements DeployStrategy {
    
    private final EnvironmentMapper environmentMapper;
    private final ServiceMapper serviceMapper;
    private final Random random = new Random();
    private final RestTemplate restTemplate = new RestTemplate();
    
    @Override
    public DeployResult deploy(DeployRequest request) {
        log.info("开始Docker部署: {}", request.getServiceName());
        
        try {
            // 获取环境配置
            Environment environment = environmentMapper.selectById(request.getEnvironmentId());
            if (environment == null || environment.getConfig() == null) {
                return DeployResult.failure("环境配置不存在");
            }
            
            Map<String, Object> config = parseConfig(environment.getConfig());
            List<String> managerHosts = (List<String>) config.get("swarmManagerHosts");
            String registryUrl = (String) config.get("registryUrl");
            
            if (managerHosts == null || managerHosts.isEmpty()) {
                return DeployResult.failure("Swarm Manager地址未配置");
            }
            
            // 连接到Swarm Manager（随机选择，失败自动切换）
            DockerClient dockerClient = createDockerClientWithFailover(managerHosts);
            
            // 处理镜像名称（如果没有仓库前缀，添加默认仓库）
            String fullImageName = buildFullImageName(request.getDockerImage(), registryUrl);
            
            // 解析Docker参数
            Map<String, Object> dockerParams = request.getDockerParams() != null 
                ? parseConfig(request.getDockerParams()) 
                : Collections.emptyMap();
            
            String serviceName = buildServiceName(request.getServiceName(), environment.getName());
            
            // 自动创建网络（如果不存在）
            String networkName = buildNetworkName(environment.getName(), "overlay");
            ensureNetworkExists(dockerClient, networkName);
            
            // 检查服务是否已存在（更新 or 创建）
            boolean serviceExists = checkServiceExists(dockerClient, serviceName);
            
            if (serviceExists) {
                // 更新服务
                updateService(dockerClient, serviceName, fullImageName, request, dockerParams);
                log.info("服务更新成功: {}", serviceName);
            } else {
                // 创建服务
                createService(dockerClient, serviceName, fullImageName, request, config, dockerParams);
                log.info("服务创建成功: {}", serviceName);
            }
            
            // 获取服务状态
            Service service = dockerClient.inspectServiceCmd(serviceName).exec();
            String dockerServiceId = service.getId();
            Long replicasLong = service.getSpec().getMode().getReplicated().getReplicas();
            Integer replicas = replicasLong != null ? replicasLong.intValue() : 0;
            
            // 从实际部署的服务中获取镜像信息
            String actualImage = service.getSpec().getTaskTemplate().getContainerSpec().getImage();
            log.info("部署成功，实际镜像: {}", actualImage);
            
            return DeployResult.builder()
                .success(true)
                .message("部署成功")
                .status("running")
                .externalServiceId(dockerServiceId)  // 保存 Docker Service ID
                .externalServiceName(serviceName)     // 保存服务名称
                .desiredInstances(replicas)
                .instances(0)  // 需要通过task查询
                .healthyInstances(0)
                .build();
                
        } catch (Exception e) {
            log.error("Docker部署失败", e);
            return DeployResult.failure("部署失败: " + e.getMessage());
        }
    }
    
    @Override
    public DeployResult stop(Long serviceId) {
        log.info("停止Docker服务: {}", serviceId);
        try {
            // 查询服务信息获取 externalServiceId 和 externalServiceName
            AppService appService = getAppService(serviceId);
            if (appService == null) {
                return DeployResult.failure("服务不存在");
            }
            
            // 获取环境配置
            Environment environment = environmentMapper.selectById(appService.getEnvironmentId());
            if (environment == null) {
                return DeployResult.failure("环境不存在");
            }
            
            Map<String, Object> config = parseConfig(environment.getConfig());
            List<String> managerHosts = (List<String>) config.get("swarmManagerHosts");
            
            if (managerHosts == null || managerHosts.isEmpty()) {
                return DeployResult.failure("Swarm Manager地址未配置");
            }
            
            DockerClient dockerClient = createDockerClientWithFailover(managerHosts);
            
            // 优先使用 externalServiceName，如果不存在则使用命名规则构建
            String serviceName = appService.getExternalServiceName() != null 
                ? appService.getExternalServiceName()
                : buildServiceName(appService.getName(), environment.getName());
            
            // 停止服务：直接移除Docker服务（不保留服务定义，但保留数据库记录）
            // 这样可以通过重启或更新操作重新创建服务
            dockerClient.removeServiceCmd(serviceName).exec();
            
            log.info("Docker服务已停止（已移除）: {}", serviceName);
            return DeployResult.success("服务已停止");
        } catch (Exception e) {
            log.error("停止Docker服务失败", e);
            return DeployResult.failure("停止失败: " + e.getMessage());
        }
    }
    
    @Override
    public DeployResult restart(Long serviceId) {
        log.info("重启Docker服务: {}", serviceId);
        try {
            AppService appService = getAppService(serviceId);
            if (appService == null) {
                return DeployResult.failure("服务不存在");
            }
            
            Environment environment = environmentMapper.selectById(appService.getEnvironmentId());
            if (environment == null) {
                return DeployResult.failure("环境不存在");
            }
            
            Map<String, Object> config = parseConfig(environment.getConfig());
            List<String> managerHosts = (List<String>) config.get("swarmManagerHosts");
            
            if (managerHosts == null || managerHosts.isEmpty()) {
                return DeployResult.failure("Swarm Manager地址未配置");
            }
            
            DockerClient dockerClient = createDockerClientWithFailover(managerHosts);
            
            String serviceName = appService.getExternalServiceName() != null 
                ? appService.getExternalServiceName()
                : buildServiceName(appService.getName(), environment.getName());
            
            // 检查服务是否存在
            boolean serviceExists = checkServiceExists(dockerClient, serviceName);
            
            if (!serviceExists) {
                // 服务不存在（可能已被停止），重新创建服务
                log.info("服务不存在，重新创建服务: {}", serviceName);
                
                // 解析 dockerParams 获取副本数
                Integer replicas = 1; // 默认值
                try {
                    if (appService.getDockerParams() != null) {
                        Map<String, Object> dockerParams = parseConfig(appService.getDockerParams());
                        Object replicasObj = dockerParams.get("replicas");
                        if (replicasObj != null) {
                            replicas = Integer.parseInt(replicasObj.toString());
                            log.info("从 dockerParams 解析副本数: {}", replicas);
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析副本数失败，使用默认值 1: {}", e.getMessage());
                }
                
                // 构建部署请求
                DeployRequest deployRequest = new DeployRequest();
                deployRequest.setServiceId(serviceId);
                deployRequest.setServiceName(appService.getName());
                deployRequest.setDockerImage(appService.getDockerImage());
                deployRequest.setDockerParams(appService.getDockerParams());
                deployRequest.setReplicas(replicas); // 设置副本数
                deployRequest.setEnvironmentId(appService.getEnvironmentId());
                
                // 调用部署逻辑重新创建服务
                return deploy(deployRequest);
            } else {
                // 服务存在，执行滚动重启
                Service service = dockerClient.inspectServiceCmd(serviceName).exec();
                ServiceSpec spec = service.getSpec();
                Long version = service.getVersion().getIndex();
                
                // 强制重启服务（不更新镜像，只重启现有容器）
                // 通过递增 ForceUpdate 计数器来触发滚动重启
                Integer currentForceUpdate = spec.getTaskTemplate().getForceUpdate();
                Integer newForceUpdate = (currentForceUpdate != null ? currentForceUpdate : 0) + 1;
                spec.getTaskTemplate().withForceUpdate(newForceUpdate);
                
                dockerClient.updateServiceCmd(serviceName, spec)
                    .withVersion(version)
                    .exec();
                
                log.info("Docker服务已重启（ForceUpdate={}）: {}", newForceUpdate, serviceName);
                return DeployResult.success("服务已重启");
            }
        } catch (Exception e) {
            log.error("重启Docker服务失败", e);
            return DeployResult.failure("重启失败: " + e.getMessage());
        }
    }
    
    @Override
    public DeployResult rollback(Long serviceId, String targetVersion) {
        log.info("回滚Docker服务: {} 到版本: {}", serviceId, targetVersion);
        try {
            AppService appService = getAppService(serviceId);
            if (appService == null) {
                return DeployResult.failure("服务不存在");
            }
            
            Environment environment = environmentMapper.selectById(appService.getEnvironmentId());
            if (environment == null) {
                return DeployResult.failure("环境不存在");
            }
            
            Map<String, Object> config = parseConfig(environment.getConfig());
            List<String> managerHosts = (List<String>) config.get("swarmManagerHosts");
            
            if (managerHosts == null || managerHosts.isEmpty()) {
                return DeployResult.failure("Swarm Manager地址未配置");
            }
            
            DockerClient dockerClient = createDockerClientWithFailover(managerHosts);
            
            String serviceName = appService.getExternalServiceName() != null 
                ? appService.getExternalServiceName()
                : buildServiceName(appService.getName(), environment.getName());
            
            // 获取当前服务信息
            Service service = dockerClient.inspectServiceCmd(serviceName).exec();
            ServiceSpec spec = service.getSpec();
            Long version = service.getVersion().getIndex();
            
            // 解析镜像名称，替换版本标签
            String currentImage = appService.getDockerImage();
            String newImage = replaceImageTag(currentImage, targetVersion);
            
            log.info("回滚镜像: {} -> {}", currentImage, newImage);
            
            // 更新镜像
            spec.getTaskTemplate().getContainerSpec().withImage(newImage);
            
            dockerClient.updateServiceCmd(serviceName, spec)
                .withVersion(version)
                .exec();
            
            // 更新数据库中的版本信息
            appService.setVersion(targetVersion);
            appService.setDockerImage(newImage);
            serviceMapper.updateById(appService);
            
            log.info("Docker服务已回滚: {} 到版本: {}", serviceName, targetVersion);
            return DeployResult.success("服务已回滚到版本: " + targetVersion);
        } catch (Exception e) {
            log.error("回滚Docker服务失败", e);
            return DeployResult.failure("回滚失败: " + e.getMessage());
        }
    }
    
    /**
     * 替换镜像标签
     * 例如: docker.1ms.run/nginx:latest -> docker.1ms.run/nginx:1.21.0
     */
    private String replaceImageTag(String dockerImage, String newTag) {
        // 移除现有标签
        String imageWithoutTag = dockerImage;
        if (imageWithoutTag.contains("@sha256:")) {
            imageWithoutTag = imageWithoutTag.substring(0, imageWithoutTag.indexOf("@"));
        }
        if (imageWithoutTag.contains(":")) {
            int lastColon = imageWithoutTag.lastIndexOf(":");
            String afterColon = imageWithoutTag.substring(lastColon + 1);
            // 如果冒号后不是端口号，则是标签，需要移除
            if (!afterColon.matches("\\d+") && !afterColon.contains("/")) {
                imageWithoutTag = imageWithoutTag.substring(0, lastColon);
            }
        }
        
        // 添加新标签
        return imageWithoutTag + ":" + newTag;
    }
    
    @Override
    public DeployResult scale(Long serviceId, Integer replicas) {
        log.info("调整Docker服务副本数: {} -> {}", serviceId, replicas);
        try {
            AppService appService = getAppService(serviceId);
            if (appService == null) {
                return DeployResult.failure("服务不存在");
            }
            
            Environment environment = environmentMapper.selectById(appService.getEnvironmentId());
            if (environment == null) {
                return DeployResult.failure("环境不存在");
            }
            
            Map<String, Object> config = parseConfig(environment.getConfig());
            List<String> managerHosts = (List<String>) config.get("swarmManagerHosts");
            
            if (managerHosts == null || managerHosts.isEmpty()) {
                return DeployResult.failure("Swarm Manager地址未配置");
            }
            
            DockerClient dockerClient = createDockerClientWithFailover(managerHosts);
            
            String serviceName = appService.getExternalServiceName() != null 
                ? appService.getExternalServiceName()
                : buildServiceName(appService.getName(), environment.getName());
            
            // 获取当前服务信息
            Service service = dockerClient.inspectServiceCmd(serviceName).exec();
            ServiceSpec spec = service.getSpec();
            Long version = service.getVersion().getIndex();
            
            // 更新副本数
            spec.getMode().getReplicated().withReplicas(replicas);
            
            dockerClient.updateServiceCmd(serviceName, spec)
                .withVersion(version)
                .exec();
            
            log.info("Docker服务副本数已调整: {} -> {}", serviceName, replicas);
            return DeployResult.success("副本数已调整");
        } catch (Exception e) {
            log.error("调整Docker服务副本数失败", e);
            return DeployResult.failure("调整失败: " + e.getMessage());
        }
    }
    
    @Override
    public DeployResult delete(Long serviceId) {
        log.info("删除Docker服务: {}", serviceId);
        try {
            AppService appService = getAppService(serviceId);
            if (appService == null) {
                return DeployResult.failure("服务不存在");
            }
            
            Environment environment = environmentMapper.selectById(appService.getEnvironmentId());
            if (environment == null) {
                return DeployResult.failure("环境不存在");
            }
            
            Map<String, Object> config = parseConfig(environment.getConfig());
            List<String> managerHosts = (List<String>) config.get("swarmManagerHosts");
            
            if (managerHosts == null || managerHosts.isEmpty()) {
                return DeployResult.failure("Swarm Manager地址未配置");
            }
            
            DockerClient dockerClient = createDockerClientWithFailover(managerHosts);
            
            String serviceName = appService.getExternalServiceName() != null 
                ? appService.getExternalServiceName()
                : buildServiceName(appService.getName(), environment.getName());
            
            // 删除服务
            dockerClient.removeServiceCmd(serviceName).exec();
            
            log.info("Docker服务已删除: {}", serviceName);
            return DeployResult.success("服务已删除");
        } catch (Exception e) {
            log.error("删除Docker服务失败", e);
            return DeployResult.failure("删除失败: " + e.getMessage());
        }
    }
    
    @Override
    public DeployResult getStatus(Long serviceId) {
        log.info("获取Docker服务状态: {}", serviceId);
        // TODO: 实现获取状态逻辑
        return DeployResult.builder()
                .success(true)
                .status("running")
                .instances(1)
                .healthyInstances(1)
                .desiredInstances(1)
                .build();
    }
    
    @Override
    public String getDeployType() {
        return "docker";
    }
    
    /**
     * 创建Docker客户端（带故障转移）
     * 随机选择一个Manager节点，连接失败则尝试其他节点
     */
    private DockerClient createDockerClientWithFailover(List<String> managerHosts) {
        // 创建随机顺序的主机列表
        List<String> shuffledHosts = new ArrayList<>(managerHosts);
        Collections.shuffle(shuffledHosts, random);
        
        Exception lastException = null;
        
        // 依次尝试每个Manager节点
        for (String host : shuffledHosts) {
            try {
                DockerClient client = createDockerClient(host);
                // 测试连接是否可用
                client.pingCmd().exec();
                return client;
            } catch (Exception e) {
                log.warn("连接到 {} 失败: {}", host, e.getMessage());
                lastException = e;
                // 继续尝试下一个节点
            }
        }
        
        // 所有节点都失败
        throw new RuntimeException(
            "无法连接到任何Swarm Manager节点，已尝试: " + managerHosts + 
            "，最后错误: " + (lastException != null ? lastException.getMessage() : "未知"),
            lastException
        );
    }
    
    /**
     * 创建Docker客户端
     */
    private DockerClient createDockerClient(String dockerHost) {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(dockerHost)
            .build();
        
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .build();
        
        return DockerClientImpl.getInstance(config, httpClient);
    }
    
    /**
     * 构建完整镜像名称
     * 规则：
     * 1. 如果没有指定标签（冒号），自动添加 :latest
     * 2. 如果没有仓库地址（斜杠），添加默认仓库
     */
    private String buildFullImageName(String dockerImage, String registryUrl) {
        String imageName = dockerImage;
        
        // 步骤1: 如果没有标签，添加 :latest
        // 检查是否包含标签（冒号不是端口号）
        if (!imageName.contains(":")) {
            // 完全没有冒号，添加 :latest
            imageName = imageName + ":latest";
            log.info("镜像名未指定标签，自动添加: {} -> {}", dockerImage, imageName);
        } else {
            // 有冒号，检查是否是端口号（如 localhost:5000/nginx）
            int colonIndex = imageName.lastIndexOf(":");
            String afterColon = imageName.substring(colonIndex + 1);
            
            // 如果冒号后面是纯数字或包含斜杠，说明是端口号，需要添加 :latest
            if (afterColon.matches("\\d+") || afterColon.contains("/")) {
                imageName = imageName + ":latest";
                log.info("镜像名未指定标签（检测到端口号），自动添加: {} -> {}", dockerImage, imageName);
            }
        }
        
        // 步骤2: 如果没有仓库地址，添加默认仓库
        // 检查第一个斜杠之前是否有点或冒号（说明有域名/IP）
        int firstSlash = imageName.indexOf("/");
        if (firstSlash == -1) {
            // 没有斜杠，说明没有仓库地址，添加默认仓库
            imageName = registryUrl + "/" + imageName;
        } else {
            // 有斜杠，检查第一部分是否包含点或冒号（域名标志）
            String firstPart = imageName.substring(0, firstSlash);
            if (!firstPart.contains(".") && !firstPart.contains(":")) {
                // 第一部分不包含点和冒号，可能是 namespace（如 library/nginx），添加仓库
                imageName = registryUrl + "/" + imageName;
            }
        }
        
        return imageName;
    }
    
    /**
     * 构建服务名称（直接使用服务名，不添加环境前缀）
     */
    private String buildServiceName(String serviceName, String envName) {
        return serviceName.toLowerCase();
    }
    
    /**
     * 构建网络名称（环境-网络类型）
     * 例如：dev-overlay, prod-overlay
     */
    private String buildNetworkName(String envName, String networkType) {
        return envName.toLowerCase() + "-" + networkType.toLowerCase();
    }
    
    /**
     * 确保网络存在，不存在则创建
     */
    private void ensureNetworkExists(DockerClient dockerClient, String networkName) {
        try {
            // 检查网络是否存在
            dockerClient.inspectNetworkCmd().withNetworkId(networkName).exec();
            log.info("网络已存在: {}", networkName);
        } catch (Exception e) {
            // 网络不存在，创建overlay网络
            try {
                log.info("网络不存在，开始创建: {}", networkName);
                dockerClient.createNetworkCmd()
                    .withName(networkName)
                    .withDriver("overlay")
                    .withAttachable(true)  // 允许容器直接连接
                    .exec();
                log.info("网络创建成功: {}", networkName);
            } catch (Exception createEx) {
                log.error("创建网络失败: {}", networkName, createEx);
                throw new RuntimeException("创建网络失败: " + networkName + ", " + createEx.getMessage(), createEx);
            }
        }
    }
    
    /**
     * 检查服务是否存在
     */
    private boolean checkServiceExists(DockerClient dockerClient, String serviceName) {
        try {
            dockerClient.inspectServiceCmd(serviceName).exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 创建服务
     */
    private void createService(
            DockerClient dockerClient,
            String serviceName,
            String imageName,
            DeployRequest request,
            Map<String, Object> envConfig,
            Map<String, Object> dockerParams) {
        
        // 设置镜像
        ContainerSpec containerSpec = new ContainerSpec()
            .withImage(imageName);
        
        // 应用Docker运行参数
        applyDockerParams(containerSpec, dockerParams);
        
        TaskSpec taskSpec = new TaskSpec()
            .withContainerSpec(containerSpec);
        
        // 应用资源限制
        applyResourceLimits(taskSpec, dockerParams);
        
        // 应用重启策略
        applyRestartPolicy(taskSpec, dockerParams);
        
        // 设置副本数
        Integer replicas = request.getReplicas() != null ? request.getReplicas() : 1;
        ServiceModeConfig modeConfig = new ServiceModeConfig()
            .withReplicated(new ServiceReplicatedModeOptions()
                .withReplicas(replicas));
        
        // 设置网络
        Environment environment = environmentMapper.selectById(request.getEnvironmentId());
        String networkName = buildNetworkName(environment.getName(), "overlay");
        
        List<NetworkAttachmentConfig> networks = new ArrayList<>();
        networks.add(new NetworkAttachmentConfig().withTarget(networkName));
        log.info("服务将连接到网络: {}", networkName);
        
        // 组装ServiceSpec
        ServiceSpec serviceSpec = new ServiceSpec()
            .withName(serviceName)
            .withTaskTemplate(taskSpec)
            .withMode(modeConfig)
            .withNetworks(networks);
        
        // 应用端口发布
        applyPortPublishing(serviceSpec, dockerParams);
        
        // 执行创建
        CreateServiceCmd cmd = dockerClient.createServiceCmd(serviceSpec);
        CreateServiceResponse response = cmd.exec();
        log.info("创建服务响应ID: {}", response.getId());
    }
    
    /**
     * 应用Docker运行参数（环境变量等）
     */
    private void applyDockerParams(ContainerSpec containerSpec, Map<String, Object> dockerParams) {
        if (dockerParams == null || dockerParams.isEmpty()) {
            return;
        }
        
        List<String> env = new ArrayList<>();
        for (Map.Entry<String, Object> entry : dockerParams.entrySet()) {
            String key = entry.getKey();
            // 跳过Docker内置参数和replicas参数
            if (isDockerBuiltInParam(key)) {
                continue;
            }
            env.add(key + "=" + entry.getValue());
        }
        
        if (!env.isEmpty()) {
            containerSpec.withEnv(env);
            log.info("应用环境变量: {} 个", env.size());
        }
    }
    
    /**
     * 应用资源限制（CPU、内存等）
     */
    private void applyResourceLimits(TaskSpec taskSpec, Map<String, Object> dockerParams) {
        if (dockerParams == null || dockerParams.isEmpty()) {
            return;
        }
        
        ResourceRequirements resources = new ResourceRequirements();
        ResourceSpecs limits = new ResourceSpecs();
        ResourceSpecs reservations = new ResourceSpecs();
        boolean hasLimits = false;
        boolean hasReservations = false;
        
        // CPU限制（单位：纳秒，1核心 = 1,000,000,000纳秒）
        if (dockerParams.containsKey("cpus")) {
            try {
                Double cpus = Double.parseDouble(dockerParams.get("cpus").toString());
                Long nanoCPUs = (long) (cpus * 1_000_000_000);
                
                limits.withNanoCPUs(nanoCPUs);
                hasLimits = true;
                log.info("应用CPU限制: {} cores ({}纳秒)", cpus, nanoCPUs);
            } catch (Exception e) {
                log.warn("解析CPU限制失败: {}", e.getMessage());
            }
        }
        
        // 内存限制（单位：字节）
        if (dockerParams.containsKey("memory")) {
            try {
                Long memoryBytes = parseMemoryString(dockerParams.get("memory").toString());
                
                limits.withMemoryBytes(memoryBytes);
                hasLimits = true;
                log.info("应用内存限制: {} bytes ({}MB)", memoryBytes, memoryBytes / 1024 / 1024);
            } catch (Exception e) {
                log.warn("解析内存限制失败: {}", e.getMessage());
            }
        }
        
        // 内存预留（软限制）
        if (dockerParams.containsKey("memory-reservation")) {
            try {
                Long memoryBytes = parseMemoryString(dockerParams.get("memory-reservation").toString());
                
                reservations.withMemoryBytes(memoryBytes);
                hasReservations = true;
                log.info("应用内存预留: {} bytes ({}MB)", memoryBytes, memoryBytes / 1024 / 1024);
            } catch (Exception e) {
                log.warn("解析内存预留失败: {}", e.getMessage());
            }
        }
        
        // 应用资源限制
        if (hasLimits) {
            resources.withLimits(limits);
        }
        if (hasReservations) {
            resources.withReservations(reservations);
        }
        
        if (hasLimits || hasReservations) {
            taskSpec.withResources(resources);
            log.info("资源限制已应用到TaskSpec");
        }
    }
    
    /**
     * 应用重启策略
     */
    private void applyRestartPolicy(TaskSpec taskSpec, Map<String, Object> dockerParams) {
        if (dockerParams == null || !dockerParams.containsKey("restart")) {
            return;
        }
        
        String restart = dockerParams.get("restart").toString();
        ServiceRestartPolicy restartPolicy = new ServiceRestartPolicy();
        
        switch (restart.toLowerCase()) {
            case "always":
                restartPolicy.withCondition(ServiceRestartCondition.ANY);
                break;
            case "on-failure":
                restartPolicy.withCondition(ServiceRestartCondition.ON_FAILURE);
                break;
            case "unless-stopped":
                restartPolicy.withCondition(ServiceRestartCondition.ANY);
                break;
            default:
                restartPolicy.withCondition(ServiceRestartCondition.NONE);
        }
        
        taskSpec.withRestartPolicy(restartPolicy);
        log.info("应用重启策略: {}", restart);
    }
    
    /**
     * 应用端口发布
     */
    private void applyPortPublishing(ServiceSpec serviceSpec, Map<String, Object> dockerParams) {
        if (dockerParams == null || dockerParams.isEmpty()) {
            return;
        }
        
        List<PortConfig> ports = new ArrayList<>();
        
        for (Map.Entry<String, Object> entry : dockerParams.entrySet()) {
            if (entry.getKey().equals("publish")) {
                String publishValue = entry.getValue().toString();
                try {
                    // 格式: 8080:8080 或 80:8080
                    String[] parts = publishValue.split(":");
                    if (parts.length == 2) {
                        Integer publishedPort = Integer.parseInt(parts[0]);
                        Integer targetPort = Integer.parseInt(parts[1]);
                        
                        PortConfig portConfig = new PortConfig()
                            .withPublishedPort(publishedPort)
                            .withTargetPort(targetPort)
                            .withProtocol(PortConfigProtocol.TCP);
                        
                        ports.add(portConfig);
                        log.info("应用端口映射: {}:{}", publishedPort, targetPort);
                    }
                } catch (Exception e) {
                    log.warn("解析端口映射失败: {}", publishValue, e);
                }
            }
        }
        
        if (!ports.isEmpty()) {
            EndpointSpec endpointSpec = new EndpointSpec().withPorts(ports);
            serviceSpec.withEndpointSpec(endpointSpec);
        }
    }
    
    /**
     * 解析内存字符串（支持 B, KB, MB, GB）
     */
    private Long parseMemoryString(String memory) {
        memory = memory.trim().toUpperCase();
        
        if (memory.endsWith("GB") || memory.endsWith("G")) {
            String num = memory.replace("GB", "").replace("G", "").trim();
            return (long) (Double.parseDouble(num) * 1024 * 1024 * 1024);
        } else if (memory.endsWith("MB") || memory.endsWith("M")) {
            String num = memory.replace("MB", "").replace("M", "").trim();
            return (long) (Double.parseDouble(num) * 1024 * 1024);
        } else if (memory.endsWith("KB") || memory.endsWith("K")) {
            String num = memory.replace("KB", "").replace("K", "").trim();
            return (long) (Double.parseDouble(num) * 1024);
        } else if (memory.endsWith("B")) {
            String num = memory.replace("B", "").trim();
            return Long.parseLong(num);
        } else {
            // 默认按字节处理
            return Long.parseLong(memory);
        }
    }
    
    /**
     * 判断是否为Docker内置参数
     */
    private boolean isDockerBuiltInParam(String key) {
        return key.equals("replicas") || 
               key.equals("cpus") || 
               key.equals("memory") || 
               key.equals("memory-reservation") || 
               key.equals("restart") || 
               key.equals("publish");
    }
    
    /**
     * 更新服务
     */
    private void updateService(
            DockerClient dockerClient,
            String serviceName,
            String imageName,
            DeployRequest request,
            Map<String, Object> dockerParams) {
        
        // 步骤1: 强制拉取最新镜像（即使标签相同也拉取最新digest）
        try {
            log.info("开始拉取最新镜像: {}", imageName);
            dockerClient.pullImageCmd(imageName)
                .exec(new ResultCallback.Adapter<>())
                .awaitCompletion();
            log.info("镜像拉取成功: {}", imageName);
        } catch (Exception e) {
            log.error("拉取镜像失败: {}", imageName, e);
            throw new RuntimeException("拉取镜像失败: " + e.getMessage());
        }
        
        // 步骤2: 获取当前服务信息
        Service service = dockerClient.inspectServiceCmd(serviceName).exec();
        ServiceSpec spec = service.getSpec();
        Long version = service.getVersion().getIndex();
        
        // 步骤3: 更新镜像（使用刚拉取的最新版本）
        // 通过先拉取镜像，确保即使标签相同（如 nginx:1.13.0）也会获取该标签的最新 digest
        ContainerSpec containerSpec = spec.getTaskTemplate().getContainerSpec();
        containerSpec.withImage(imageName);
        
        // 应用Docker运行参数
        applyDockerParams(containerSpec, dockerParams);
        
        TaskSpec taskSpec = spec.getTaskTemplate();
        
        // 应用资源限制
        applyResourceLimits(taskSpec, dockerParams);
        
        // 应用重启策略
        applyRestartPolicy(taskSpec, dockerParams);
        
        // 步骤4: 递增 ForceUpdate 以确保触发滚动更新
        Integer currentForceUpdate = taskSpec.getForceUpdate();
        Integer newForceUpdate = (currentForceUpdate != null ? currentForceUpdate : 0) + 1;
        taskSpec.withForceUpdate(newForceUpdate);
        
        // 步骤5: 更新副本数（如果指定）
        if (request.getReplicas() != null) {
            spec.getMode().getReplicated().withReplicas(request.getReplicas());
        }
        
        // 应用端口发布
        applyPortPublishing(spec, dockerParams);
        
        // 步骤6: 执行更新
        dockerClient.updateServiceCmd(serviceName, spec)
            .withVersion(version)
            .exec();
        
        log.info("服务更新成功（ForceUpdate={}）: {}", newForceUpdate, serviceName);
    }
    
    /**
     * 解析JSON配置
     */
    private Map<String, Object> parseConfig(String json) {
        try {
            return JSON.parseObject(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("解析配置失败", e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * 查询服务信息
     */
    private AppService getAppService(Long serviceId) {
        return serviceMapper.selectById(serviceId);
    }
    
    /**
     * 从镜像名称中提取版本号
     * 规则：
     * 1. 处理带 digest 的情况（如 nginx:1.25.3@sha256:xxx）
     * 2. 处理普通标签（如 nginx:1.25.3）
     * 3. 默认返回 latest
     */
    @Override
    public String extractVersionFromImage(String dockerImage) {
        if (dockerImage == null || dockerImage.trim().isEmpty()) {
            return "latest";
        }
        
        // 处理带 digest 的情况: registry.com/nginx:1.13.0@sha256:abc...
        if (dockerImage.contains("@sha256:")) {
            int atIndex = dockerImage.indexOf("@");
            String imageWithTag = dockerImage.substring(0, atIndex);
            
            // 提取标签
            if (imageWithTag.contains(":")) {
                int lastColonIndex = imageWithTag.lastIndexOf(":");
                String tagPart = imageWithTag.substring(lastColonIndex + 1);
                // 确保不是端口号（如 localhost:5000）
                if (!tagPart.contains("/")) {
                    return tagPart;
                }
            }
        }
        
        // 处理普通镜像标签: registry.com/nginx:1.13.0
        if (dockerImage.contains(":")) {
            int lastColonIndex = dockerImage.lastIndexOf(":");
            String tag = dockerImage.substring(lastColonIndex + 1);
            // 排除端口号的情况（如 localhost:5000/myapp）
            if (!tag.contains("/")) {
                return tag;
            }
        }
        
        // 默认为 'latest'
        return "latest";
    }

    /**
     * 获取镜像的所有可用版本（从镜像仓库）
     * 使用 Docker Registry HTTP API V2
     * API文档: https://docs.docker.com/registry/spec/api/
     */
    @Override
    public List<ImageVersionDTO> getAvailableVersions(String dockerImage) {
        List<ImageVersionDTO> versions = new ArrayList<>();
        
        try {
            // Step 1: 解析镜像名称，提取仓库地址和镜像名
            ImageInfo imageInfo = parseImageName(dockerImage);
            
            log.info("解析镜像信息: dockerImage={}, registryUrl={}, imageName={}", 
                dockerImage, imageInfo.getRegistryUrl(), imageInfo.getImageName());
            
            // Step 2: 构建 Registry API URL
            String tagsUrl = String.format("%s/v2/%s/tags/list", 
                imageInfo.getRegistryUrl(), imageInfo.getImageName());
            
            log.info("查询镜像版本列表: {}", tagsUrl);
            
            // Step 3: 调用 Registry API 获取标签列表
            HttpHeaders headers = new HttpHeaders();
            // 如果需要认证，这里添加 Authorization header
            // headers.set("Authorization", "Basic " + base64Credentials);
            
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                tagsUrl, HttpMethod.GET, entity, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                List<String> tags = (List<String>) body.get("tags");
                
                if (tags != null && !tags.isEmpty()) {
                    // 直接返回所有版本，不限制数量
                    for (String tag : tags) {
                        versions.add(ImageVersionDTO.builder()
                            .version(tag)
                            .digest(null)
                            .size(null)
                            .createdTime(null)
                            .isCurrent(false)
                            .build());
                    }
                    
                    log.info("成功获取 {} 个版本", versions.size());
                } else {
                    log.warn("镜像仓库返回空标签列表");
                }
            }
            
        } catch (Exception e) {
            log.error("获取镜像版本列表失败: dockerImage={}, error={}", dockerImage, e.getMessage(), e);
        }
        
        log.info("最终返回 {} 个版本", versions.size());
        return versions;
    }
    
    /**
     * 选择重要的标签（优先级排序）
     * 1. latest - 总是包含
     * 2. stable, mainline 等常见标签
     * 3. 版本号标签（数字开头）
     * 限制返回数量避免过多API调用
     */
    private List<String> selectImportantTags(List<String> allTags) {
        List<String> selected = new ArrayList<>();
        Set<String> priorityTags = new HashSet<>();
        priorityTags.add("latest");
        priorityTags.add("stable");
        priorityTags.add("mainline");
        priorityTags.add("alpine");
        
        // Step 1: 添加优先级标签
        for (String tag : allTags) {
            if (priorityTags.contains(tag)) {
                selected.add(tag);
            }
        }
        
        // Step 2: 添加版本号标签（以数字开头的）
        List<String> versionTags = new ArrayList<>();
        for (String tag : allTags) {
            if (!selected.contains(tag) && tag.matches("^\\d+.*")) {
                versionTags.add(tag);
            }
        }
        
        // 版本号标签按降序排序
        versionTags.sort((a, b) -> b.compareTo(a));
        
        // Step 3: 限制总数（优先标签 + 最多47个版本标签 = 最多50个）
        int remainingSlots = Math.min(50 - selected.size(), versionTags.size());
        selected.addAll(versionTags.subList(0, remainingSlots));
        
        return selected;
    }
    
    /**
     * 解析镜像名称，提取仓库地址和镜像名
     * 示例：
     * - docker.io/nginx:latest -> {registry: https://registry-1.docker.io, image: library/nginx}
     * - registry.com/myapp:1.0 -> {registry: https://registry.com, image: myapp}
     * - localhost:5000/test:v1 -> {registry: http://localhost:5000, image: test}
     */
    private ImageInfo parseImageName(String dockerImage) {
        ImageInfo info = new ImageInfo();
        
        // 移除标签和digest
        String imageWithoutTag = dockerImage;
        if (imageWithoutTag.contains("@sha256:")) {
            imageWithoutTag = imageWithoutTag.substring(0, imageWithoutTag.indexOf("@"));
        }
        if (imageWithoutTag.contains(":")) {
            int lastColon = imageWithoutTag.lastIndexOf(":");
            String afterColon = imageWithoutTag.substring(lastColon + 1);
            // 如果冒号后不是端口号，则是标签，需要移除
            if (!afterColon.matches("\\d+") && !afterColon.contains("/")) {
                imageWithoutTag = imageWithoutTag.substring(0, lastColon);
            }
        }
        
        // 检查是否包含仓库地址
        int firstSlash = imageWithoutTag.indexOf("/");
        if (firstSlash == -1) {
            // 没有斜杠，使用 Docker Hub
            info.setRegistryUrl("https://registry-1.docker.io");
            info.setImageName("library/" + imageWithoutTag);
        } else {
            String beforeSlash = imageWithoutTag.substring(0, firstSlash);
            
            // 检查是否包含域名或IP（包含点或冒号）
            if (beforeSlash.contains(".") || beforeSlash.contains(":")) {
                // 有仓库地址
                String registry = beforeSlash;
                String imageName = imageWithoutTag.substring(firstSlash + 1);
                
                // 判断协议
                if (registry.contains("localhost") || registry.matches(".*:\\d+")) {
                    info.setRegistryUrl("http://" + registry);
                } else {
                    info.setRegistryUrl("https://" + registry);
                }
                info.setImageName(imageName);
            } else {
                // 没有仓库地址，使用 Docker Hub
                info.setRegistryUrl("https://registry-1.docker.io");
                info.setImageName(imageWithoutTag);
            }
        }
        
        return info;
    }
    
    /**
     * 镜像信息内部类
     */
    private static class ImageInfo {
        private String registryUrl;
        private String imageName;
        
        public String getRegistryUrl() {
            return registryUrl;
        }
        
        public void setRegistryUrl(String registryUrl) {
            this.registryUrl = registryUrl;
        }
        
        public String getImageName() {
            return imageName;
        }
        
        public void setImageName(String imageName) {
            this.imageName = imageName;
        }
    }

    /**
     * 收集环境下所有服务的状态信息
     */
    public List<ServiceStatusInfo> collectServiceStatus(Environment environment, List<AppService> services) {
        List<ServiceStatusInfo> statusList = new ArrayList<>();
        
        if (services == null || services.isEmpty()) {
            return statusList;
        }
        
        try {
            // 解析环境配置
            Map<String, Object> config = parseConfig(environment.getConfig());
            List<String> managerHosts = (List<String>) config.get("swarmManagerHosts");
            
            if (managerHosts == null || managerHosts.isEmpty()) {
                log.error("环境[{}]的Swarm Manager地址未配置", environment.getName());
                return statusList;
            }
            
            // 连接到Swarm Manager
            DockerClient dockerClient = createDockerClientWithFailover(managerHosts);
            
            // 查询每个服务的状态
            for (AppService appService : services) {
                try {
                    String serviceName = buildServiceName(appService.getName(), environment.getName());
                    ServiceStatusInfo statusInfo = collectSingleServiceStatus(dockerClient, appService.getId(), serviceName);
                    statusList.add(statusInfo);
                } catch (Exception e) {
                    log.error("收集服务[{}]状态失败", appService.getName(), e);
                    // 服务不存在或异常，返回stopped状态
                    statusList.add(ServiceStatusInfo.builder()
                        .serviceId(appService.getId())
                        .serviceName(appService.getName())
                        .status("stopped")
                        .healthyInstances(0)
                        .instances(0)
                        .desiredInstances(0)
                        .build());
                }
            }
            
        } catch (Exception e) {
            log.error("收集环境[{}]服务状态失败", environment.getName(), e);
        }
        
        return statusList;
    }
    
    /**
     * 收集单个服务的状态信息
     */
    private ServiceStatusInfo collectSingleServiceStatus(DockerClient dockerClient, Long serviceId, String serviceName) {
        try {
            // 查询服务信息
            Service service = dockerClient.inspectServiceCmd(serviceName).exec();
            
            // 获取期望副本数

            Long replicasLong = service.getSpec().getMode().getReplicated().getReplicas();
            Integer desiredInstances = (replicasLong != null) ? replicasLong.intValue() : 0;
            
            // 查询服务的所有任务（Task）
            List<Task> tasks = dockerClient.listTasksCmd()
                .withServiceFilter(serviceName)
                .exec();
            
            // 统计运行中的任务数和健康的任务数
            int runningInstances = 0;
            int healthyInstances = 0;
            
            for (Task task : tasks) {
                TaskState taskState = task.getStatus().getState();
                if (taskState == TaskState.RUNNING) {
                    runningInstances++;
                    healthyInstances++;
                } else if (taskState == TaskState.STARTING || taskState == TaskState.PREPARING) {
                    runningInstances++;
                }
            }
            
            // 判断服务整体状态
            String status = determineServiceStatus(runningInstances, healthyInstances, desiredInstances, tasks);
            
            return ServiceStatusInfo.builder()
                .serviceId(serviceId)
                .serviceName(serviceName)
                .status(status)
                .healthyInstances(healthyInstances)
                .instances(runningInstances)
                .desiredInstances(desiredInstances)
                .build();
                
        } catch (Exception e) {
            log.debug("查询服务[{}]失败，可能不存在: {}", serviceName, e.getMessage());
            throw e;
        }
    }
    
    /**
     * 判断服务整体状态
     */
    private String determineServiceStatus(int runningInstances, int healthyInstances, int desiredInstances, List<Task> tasks) {
        // 如果没有任务，服务已停止
        if (tasks == null || tasks.isEmpty()) {
            return "stopped";
        }
        
        // 如果有失败的任务
        long failedTasks = tasks.stream()
            .filter(task -> task.getStatus().getState() == TaskState.FAILED)
            .count();
        
        if (failedTasks > 0 && healthyInstances == 0) {
            return "failed";
        }
        
        // 如果健康实例数等于期望值，运行正常
        if (healthyInstances == desiredInstances && desiredInstances > 0) {
            return "running";
        }
        
        // 如果有实例在运行，但未达到期望值
        if (runningInstances > 0 && runningInstances < desiredInstances) {
            return "deploying";
        }
        
        // 如果部分实例健康
        if (healthyInstances > 0 && healthyInstances < desiredInstances) {
            return "degraded";
        }
        
        // 默认返回未知
        return "unknown";
    }
    
    /**
     * 收集环境下所有服务的监控指标
     */
    public List<ServiceMetricsInfo> collectServiceMetrics(Environment environment, List<AppService> services) {
        // TODO: 后续实现真实的监控指标收集逻辑
        return new ArrayList<>();
    }

    
    /**
     * 从两次采样数据计算CPU使用率
     * 公式: ((cpuDelta / systemDelta) * numCpus) * 100.0
     */
    private Double calculateCpuPercentFromTwoSamples(Statistics firstStats, Statistics secondStats) {
        try {
            CpuStatsConfig firstCpu = firstStats.getCpuStats();
            CpuStatsConfig secondCpu = secondStats.getCpuStats();
            
            if (firstCpu == null || secondCpu == null) {
                log.warn("CPU统计配置为空");
                return null;
            }
            
            if (firstCpu.getCpuUsage() == null || secondCpu.getCpuUsage() == null) {
                log.warn("CPU使用量数据为空");
                return null;
            }
            
            Long firstTotalUsage = firstCpu.getCpuUsage().getTotalUsage();
            Long secondTotalUsage = secondCpu.getCpuUsage().getTotalUsage();
            Long firstSystemCpu = firstCpu.getSystemCpuUsage();
            Long secondSystemCpu = secondCpu.getSystemCpuUsage();
            
            if (firstTotalUsage == null || secondTotalUsage == null || 
                firstSystemCpu == null || secondSystemCpu == null) {
                log.warn("CPU数据字段为空");
                return null;
            }
            
            Long cpuDelta = secondTotalUsage - firstTotalUsage;
            Long systemDelta = secondSystemCpu - firstSystemCpu;
            
            log.info("CPU Delta: cpuDelta={}, systemDelta={}", cpuDelta, systemDelta);
            
            if (systemDelta == null || systemDelta <= 0 || cpuDelta == null || cpuDelta < 0) {
                log.warn("CPU Delta无效: cpuDelta={}, systemDelta={}", cpuDelta, systemDelta);
                return null;
            }
            
            // 获取CPU核心数
            Integer numCpus = secondCpu.getCpuUsage().getPercpuUsage() != null 
                ? secondCpu.getCpuUsage().getPercpuUsage().size()
                : 1;

            Double cpuPercent = (cpuDelta.doubleValue() / systemDelta.doubleValue()) * numCpus * 100.0;

            // 限制在0-100*numCpus之间
            return Math.max(0.0, Math.min(cpuPercent, numCpus * 100.0));
            
        } catch (Exception e) {
            log.error("计算CPU使用率失败", e);
            return null;
        }
    }
    
    /**
     * 计算CPU使用率百分比 (已弃用 - 使用calculateCpuPercentFromTwoSamples代替)
     * 公式: ((cpuDelta / systemDelta) * numCpus) * 100.0
     */
    @Deprecated
    private Double calculateCpuPercent(Statistics stats) {
        try {
            CpuStatsConfig cpuStats = stats.getCpuStats();
            CpuStatsConfig preCpuStats = stats.getPreCpuStats();
            
            if (cpuStats == null || preCpuStats == null) {
                log.warn("CPU统计配置为空");
                return null;
            }
            
            if (cpuStats.getCpuUsage() == null || preCpuStats.getCpuUsage() == null) {
                log.warn("CPU使用量数据为空");
                return null;
            }
            
            Long totalUsage = cpuStats.getCpuUsage().getTotalUsage();
            Long preTotalUsage = preCpuStats.getCpuUsage().getTotalUsage();
            Long systemCpuUsage = cpuStats.getSystemCpuUsage();
            Long preSystemCpuUsage = preCpuStats.getSystemCpuUsage();
            
            log.info("CPU原始数据: totalUsage={}, preTotalUsage={}, systemCpu={}, preSystemCpu={}",
                totalUsage, preTotalUsage, systemCpuUsage, preSystemCpuUsage);
            
            if (totalUsage == null || preTotalUsage == null || systemCpuUsage == null || preSystemCpuUsage == null) {
                log.warn("CPU数据字段为空");
                return null;
            }
            
            Long cpuDelta = totalUsage - preTotalUsage;
            Long systemDelta = systemCpuUsage - preSystemCpuUsage;
            
            log.info("CPU Delta: cpuDelta={}, systemDelta={}", cpuDelta, systemDelta);
            
            if (systemDelta == null || systemDelta <= 0 || cpuDelta == null) {
                log.warn("CPU Delta无效: cpuDelta={}, systemDelta={}", cpuDelta, systemDelta);
                return null;
            }
            
            // 获取CPU核心数
            Integer numCpus = cpuStats.getCpuUsage().getPercpuUsage() != null 
                ? cpuStats.getCpuUsage().getPercpuUsage().size()
                : 1;
            
            log.info("CPU核心数: {}", numCpus);
            
            Double cpuPercent = (cpuDelta.doubleValue() / systemDelta.doubleValue()) * numCpus * 100.0;
            
            log.info("计算得到CPU使用率: {}%", cpuPercent);
            
            // 限制在0-100*numCpus之间
            return Math.max(0.0, Math.min(cpuPercent, numCpus * 100.0));
            
        } catch (Exception e) {
            log.error("计算CPU使用率失败", e);
            return null;
        }
    }
    
    /**
     * 解析Docker时间戳并计算运行时间（秒）
     * Docker API 返回的时间戳格式："2024-12-15T09:30:45.123456789Z"
     */
    private Long parseDockerTimestamp(String timestamp) {
        try {
            if (timestamp == null || timestamp.isEmpty()) {
                return null;
            }
            
            // 移除纳秒部分（Java 8 的 DateTimeFormatter 不支持超过9位的纳秒）
            // "2024-12-15T09:30:45.123456789Z" -> "2024-12-15T09:30:45.123456Z"
            String processedTimestamp = timestamp;
            if (timestamp.contains(".")) {
                int dotIndex = timestamp.indexOf('.');
                int zIndex = timestamp.indexOf('Z');
                if (zIndex > dotIndex) {
                    String nanos = timestamp.substring(dotIndex + 1, zIndex);
                    // 只保疙6位（微秒）
                    if (nanos.length() > 6) {
                        nanos = nanos.substring(0, 6);
                    }
                    processedTimestamp = timestamp.substring(0, dotIndex + 1) + nanos + "Z";
                }
            }
            
            // 解析为 Instant
            java.time.Instant startTime = java.time.Instant.parse(processedTimestamp);
            java.time.Instant now = java.time.Instant.now();
            
            // 计算运行时间（秒）
            long seconds = java.time.Duration.between(startTime, now).getSeconds();
            
            return seconds > 0 ? seconds : 0;
            
        } catch (Exception e) {
            log.debug("解析时间戳[{}]失败: {}", timestamp, e.getMessage());
            return null;
        }
    }
}


