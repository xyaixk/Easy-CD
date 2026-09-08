package com.easy.cd.schedule;

import com.easy.cd.service.ServiceStatusSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 服务状态定时刷新任务
 * 默认每 10 秒刷新一次所有服务的运行状态
 */
@Component
@ConditionalOnProperty(prefix = "monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ServiceStatusRefreshTask {

    private final ServiceStatusSyncService serviceStatusSyncService;

    @Scheduled(fixedRateString = "${monitor.collector.interval-ms:10000}", initialDelay = 3000)
    public void refreshAllServiceStatus() {
        serviceStatusSyncService.refreshAllServiceStatus();
    }
}
