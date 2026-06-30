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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

/**
 * Docker Swarm 部署策略实现
 */
/**
 * Docker Swarm 部署策略实现。
 *
 * <p>职责概览：</p>
 * <ul>
 *   <li>根据环境配置连接 Swarm Manager，并在多个 Manager 之间做故障切换。</li>
 *   <li>完成服务的创建、更新、回滚、扩缩容、停止/删除等生命周期操作。</li>
 *   <li>解析/拼装镜像名称、服务名称、Docker 参数并同步数据库中的服务元数据。</li>
 *   <li>收集服务状态、日志与基础监控信息（部分指标预留实现）。</li>
 * </ul>
 *
 * <p>关键依赖：</p>
 * <ul>
 *   <li>{@link EnvironmentMapper}：读取环境配置（Swarm Manager 地址、Registry 等）。</li>
 *   <li>{@link ServiceMapper}：更新服务的版本与镜像信息。</li>
 *   <li>Docker Java Client：执行 Swarm Service 的各类操作。</li>
 * </ul>
 *
 * <p>异常处理：</p>
 * <ul>
 *   <li>所有对外方法均捕获异常并返回失败结果，避免上层出现未处理异常。</li>
 *   <li>关键步骤写入日志，便于故障排查与审计。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerDeployStrategy implements DeployStrategy {
    
    // 环境配置数据源：读取 Swarm Manager 列表、Registry 地址等
    private final EnvironmentMapper environmentMapper;

    // 服务数据源：用于回滚/更新时同步数据库中的镜像与版本
    private final ServiceMapper serviceMapper;

    // 随机数用于从多个 Manager 地址中选择入口
    private final Random random = new Random();

    // 访问 Registry API 或外部 HTTP 服务的客户端（带 1s 连接/10s 读取超时）
    private RestTemplate restTemplate;
    {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);   // 连接超时 1 秒：快速探测端口是否可达
        factory.setReadTimeout(10000);     // 读取超时 10 秒：连上后给足数据返回时间
        restTemplate = new RestTemplate(factory);
    }
    
    /**
     * 部署或更新 Docker Swarm 服务。
     *
     * <p>核心流程：</p>
     * <ol>
     *   <li>读取环境配置并解析 Swarm Manager 列表与 Registry 地址。</li>
     *   <li>连接可用的 Manager 节点（带故障切换）。</li>
     *   <li>拼装完整镜像名与服务名，并解析 Docker 参数。</li>
     *   <li>服务存在则更新，不存在则创建。</li>
     *   <li>读取服务真实镜像与副本数，返回部署结果。</li>
     * </ol>
     *
     * @param request 部署请求（包含服务名、镜像、参数、环境等）
     * @return 部署结果，失败时包含错误信息
     */
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
            // global 模式没有 replicated 字段，期望副本数取 0 占位（实际由节点数决定，状态刷新任务会修正）
            Integer replicas = 0;
            if (service.getSpec().getMode() != null
                    && service.getSpec().getMode().getReplicated() != null) {
                Long replicasLong = service.getSpec().getMode().getReplicated().getReplicas();
                replicas = replicasLong != null ? replicasLong.intValue() : 0;
            }
            
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
    
    /**
     * 停止服务（在 Swarm 中删除 Service，但保留数据库记录）。
     *
     * <p>实现细节：</p>
     * <ul>
     *   <li>优先使用数据库中的 externalServiceName；为空时按命名规则重建。</li>
     *   <li>删除 Swarm Service，相当于停止所有副本。</li>
     * </ul>
     *
     * @param serviceId 服务 ID
     * @return 停止结果
     */
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
    
    /**
     * 重启服务。
     *
     * <p>当服务已被删除时，会按原参数重新部署；否则通过递增
     * {@code forceUpdate} 触发 Swarm 滚动重启。</p>
     *
     * @param serviceId 服务 ID
     * @return 重启结果
     */
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
                
                // 直接从 app_service 获取用户配置的副本数
                Integer replicas = appService.getReplicas() != null && appService.getReplicas() > 0
                    ? appService.getReplicas()
                    : 1;
                log.info("使用 app_service 副本数: {}", replicas);
                
                // 构建部署请求
                DeployRequest deployRequest = new DeployRequest();
                deployRequest.setServiceId(serviceId);
                deployRequest.setServiceName(appService.getName());
                deployRequest.setDockerImage(appService.getDockerImage());
                deployRequest.setDockerParams(appService.getDockerParams());
                deployRequest.setReplicas(replicas); // 设置副本数
                deployRequest.setServiceMode(appService.getServiceMode()); // 透传部署模式（replicated/global）
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
    
    /**
     * 回滚服务到指定镜像版本。
     *
     * <p>实现细节：</p>
     * <ul>
     *   <li>根据目标版本替换镜像 Tag 并更新 Swarm Service。</li>
     *   <li>同步数据库中的镜像地址与版本字段。</li>
     * </ul>
     *
     * @param serviceId 服务 ID
     * @param targetVersion 目标版本（镜像 Tag）
     * @return 回滚结果
     */
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
            ContainerSpec containerSpec = spec.getTaskTemplate().getContainerSpec();
            containerSpec.withImage(newImage);
            
            // 解析 dockerParams 以获取 HealthCheck 和 UpdateConfig 配置
            Map<String, Object> dockerParams = appService.getDockerParams() != null 
                ? parseConfig(appService.getDockerParams()) 
                : Collections.emptyMap();
            
            // 重新应用 HealthCheck 配置（如果存在）
            applyHealthCheck(containerSpec, dockerParams);
            
            // 重新应用 UpdateConfig 配置（如果存在）
            applyUpdateConfig(spec, dockerParams);
            
            // 递增 ForceUpdate 以触发滚动更新
            TaskSpec taskSpec = spec.getTaskTemplate();
            Integer currentForceUpdate = taskSpec.getForceUpdate();
            Integer newForceUpdate = (currentForceUpdate != null ? currentForceUpdate : 0) + 1;
            taskSpec.withForceUpdate(newForceUpdate);
            
            dockerClient.updateServiceCmd(serviceName, spec)
                .withVersion(version)
                .exec();
            
            // 更新数据库中的版本信息
            appService.setVersion(targetVersion);
            appService.setDockerImage(newImage);
            serviceMapper.updateById(appService);
            
            log.info("Docker服务已回滚: {} 到版本: {} (ForceUpdate={})", serviceName, targetVersion, newForceUpdate);
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
    
    /**
     * 调整服务副本数（扩/缩容）。
     *
     * @param serviceId 服务 ID
     * @param replicas 目标副本数
     * @return 扩缩容结果
     */
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

            // global 模式不支持手动调整副本数，副本数由集群节点数决定
            if (spec.getMode() == null || spec.getMode().getReplicated() == null) {
                log.warn("服务 {} 为 global 模式，不支持手动调整副本数", serviceName);
                return DeployResult.failure("global 模式服务不支持手动调整副本数");
            }

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
    
    /**
     * 删除服务（从 Swarm 中移除 Service）。
     *
     * <p>与 {@link #stop(Long)} 类似，但语义上表示彻底删除。</p>
     *
     * @param serviceId 服务 ID
     * @return 删除结果
     */
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
    
    /**
     * 查询服务状态。
     *
     * <p>从 Swarm 获取服务详情与任务列表并换算成统一状态字段。</p>
     *
     * @param serviceId 服务 ID
     * @return 状态结果（包含运行/健康/期望副本数）
     */
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
    
    /**
     * 返回部署类型标识（用于前端/上层区分策略）。
     *
     * @return 部署类型字符串
     */
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
            String beforeColon = imageName.substring(0, colonIndex);
            
            // 如果冒号后面是纯数字或包含斜杠，且冒号前面没有 "/"，说明是端口号
            // 如 localhost:5000/nginx、192.168.1.1:5000/nginx
            // 但如果冒号前面有 "/"（如 docker.io/nginx:8），则是镜像路径+标签，不是端口号
            if ((afterColon.matches("\\d+") || afterColon.contains("/")) && !beforeColon.contains("/")) {
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
    /**
     * 获取网络模式
     * 优先级: Docker参数 > 环境配置 > 默认(overlay)
     */
    private String getNetworkMode(Map<String, Object> dockerParams, Environment environment) {
        // 1. 优先使用 Docker 参数中指定的网络模式
        if (dockerParams != null && dockerParams.containsKey("network")) {
            String network = dockerParams.get("network").toString();
            log.info("使用 Docker 参数指定的网络模式: {}", network);
            return network;
        }
        
        // 2. 其次使用环境配置中的默认网络模式
        if (environment != null && environment.getConfig() != null) {
            Map<String, Object> envConfig = parseConfig(environment.getConfig());
            if (envConfig.containsKey("networkMode")) {
                String network = envConfig.get("networkMode").toString();
                log.info("使用环境配置的网络模式: {}", network);
                return network;
            }
        }
        
        // 3. 默认使用 overlay 网络
        log.info("使用默认网络模式: overlay");
        return "overlay";
    }
    
    /**
     * 构建网络名称
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

                // 随机生成 80-90 的网段
                int segment = 80 + random.nextInt(11);
                String subnet = "172." + segment + ".0.0/16";
                log.info("指定网段: {}", subnet);

                Network.Ipam.Config ipamConfig = new Network.Ipam.Config()
                    .withSubnet(subnet);

                Network.Ipam ipam = new Network.Ipam()
                    .withConfig(Collections.singletonList(ipamConfig));

                dockerClient.createNetworkCmd()
                    .withName(networkName)
                    .withDriver("overlay")
                    .withAttachable(true)  // 允许容器直接连接
                    .withIpam(ipam)
                    .exec();
                log.info("网络创建成功: {}，网段: {}", networkName, subnet);
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
        
        // 应用 HealthCheck
        applyHealthCheck(containerSpec, dockerParams);
        
        // 设置副本数（防御性处理：为null或<=0时默认1）
        Integer replicas = (request.getReplicas() != null && request.getReplicas() > 0) ? request.getReplicas() : 1;
        // 根据部署模式切换 ServiceMode：global 不指定副本数，由集群节点数决定
        boolean isGlobal = "global".equalsIgnoreCase(request.getServiceMode());
        ServiceModeConfig modeConfig = new ServiceModeConfig();
        if (isGlobal) {
            modeConfig.withGlobal(new ServiceGlobalModeOptions());
            log.info("部署模式: global（每节点一个副本）");
        } else {
            modeConfig.withReplicated(new ServiceReplicatedModeOptions().withReplicas(replicas));
            log.info("部署模式: replicated, 副本数={}", replicas);
        }
        
        // 设置网络 - 根据参数决定使用 overlay 还是 host 模式
        Environment environment = environmentMapper.selectById(request.getEnvironmentId());
        String networkMode = getNetworkMode(dockerParams, environment);
        
        ServiceSpec serviceSpec;
        if ("host".equalsIgnoreCase(networkMode)) {
            // Host 网络模式 - 使用 host 网络
            log.info("使用 host 网络模式");
            List<NetworkAttachmentConfig> networks = new ArrayList<>();
            networks.add(new NetworkAttachmentConfig().withTarget("host"));
            
            // 网络配置需要在 TaskSpec 中设置
            taskSpec.withNetworks(networks);
            
            serviceSpec = new ServiceSpec()
                .withName(serviceName)
                .withTaskTemplate(taskSpec)
                .withMode(modeConfig);
        } else {
            // Overlay 网络模式（默认）
            String networkName = buildNetworkName(environment.getName(), "overlay");
            ensureNetworkExists(dockerClient, networkName);
            
            List<NetworkAttachmentConfig> networks = new ArrayList<>();
            networks.add(new NetworkAttachmentConfig().withTarget(networkName));
            log.info("使用 overlay 网络: {}", networkName);
            
            // 网络配置需要在 TaskSpec 中设置
            taskSpec.withNetworks(networks);
            
            serviceSpec = new ServiceSpec()
                .withName(serviceName)
                .withTaskTemplate(taskSpec)
                .withMode(modeConfig);
        }
        
        // 应用端口发布
        applyPortPublishing(serviceSpec, dockerParams);
        
        // 应用 UpdateConfig（滚动更新策略）
        applyUpdateConfig(serviceSpec, dockerParams);
        
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
               key.equals("publish") ||
               key.equals("network") ||  // 支持网络模式参数
               // HealthCheck 参数
               key.equals("healthcheck") ||
               key.equals("healthcheck_interval") ||
               key.equals("healthcheck_timeout") ||
               key.equals("healthcheck_retries") ||
               key.equals("healthcheck_start_period") ||
               // UpdateConfig 参数
               key.equals("update_parallelism") ||
               key.equals("update_delay") ||
               key.equals("update_monitor") ||
               key.equals("update_failure_action") ||
               key.equals("update_order");
    }
    
    /**
     * 应用 HealthCheck 配置
     */
    private void applyHealthCheck(ContainerSpec containerSpec, Map<String, Object> dockerParams) {
        if (dockerParams == null || !dockerParams.containsKey("healthcheck")) {
            return;
        }
        
        String healthCmd = dockerParams.get("healthcheck").toString();
        
        HealthCheck healthCheck = new HealthCheck()
            .withTest(Arrays.asList("CMD-SHELL", healthCmd))
            .withInterval(parseDuration(dockerParams.getOrDefault("healthcheck_interval", "10s").toString()))
            .withTimeout(parseDuration(dockerParams.getOrDefault("healthcheck_timeout", "5s").toString()))
            .withRetries(parseInt(dockerParams.getOrDefault("healthcheck_retries", 3)))
            .withStartPeriod(parseDuration(dockerParams.getOrDefault("healthcheck_start_period", "30s").toString()));
        
        containerSpec.withHealthCheck(healthCheck);
        log.info("应用 HealthCheck: 命令={}, 间隔={}ns, 超时={}ns, 重试={}, 启动期={}ns", 
            healthCmd, healthCheck.getInterval(), healthCheck.getTimeout(), 
            healthCheck.getRetries(), healthCheck.getStartPeriod());
    }
    
    /**
     * 应用 UpdateConfig 配置（滚动更新策略）
     */
    private void applyUpdateConfig(ServiceSpec serviceSpec, Map<String, Object> dockerParams) {
        if (dockerParams == null) {
            return;
        }
        
        // 检查是否有 UpdateConfig 相关参数
        boolean hasUpdateConfig = dockerParams.containsKey("update_parallelism") ||
                                  dockerParams.containsKey("update_delay") ||
                                  dockerParams.containsKey("update_monitor") ||
                                  dockerParams.containsKey("update_failure_action") ||
                                  dockerParams.containsKey("update_order");
        
        if (!hasUpdateConfig) {
            return;
        }
        
        // 解析 failureAction
        String failureActionStr = dockerParams.getOrDefault("update_failure_action", "pause").toString().toLowerCase();
        UpdateFailureAction failureAction;
        switch (failureActionStr) {
            case "continue":
                failureAction = UpdateFailureAction.CONTINUE;
                break;
            case "rollback":
                failureAction = UpdateFailureAction.ROLLBACK;
                break;
            case "pause":
            default:
                failureAction = UpdateFailureAction.PAUSE;
                break;
        }
        
        // 解析 order
        String orderStr = dockerParams.getOrDefault("update_order", "stop-first").toString().toLowerCase();
        UpdateOrder order;
        switch (orderStr) {
            case "start-first":
                order = UpdateOrder.START_FIRST;
                break;
            case "stop-first":
            default:
                order = UpdateOrder.STOP_FIRST;
                break;
        }
        
        UpdateConfig updateConfig = new UpdateConfig()
            .withParallelism(parseInt(dockerParams.getOrDefault("update_parallelism", 1)))
            .withDelay(parseDuration(dockerParams.getOrDefault("update_delay", "0s").toString()))
            .withMonitor(parseDuration(dockerParams.getOrDefault("update_monitor", "0s").toString()))
            .withFailureAction(failureAction)
            .withOrder(order);
        
        serviceSpec.withUpdateConfig(updateConfig);
        log.info("应用 UpdateConfig: parallelism={}, delay={}ns, monitor={}ns, failureAction={}, order={}",
            updateConfig.getParallelism(), updateConfig.getDelay(), updateConfig.getMonitor(),
            updateConfig.getFailureAction(), updateConfig.getOrder());
    }
    
    /**
     * 解析时间字符串为纳秒（支持 s, m, h）
     * 例如：10s -> 10000000000, 1m -> 60000000000
     */
    private Long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return 0L;
        }
        
        duration = duration.trim().toLowerCase();
        
        try {
            if (duration.endsWith("h")) {
                long hours = Long.parseLong(duration.replace("h", "").trim());
                return hours * 60 * 60 * 1_000_000_000L;
            } else if (duration.endsWith("m")) {
                long minutes = Long.parseLong(duration.replace("m", "").trim());
                return minutes * 60 * 1_000_000_000L;
            } else if (duration.endsWith("s")) {
                long seconds = Long.parseLong(duration.replace("s", "").trim());
                return seconds * 1_000_000_000L;
            } else if (duration.endsWith("ms")) {
                long millis = Long.parseLong(duration.replace("ms", "").trim());
                return millis * 1_000_000L;
            } else {
                // 默认按秒处理
                return Long.parseLong(duration) * 1_000_000_000L;
            }
        } catch (NumberFormatException e) {
            log.warn("解析时间格式失败: {}, 使用默认值0", duration);
            return 0L;
        }
    }
    
    /**
     * 解析整数值（支持 String 和 Number 类型）
     */
    private Integer parseInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            log.warn("解析整数失败: {}, 使用默认值0", value);
            return 0;
        }
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
        
        // 应用 HealthCheck
        applyHealthCheck(containerSpec, dockerParams);
        
        // 步骤4: 递增 ForceUpdate 以确保触发滚动更新
        Integer currentForceUpdate = taskSpec.getForceUpdate();
        Integer newForceUpdate = (currentForceUpdate != null ? currentForceUpdate : 0) + 1;
        taskSpec.withForceUpdate(newForceUpdate);
        
        // 步骤5: 更新副本数（仅 replicated 模式）
        if (request.getReplicas() != null
                && spec.getMode() != null
                && spec.getMode().getReplicated() != null) {
            spec.getMode().getReplicated().withReplicas(request.getReplicas());
        }
        
        // 应用端口发布
        applyPortPublishing(spec, dockerParams);
        
        // 应用 UpdateConfig（滚动更新策略）
        applyUpdateConfig(spec, dockerParams);
        
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
    /**
     * 从镜像地址中解析版本号（Tag 或 Digest）。
     *
     * @param dockerImage 镜像地址（可能包含 Tag 或 Digest）
     * @return 解析出的版本号；无法解析时返回 null
     */
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
    /**
     * 获取镜像可用版本列表。
     *
     * <p>优先使用环境配置中的 registryUrl，如果没有则根据镜像地址解析 Registry。</p>
     *
     * @param dockerImage 镜像地址
     * @param registryUrl 环境配置中的镜像仓库地址（优先级高于镜像名称中的地址）
     * @return 版本列表（按时间倒序/Registry 返回顺序）
     */
    public List<ImageVersionDTO> getAvailableVersions(String dockerImage, String registryUrl) {
        List<ImageVersionDTO> versions = new ArrayList<>();
        
        try {
            // Step 1: 解析镜像名称，提取仓库地址和镜像名
            ImageInfo imageInfo = parseImageName(dockerImage);
            
            // 优先使用环境配置中的 registryUrl
            if (registryUrl != null && !registryUrl.isEmpty()) {
                imageInfo.setRegistryUrl(registryUrl);
                // 如果镜像本身没有指定 registry，parseImageName 会按 Docker Hub 规则加 library/ 前缀
                // 但使用私有 Registry 时不需要这个前缀，去掉它
                if (imageInfo.getImageName().startsWith("library/")) {
                    imageInfo.setImageName(imageInfo.getImageName().substring("library/".length()));
                }
                log.info("使用环境配置中的 registryUrl: {}", registryUrl);
            }

            log.info("解析镜像信息: dockerImage={}, registryUrl={}, imageName={}",
                dockerImage, imageInfo.getRegistryUrl(), imageInfo.getImageName());

            // Step 2: 调用 Registry API 获取标签列表（支持 HTTP/HTTPS 自动探测）
            versions = fetchRegistryTags(imageInfo.getRegistryUrl(), imageInfo.getImageName());

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
    /**
     * 调用 Registry API 获取镜像标签列表，支持 HTTP/HTTPS 自动探测。
     * <p>如果 registryUrl 已包含协议前缀（http:// 或 https://），直接使用；
     * 否则优先探测 HTTPS（现代 Registry 默认协议），HTTPS 不通或返回非合法响应时再降级 HTTP。
     * 不能反过来"先 HTTP 后 HTTPS"——某些公网 80 端口存在网关默认页或非标准跳转，
     * 会让 HTTP 请求得到 2xx 但 body 不是合法 Registry 响应，从而错过真正的 HTTPS Registry。</p>
     */
    private List<ImageVersionDTO> fetchRegistryTags(String registryUrl, String imageName) {
        boolean hasProtocol = registryUrl.startsWith("http://") || registryUrl.startsWith("https://");

        // 已有协议前缀，直接使用
        if (hasProtocol) {
            String tagsUrl = String.format("%s/v2/%s/tags/list", registryUrl, imageName);
            log.info("查询镜像版本列表: {}", tagsUrl);
            try {
                return doFetchRegistryTags(tagsUrl);
            } catch (Exception e) {
                log.error("Registry 请求失败: {}, error: {}", tagsUrl, e.getMessage());
                return new ArrayList<>();
            }
        }

        // 没有协议前缀：优先探测 HTTPS
        String httpsUrl = String.format("https://%s/v2/%s/tags/list", registryUrl, imageName);
        log.info("探测 Registry HTTPS: {}", httpsUrl);
        try {
            List<ImageVersionDTO> httpsResult = doFetchRegistryTags(httpsUrl);
            if (httpsResult != null && !httpsResult.isEmpty()) {
                return httpsResult;
            }
            log.warn("Registry HTTPS 返回空标签，降级尝试 HTTP");
        } catch (Exception e) {
            log.warn("Registry HTTPS 探测失败: {}, error: {}", httpsUrl, e.getMessage());
        }

        // HTTPS 不通或返回空，降级探测 HTTP
        String httpUrl = String.format("http://%s/v2/%s/tags/list", registryUrl, imageName);
        log.info("探测 Registry HTTP: {}", httpUrl);
        try {
            return doFetchRegistryTags(httpUrl);
        } catch (Exception e) {
            log.error("Registry HTTP 探测也失败: {}, error: {}", httpUrl, e.getMessage());
        }

        return new ArrayList<>();
    }

    /**
     * 执行 Registry API 请求获取标签列表
     */
    private List<ImageVersionDTO> doFetchRegistryTags(String tagsUrl) {
        List<ImageVersionDTO> versions = new ArrayList<>();

        HttpHeaders headers = new HttpHeaders();
        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<Map> response = restTemplate.exchange(
            tagsUrl, HttpMethod.GET, entity, Map.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Map<String, Object> body = response.getBody();
            List<String> tags = (List<String>) body.get("tags");

            if (tags != null && !tags.isEmpty()) {
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

        return versions;
    }

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
                
                // 判断协议：localhost 和带端口的默认 http，其余保持原样不强制 https
                if (registry.contains("localhost") || registry.matches(".*:\\d+")) {
                    info.setRegistryUrl("http://" + registry);
                } else {
                    info.setRegistryUrl(registry);
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
    /**
     * 收集环境下所有服务的状态信息。
     *
     * <p>对每个服务执行单独的状态查询；若服务不存在或查询失败，
     * 以 stopped 状态返回，保证调用方能得到完整列表。</p>
     *
     * @param environment 环境信息（包含 Swarm 配置）
     * @param services 服务列表
     * @return 服务状态列表（与输入服务一一对应）
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
            int successCount = 0;
            int failCount = 0;
            for (AppService appService : services) {
                try {
                    String serviceName = appService.getExternalServiceName() != null
                        ? appService.getExternalServiceName()
                        : buildServiceName(appService.getName(), environment.getName());
                    ServiceStatusInfo statusInfo = collectSingleServiceStatus(dockerClient, appService.getId(), serviceName);
                    statusList.add(statusInfo);
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    log.debug("收集服务[{}]状态失败", appService.getName(), e);
                    // 服务不存在或异常，返回stopped状态，但保留用户配置的副本数
                    Integer desiredReplicas = appService.getReplicas() != null && appService.getReplicas() > 0
                        ? appService.getReplicas()
                        : 1;
                    statusList.add(ServiceStatusInfo.builder()
                        .serviceId(appService.getId())
                        .serviceName(appService.getName())
                        .status("stopped")
                        .healthyInstances(0)
                        .instances(0)
                        .desiredInstances(desiredReplicas)
                        .build());
                }
            }
            
            // 汇总日志：只在有失败时打印一次
            if (failCount > 0) {
                log.warn("环境[{}]服务状态收集完成：成功{}个，失败{}个", 
                    environment.getName(), successCount, failCount);
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
        
        if (desiredInstances == 0 && runningInstances == 0 && healthyInstances == 0) {
            return "stopped";
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
    /**
     * 收集环境下所有服务的监控指标。
     *
     * <p>目前仅提供接口占位，真实指标采集逻辑待补充。</p>
     *
     * @param environment 环境信息
     * @param services 服务列表
     * @return 监控指标列表
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
    
    /**
     * 流式推送服务聚合日志（SSE） - 在Manager节点查询所有副本的聚合日志
     */
    /**
     * 通过 SSE 流式推送服务聚合日志（在 Manager 节点查询）。
     *
     * <p>说明：</p>
     * <ul>
     *   <li>日志查询使用 Service ID，避免名称冲突。</li>
     *   <li>支持 tail（仅拉取最近 N 行）与 follow（持续跟随）。</li>
     * </ul>
     *
     * @param environment 环境信息（含 Manager 列表）
     * @param serviceName 服务名称
     * @param tail 仅返回最近 N 行（可为空）
     * @param follow 是否持续跟随
     * @return SSE emitter
     */
    @Override
    public SseEmitter streamServiceLogs(Environment environment, String serviceName, Integer tail, Boolean follow) {
        log.info("开始获取服务日志: serviceName={}, tail={}, follow={}, environmentId={}", 
                serviceName, tail, follow, environment.getId());
        
        // 初始化SseEmitter，设置超时时间（30分钟）
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        
        // 异步推送日志
        new Thread(() -> {
            DockerClient dockerClient = null;
            try {
                // 直接使用传入的Environment对象
                Map<String, Object> config = parseConfig(environment.getConfig());
                List<String> managerHosts = (List<String>) config.get("swarmManagerHosts");
                
                if (managerHosts == null || managerHosts.isEmpty()) {
                    emitter.completeWithError(new RuntimeException("Swarm Manager地址未配置"));
                    return;
                }
                
                // 连接到Swarm Manager - Service日志可以在Manager节点查询
                dockerClient = createDockerClientWithFailover(managerHosts);
                
                int effectiveTail = (tail != null && tail > 0) ? Math.min(tail, 1000) : 100;

                com.github.dockerjava.api.model.Service dockerService;
                try {
                    dockerService = dockerClient.inspectServiceCmd(serviceName).exec();
                } catch (Exception e) {
                    log.error("查询服务失败: {}", serviceName, e);
                    emitter.completeWithError(new RuntimeException("服务不存在: " + serviceName));
                    return;
                }

                String serviceId = dockerService.getId();
                Integer since = resolveCurrentTaskSince(dockerClient, serviceName);

                ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        try {
                            String logLine = new String(frame.getPayload()).trim();
                            if (!logLine.isEmpty()) {
                                // 通过SSE发送日志
                                emitter.send(SseEmitter.event()
                                    .data(logLine)
                                    .name("log"));
                            }
                        } catch (IOException e) {
                            log.error("发送日志失败", e);
                            try {
                                emitter.completeWithError(e);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    
                    @Override
                    public void onComplete() {
                        log.info("服务日志流结束: {}", serviceName);
                        emitter.complete();
                    }
                    
                    @Override
                    public void onError(Throwable throwable) {
                        log.error("服务日志流错误", throwable);
                        emitter.completeWithError(throwable);
                    }
                };

                com.github.dockerjava.api.command.LogSwarmObjectCmd logCmd = dockerClient.logServiceCmd(serviceId)
                    .withStdout(true)
                    .withStderr(true)
                    .withFollow(follow)
                    .withTail(effectiveTail);

                if (since != null && since > 0) {
                    logCmd.withSince(since);
                }

                logCmd.exec(callback);
                
            } catch (Exception e) {
                log.error("获取服务日志失败", e);
                emitter.completeWithError(e);
            }
        }).start();
        
        // 设置超时和完成回调
        emitter.onTimeout(() -> {
            log.info("服务日志流超时: {}", serviceName);
            emitter.complete();
        });
        
        emitter.onCompletion(() -> {
            log.info("服务日志流完成: {}", serviceName);
        });
        
        return emitter;
    }

    private Integer resolveCurrentTaskSince(DockerClient dockerClient, String serviceName) {
        try {
            List<Task> tasks = dockerClient.listTasksCmd()
                .withServiceFilter(serviceName)
                .exec();

            if (tasks == null || tasks.isEmpty()) {
                return null;
            }

            return selectLatestTasksBySlot(tasks).stream()
                .map(this::parseTaskTimestampToInstant)
                .filter(instant -> instant != null && !instant.equals(java.time.Instant.EPOCH))
                .min(java.time.Instant::compareTo)
                .map(instant -> (int) instant.getEpochSecond())
                .orElse(null);
        } catch (Exception e) {
            log.warn("计算服务[{}]日志 since 时间失败，将回退到完整服务日志", serviceName, e);
            return null;
        }
    }

    private List<Task> selectLatestTasksBySlot(List<Task> tasks) {
        Map<String, Task> latestTaskByKey = new LinkedHashMap<>();
        for (Task task : tasks) {
            String key = task.getSlot() != null ? "slot:" + task.getSlot() : "task:" + task.getId();
            Task existing = latestTaskByKey.get(key);
            if (existing == null || parseTaskTimestampToInstant(task).isAfter(parseTaskTimestampToInstant(existing))) {
                latestTaskByKey.put(key, task);
            }
        }
        return new ArrayList<>(latestTaskByKey.values());
    }

    private java.time.Instant parseTaskTimestampToInstant(Task task) {
        try {
            if (task == null || task.getStatus() == null || task.getStatus().getTimestamp() == null) {
                return java.time.Instant.EPOCH;
            }

            String timestamp = task.getStatus().getTimestamp();
            if (timestamp.contains(".")) {
                int dotIndex = timestamp.indexOf('.');
                int zIndex = timestamp.indexOf('Z');
                if (zIndex > dotIndex) {
                    String nanos = timestamp.substring(dotIndex + 1, zIndex);
                    if (nanos.length() > 6) {
                        nanos = nanos.substring(0, 6);
                    }
                    timestamp = timestamp.substring(0, dotIndex + 1) + nanos + "Z";
                }
            }

            return java.time.Instant.parse(timestamp);
        } catch (Exception e) {
            return java.time.Instant.EPOCH;
        }
    }
}
