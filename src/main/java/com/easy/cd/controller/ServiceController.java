package com.easy.cd.controller;

import com.easy.cd.common.Result;
import com.easy.cd.dto.ImageVersionDTO;
import com.easy.cd.dto.ReplicaDetailDTO;
import com.easy.cd.dto.ServiceCreateDTO;
import com.easy.cd.dto.ServiceUpdateDTO;
import com.easy.cd.service.ServiceManagementService;
import com.easy.cd.vo.ServiceDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 服务管理Controller
 */
@Slf4j
@RestController
@RequestMapping("/service")
@RequiredArgsConstructor
public class ServiceController {
    
    private final ServiceManagementService serviceManagementService;
    
    /**
     * 查询环境下的所有服务
     */
    @GetMapping("/list")
    public Result<List<ServiceDetailVO>> list(@RequestParam Long environmentId) {
        log.info("查询环境服务列表, environmentId: {}", environmentId);
        List<ServiceDetailVO> services = serviceManagementService.listByEnvironment(environmentId);
        return Result.success(services);
    }
    
    /**
     * 根据ID查询服务详情
     */
    @GetMapping("/{id}")
    public Result<ServiceDetailVO> getById(@PathVariable Long id) {
        log.info("查询服务详情, id: {}", id);
        ServiceDetailVO service = serviceManagementService.getById(id);
        return Result.success(service);
    }
    
    /**
     * 新增服务
     * 核心流程：
     * 1. 参数校验（服务名唯一性、必填字段等）
     * 2. 构建部署请求对象
     * 3. 调用部署服务执行实际部署
     * 4. 部署成功后保存服务信息到数据库
     * 5. 返回创建的服务信息
     */
    @PostMapping
    public Result<ServiceDetailVO> create(@RequestBody ServiceCreateDTO createDTO) {
        log.info("创建服务, serviceName: {}, environmentId: {}", 
                createDTO.getName(), createDTO.getEnvironmentId());
        
        ServiceDetailVO result = serviceManagementService.create(createDTO);
        return Result.success(result);
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
    @PutMapping("/{id}")
    public Result<ServiceDetailVO> update(@PathVariable Long id, 
                                          @RequestBody ServiceUpdateDTO updateDTO) {
        log.info("更新服务, id: {}", id);
        ServiceDetailVO result = serviceManagementService.update(id, updateDTO);
        return Result.success(result);
    }
    
    /**
     * 删除服务
     */
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        log.info("删除服务, id: {}", id);
        serviceManagementService.delete(id);
        return Result.success(true);
    }
    
    /**
     * 重启服务
     */
    @PostMapping("/{id}/restart")
    public Result<Boolean> restart(@PathVariable Long id) {
        log.info("重启服务, id: {}", id);
        serviceManagementService.restart(id);
        return Result.success(true);
    }
    
    /**
     * 停止服务
     */
    @PostMapping("/{id}/stop")
    public Result<Boolean> stop(@PathVariable Long id) {
        log.info("停止服务, id: {}", id);
        serviceManagementService.stop(id);
        return Result.success(true);
    }
    
    /**
     * 回滚服务
     */
    @PostMapping("/{id}/rollback")
    public Result<Boolean> rollback(@PathVariable Long id, @RequestParam String targetVersion) {
        log.info("回滚服务, id: {}, targetVersion: {}", id, targetVersion);
        serviceManagementService.rollback(id, targetVersion);
        return Result.success(true);
    }
    
    /**
     * 调整副本数
     */
    @PostMapping("/{id}/scale")
    public Result<Boolean> scale(@PathVariable Long id, @RequestParam Integer replicas) {
        log.info("调整副本数, id: {}, replicas: {}", id, replicas);
        serviceManagementService.scale(id, replicas);
        return Result.success(true);
    }
    
    /**
     * 查看服务副本列表
     */
    @GetMapping("/{id}/replicas")
    public Result<List<ReplicaDetailDTO>> getReplicas(@PathVariable Long id) {
        log.info("查看服务副本, id: {}", id);
        List<ReplicaDetailDTO> replicas = (List<ReplicaDetailDTO>) serviceManagementService.getReplicas(id);
        return Result.success(replicas);
    }
    
    /**
     * 获取服务镜像的所有可用版本（用于回滚）
     */
    @GetMapping("/{id}/versions")
    public Result<List<ImageVersionDTO>> getAvailableVersions(@PathVariable Long id) {
        log.info("获取服务可用版本, id: {}", id);
        List<ImageVersionDTO> versions = serviceManagementService.getAvailableVersions(id);
        return Result.success(versions);
    }
    
    /**
     * 查看服务日志（SSE流式传输）
     * @param serviceId 服务ID
     * @param tail 获取最后N行，默认500行
     * @param follow 是否持续推送新日志，默认false
     */
    @GetMapping(value = "/{serviceId}/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(
            @PathVariable Long serviceId,
            @RequestParam(required = false) String replicaId,
            @RequestParam(defaultValue = "500") Integer tail,
            @RequestParam(defaultValue = "false") Boolean follow) {
        log.info("查看服务日志, serviceId: {}, replicaId: {}, tail: {}, follow: {}", 
                serviceId, replicaId, tail, follow);
        return serviceManagementService.streamLogs(serviceId, replicaId, tail, follow);
    }
}
