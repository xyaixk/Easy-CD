package com.easy.cd.controller;

import com.easy.cd.common.Result;
import com.easy.cd.dto.ImageVersionDTO;
import com.easy.cd.dto.ReplicaDetailDTO;
import com.easy.cd.dto.ServiceCreateDTO;
import com.easy.cd.dto.ServiceLogInstanceDTO;
import com.easy.cd.dto.ServiceUpdateDTO;
import com.easy.cd.service.ServiceLogService;
import com.easy.cd.service.ServiceManagementService;
import com.easy.cd.vo.ServiceDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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
    private final ServiceLogService serviceLogService;
    
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
     * 新增服务（异步）
     * 同步完成参数校验后提交到环境任务队列，立即返回任务ID，
     * 实际部署在后台串行执行，进度通过 /task 接口查询
     */
    @PostMapping
    public Result<Long> create(@RequestBody ServiceCreateDTO createDTO) {
        log.info("创建服务, serviceName: {}, environmentId: {}", 
                createDTO.getName(), createDTO.getEnvironmentId());
        
        Long taskId = serviceManagementService.create(createDTO);
        return Result.success(taskId);
    }
    
    /**
     * 更新服务（异步：返回任务ID）
     */
    @PutMapping("/{id}")
    public Result<Long> update(@PathVariable Long id, 
                               @RequestBody ServiceUpdateDTO updateDTO) {
        log.info("更新服务, id: {}", id);
        Long taskId = serviceManagementService.update(id, updateDTO);
        return Result.success(taskId);
    }
    
    /**
     * 删除服务（异步：返回任务ID）
     */
    @DeleteMapping("/{id}")
    public Result<Long> delete(@PathVariable Long id) {
        log.info("删除服务, id: {}", id);
        Long taskId = serviceManagementService.delete(id);
        return Result.success(taskId);
    }
    
    /**
     * 重启服务（异步：返回任务ID）
     */
    @PostMapping("/{id}/restart")
    public Result<Long> restart(@PathVariable Long id) {
        log.info("重启服务, id: {}", id);
        Long taskId = serviceManagementService.restart(id);
        return Result.success(taskId);
    }
    
    /**
     * 停止服务（异步：返回任务ID）
     */
    @PostMapping("/{id}/stop")
    public Result<Long> stop(@PathVariable Long id) {
        log.info("停止服务, id: {}", id);
        Long taskId = serviceManagementService.stop(id);
        return Result.success(taskId);
    }
    
    /**
     * 回滚服务（异步：返回任务ID）
     */
    @PostMapping("/{id}/rollback")
    public Result<Long> rollback(@PathVariable Long id, @RequestParam String targetVersion) {
        log.info("回滚服务, id: {}, targetVersion: {}", id, targetVersion);
        Long taskId = serviceManagementService.rollback(id, targetVersion);
        return Result.success(taskId);
    }
    
    /**
     * 调整副本数（异步：返回任务ID）
     */
    @PostMapping("/{id}/scale")
    public Result<Long> scale(@PathVariable Long id, @RequestParam Integer replicas) {
        log.info("调整副本数, id: {}, replicas: {}", id, replicas);
        Long taskId = serviceManagementService.scale(id, replicas);
        return Result.success(taskId);
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
     * 获取 Docker Swarm 中仍可追溯的服务日志实例
     */
    @GetMapping("/{id}/log-instances")
    public Result<List<ServiceLogInstanceDTO>> getLogInstances(@PathVariable Long id) {
        log.info("查看服务日志实例, id: {}", id);
        return Result.success(serviceLogService.listInstances(id));
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
}
