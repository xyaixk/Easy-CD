package com.easy.cd.service;

import com.easy.cd.dto.ImageVersionDTO;
import com.easy.cd.dto.ServiceCreateDTO;
import com.easy.cd.dto.ServiceUpdateDTO;
import com.easy.cd.vo.ServiceDetailVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 服务管理业务接口
 */
public interface ServiceManagementService {
    
    /**
     * 创建服务
     */
    ServiceDetailVO create(ServiceCreateDTO createDTO);
    
    /**
     * 更新服务
     */
    ServiceDetailVO update(Long id, ServiceUpdateDTO updateDTO);
    
    /**
     * 查询环境下的所有服务
     */
    List<ServiceDetailVO> listByEnvironment(Long environmentId);
    
    /**
     * 根据ID查询服务详情
     */
    ServiceDetailVO getById(Long id);
    
    /**
     * 删除服务（包括 Docker 服务和数据库记录）
     */
    void delete(Long id);
    
    /**
     * 重启服务
     */
    void restart(Long id);
    
    /**
     * 停止服务
     */
    void stop(Long id);
    
    /**
     * 回滚服务
     */
    void rollback(Long id, String targetVersion);
    
    /**
     * 调整副本数
     */
    void scale(Long id, Integer replicas);
    
    /**
     * 查看服务副本列表
     */
    List<?> getReplicas(Long id);
    
    /**
     * 获取服务镜像的所有可用版本（从镜像仓库）
     */
    List<ImageVersionDTO> getAvailableVersions(Long serviceId);
    
    /**
     * 流式推送副本日志（SSE）
     * @param serviceId 服务ID
     * @param replicaId 副本ID（容器ID）
     * @param tail 获取最后N行
     * @param follow 是否持续推送
     * @return SseEmitter
     */
    SseEmitter streamLogs(Long serviceId, String replicaId, Integer tail, Boolean follow);
}
