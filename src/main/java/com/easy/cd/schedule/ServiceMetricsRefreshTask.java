package com.easy.cd.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.deploy.DeployService;
import com.easy.cd.dto.ServiceMetricsInfo;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.entity.ServiceMetrics;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.mapper.ServiceMetricsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务监控指标定时刷新任务
 * 每1秒刷新一次所有服务的监控指标
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceMetricsRefreshTask {
    
    private final ServiceMapper serviceMapper;
    private final ServiceMetricsMapper serviceMetricsMapper;
    private final EnvironmentMapper environmentMapper;
    private final DeployService deployService;
    
    @Scheduled(fixedRate = 1000)
    public void refreshAllServiceMetrics() {
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
                    
                    // 调用 DeployService 收集指标
                    List<ServiceMetricsInfo> metricsInfoList = 
                        deployService.collectServiceMetrics(environment.getDeployType(), environment, services);
                    
                    for (ServiceMetricsInfo metricsInfo : metricsInfoList) {
                        updateServiceMetrics(metricsInfo);
                    }
                    
                } catch (Exception e) {
                    log.error("刷新环境[{}]监控指标失败", environment.getName(), e);
                }
            }
        } catch (Exception e) {
            log.error("刷新服务监控指标失败", e);
        }
    }
    
    /**
     * 更新服务监控指标
     */
    private void updateServiceMetrics(ServiceMetricsInfo metricsInfo) {
        try {
            ServiceMetrics metrics = new ServiceMetrics();
            metrics.setServiceId(metricsInfo.getServiceId());
            metrics.setCpuPercent(metricsInfo.getCpuPercent());
            metrics.setMemoryUsage(metricsInfo.getMemoryUsage());
            metrics.setMemoryLimit(metricsInfo.getMemoryLimit());
            metrics.setMemoryPercent(metricsInfo.getMemoryPercent());
            metrics.setNetworkRxRate(metricsInfo.getNetworkRxRate());
            metrics.setNetworkTxRate(metricsInfo.getNetworkTxRate());
            metrics.setDiskReadRate(metricsInfo.getDiskReadRate());
            metrics.setDiskWriteRate(metricsInfo.getDiskWriteRate());
            metrics.setCollectedTime(LocalDateTime.now());
            metrics.setCreatedTime(LocalDateTime.now());
            
            serviceMetricsMapper.insert(metrics);
            
        } catch (Exception e) {
            log.error("保存服务监控指标失败, ServiceId={}", metricsInfo.getServiceId(), e);
        }
    }
}
