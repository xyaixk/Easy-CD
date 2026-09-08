package com.easy.cd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.deploy.DeployService;
import com.easy.cd.deploy.model.DeployResult;
import com.easy.cd.dto.ServiceStatusInfo;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.entity.ServiceStatus;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.mapper.ServiceStatusMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 将部署平台的实际运行状态同步到 service_status。
 * 定时采集和部署任务完成回调共用此服务，避免两套状态写入规则产生偏差。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceStatusSyncService {

    private static final String TASK_STOP = "STOP";
    private static final String TASK_DELETE = "DELETE";

    private final ServiceMapper serviceMapper;
    private final ServiceStatusMapper serviceStatusMapper;
    private final EnvironmentMapper environmentMapper;
    private final DeployService deployService;

    /**
     * 部署任务结束后的定向同步。任务失败时也读取实际运行态，避免把任务失败误当成服务失败。
     */
    public void refreshAfterTask(String taskType, Long serviceId, boolean taskSucceeded) {
        if (serviceId == null) {
            return;
        }
        if (taskSucceeded && TASK_DELETE.equalsIgnoreCase(taskType)) {
            return;
        }
        if (taskSucceeded && TASK_STOP.equalsIgnoreCase(taskType)) {
            markStopped(serviceId);
            return;
        }
        refreshService(serviceId);
    }

    /**
     * 读取并持久化单个服务的真实运行状态。
     *
     * @return 状态是否成功读取并处理
     */
    public boolean refreshService(Long serviceId) {
        if (serviceId == null) {
            return false;
        }
        try {
            AppService service = serviceMapper.selectById(serviceId);
            if (service == null) {
                log.debug("跳过状态同步，服务不存在: serviceId={}", serviceId);
                return false;
            }

            Environment environment = environmentMapper.selectById(service.getEnvironmentId());
            if (environment == null) {
                log.warn("跳过状态同步，环境不存在: serviceId={}, environmentId={}",
                        serviceId, service.getEnvironmentId());
                return false;
            }

            DeployResult result = deployService.getStatus(environment.getDeployType(), serviceId);
            if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || result.getStatus() == null) {
                String message = result == null ? "未返回状态" : result.getMessage();
                log.warn("读取服务实际状态失败，保留原状态: serviceId={}, message={}", serviceId, message);
                return false;
            }

            upsertStatus(ServiceStatusInfo.builder()
                    .serviceId(serviceId)
                    .serviceName(service.getName())
                    .status(result.getStatus())
                    .healthyInstances(orZero(result.getHealthyInstances()))
                    .instances(orZero(result.getInstances()))
                    .desiredInstances(result.getDesiredInstances() != null
                            ? result.getDesiredInstances()
                            : configuredDesiredInstances(service))
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("同步服务实际状态失败，保留原状态: serviceId={}", serviceId, e);
            return false;
        }
    }

    /** 成功停止会删除 Swarm Service，因此无需再查询已不存在的远端对象。 */
    public boolean markStopped(Long serviceId) {
        if (serviceId == null) {
            return false;
        }
        try {
            AppService service = serviceMapper.selectById(serviceId);
            if (service == null) {
                return false;
            }
            upsertStatus(ServiceStatusInfo.builder()
                    .serviceId(serviceId)
                    .serviceName(service.getName())
                    .status("stopped")
                    .healthyInstances(0)
                    .instances(0)
                    .desiredInstances(configuredDesiredInstances(service))
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("写入服务停止状态失败，保留原状态: serviceId={}", serviceId, e);
            return false;
        }
    }

    /** 保留原有的环境级批量查询，供定时刷新调用。 */
    public void refreshAllServiceStatus() {
        try {
            List<Environment> environments = environmentMapper.selectList(null);
            for (Environment environment : environments) {
                refreshEnvironment(environment);
            }
        } catch (Exception e) {
            log.error("刷新服务状态失败", e);
        }
    }

    private void refreshEnvironment(Environment environment) {
        try {
            List<AppService> services = serviceMapper.selectList(
                    new LambdaQueryWrapper<AppService>()
                            .eq(AppService::getEnvironmentId, environment.getId()));
            if (services.isEmpty()) {
                return;
            }

            List<ServiceStatusInfo> statuses = deployService.collectServiceStatus(
                    environment.getDeployType(), environment, services);
            for (ServiceStatusInfo status : statuses) {
                try {
                    upsertStatus(status);
                } catch (Exception e) {
                    log.error("更新服务状态失败, serviceId={}", status.getServiceId(), e);
                }
            }
        } catch (Exception e) {
            log.error("刷新环境[{}]服务状态失败", environment.getName(), e);
        }
    }

    private void upsertStatus(ServiceStatusInfo statusInfo) {
        ServiceStatus existing = serviceStatusMapper.selectOne(
                new LambdaQueryWrapper<ServiceStatus>()
                        .eq(ServiceStatus::getServiceId, statusInfo.getServiceId()));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            ServiceStatus created = new ServiceStatus();
            created.setServiceId(statusInfo.getServiceId());
            applySnapshot(created, statusInfo);
            created.setCreatedTime(now);
            created.setUpdatedTime(now);
            serviceStatusMapper.insert(created);
            return;
        }

        if (!hasStatusChanged(existing, statusInfo)) {
            return;
        }
        applySnapshot(existing, statusInfo);
        existing.setUpdatedTime(now);
        serviceStatusMapper.updateById(existing);
    }

    private void applySnapshot(ServiceStatus target, ServiceStatusInfo source) {
        target.setStatus(source.getStatus());
        target.setHealthyInstances(orZero(source.getHealthyInstances()));
        target.setInstances(orZero(source.getInstances()));
        target.setDesiredInstances(orZero(source.getDesiredInstances()));
    }

    private boolean hasStatusChanged(ServiceStatus current, ServiceStatusInfo incoming) {
        return !Objects.equals(current.getStatus(), incoming.getStatus())
                || !Objects.equals(current.getHealthyInstances(), orZero(incoming.getHealthyInstances()))
                || !Objects.equals(current.getInstances(), orZero(incoming.getInstances()))
                || !Objects.equals(current.getDesiredInstances(), orZero(incoming.getDesiredInstances()));
    }

    private int configuredDesiredInstances(AppService service) {
        return service.getReplicas() != null ? service.getReplicas() : 1;
    }

    private int orZero(Integer value) {
        return value != null ? value : 0;
    }
}
