package com.easy.cd.service;

import com.easy.cd.dto.ImageVersionDTO;
import com.easy.cd.dto.ServiceCreateDTO;
import com.easy.cd.dto.ServiceUpdateDTO;
import com.easy.cd.vo.ServiceDetailVO;

import java.util.List;

/**
 * 服务管理业务接口
 */
public interface ServiceManagementService {
    
    /**
     * 创建服务（异步：提交到环境队列后立即返回任务ID）
     */
    Long create(ServiceCreateDTO createDTO);
    
    /**
     * 更新服务（异步：返回任务ID）
     */
    Long update(Long id, ServiceUpdateDTO updateDTO);
    
    /**
     * 查询环境下的所有服务
     */
    List<ServiceDetailVO> listByEnvironment(Long environmentId);
    
    /**
     * 根据ID查询服务详情
     */
    ServiceDetailVO getById(Long id);
    
    /**
     * 删除服务（异步：返回任务ID，包括 Docker 服务和数据库记录）
     */
    Long delete(Long id);
    
    /**
     * 重启服务（异步：返回任务ID）
     */
    Long restart(Long id);
    
    /**
     * 停止服务（异步：返回任务ID）
     */
    Long stop(Long id);
    
    /**
     * 回滚服务（异步：返回任务ID）
     */
    Long rollback(Long id, String targetVersion);
    
    /**
     * 调整副本数（异步：返回任务ID）
     */
    Long scale(Long id, Integer replicas);
    
    /**
     * 查看服务副本列表
     */
    List<?> getReplicas(Long id);
    
    /**
     * 获取服务镜像的所有可用版本（从镜像仓库）
     */
    List<ImageVersionDTO> getAvailableVersions(Long serviceId);
}
