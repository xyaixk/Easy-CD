package com.easy.cd.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.deploy.DeployService;
import com.easy.cd.dto.ServiceStatusInfo;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.entity.ServiceStatus;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.mapper.ServiceStatusMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务状态定时刷新任务
 * 每3秒刷新一次所有服务的运行状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceStatusRefreshTask {

    private final ServiceMapper serviceMapper;
    private final ServiceStatusMapper serviceStatusMapper;
    private final EnvironmentMapper environmentMapper;
    private final DeployService deployService;

    @Scheduled(fixedRate = 3000)
    public void refreshAllServiceStatus() {
        try {
            List<Environment> environments = environmentMapper.selectList(null);

            for (Environment environment : environments) {
                try {
                    LambdaQueryWrapper<AppService> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(AppService::getEnvironmentId, environment.getId());
                    List<AppService> services = serviceMapper.selectList(wrapper);

                    if (services.isEmpty()) {
                        continue;
                    }

                    // 调用 DeployService 收集状态
                    List<ServiceStatusInfo> statusInfoList = deployService.collectServiceStatus(environment.getDeployType(), environment, services);

                    for (ServiceStatusInfo statusInfo : statusInfoList) {
                        updateServiceStatus(statusInfo);
                    }

                } catch (Exception e) {
                    log.error("刷新环境[{}]服务状态失败", environment.getName(), e);
                }
            }
        } catch (Exception e) {
            log.error("刷新服务状态失败", e);
        }
    }

    /**
     * 更新单个服务的状态到数据库
     */
    private void updateServiceStatus(ServiceStatusInfo statusInfo) {
        try {
            // 查询现有状态记录
            LambdaQueryWrapper<ServiceStatus> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ServiceStatus::getServiceId, statusInfo.getServiceId());
            ServiceStatus existingStatus = serviceStatusMapper.selectOne(wrapper);

            if (existingStatus == null) {
                // 如果状态记录不存在，创建新记录
                ServiceStatus newStatus = new ServiceStatus();
                newStatus.setServiceId(statusInfo.getServiceId());
                newStatus.setStatus(statusInfo.getStatus());
                newStatus.setHealthyInstances(statusInfo.getHealthyInstances());
                newStatus.setInstances(statusInfo.getInstances());
                newStatus.setDesiredInstances(statusInfo.getDesiredInstances());
                newStatus.setCreatedTime(LocalDateTime.now());
                newStatus.setUpdatedTime(LocalDateTime.now());
                serviceStatusMapper.insert(newStatus);
            } else {
                // 只有状态真正发生变化时才更新
                if (hasStatusChanged(existingStatus, statusInfo)) {
                    existingStatus.setStatus(statusInfo.getStatus());
                    existingStatus.setHealthyInstances(statusInfo.getHealthyInstances());
                    existingStatus.setInstances(statusInfo.getInstances());
                    existingStatus.setDesiredInstances(statusInfo.getDesiredInstances());
                    existingStatus.setUpdatedTime(LocalDateTime.now());
                    serviceStatusMapper.updateById(existingStatus);

                    log.debug("服务状态已更新: ServiceId={}, Status={}, Instances={}/{}/{}",
                            statusInfo.getServiceId(), statusInfo.getStatus(),
                            statusInfo.getHealthyInstances(), statusInfo.getInstances(),
                            statusInfo.getDesiredInstances());
                }
            }
        } catch (Exception e) {
            log.error("更新服务状态失败, ServiceId={}", statusInfo.getServiceId(), e);
        }
    }

    /**
     * 检查状态是否发生变化
     */
    private boolean hasStatusChanged(ServiceStatus oldStatus, ServiceStatusInfo newStatus) {
        return !oldStatus.getStatus().equals(newStatus.getStatus()) ||
                !oldStatus.getHealthyInstances().equals(newStatus.getHealthyInstances()) ||
                !oldStatus.getInstances().equals(newStatus.getInstances()) ||
                !oldStatus.getDesiredInstances().equals(newStatus.getDesiredInstances());
    }
}
