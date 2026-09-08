package com.easy.cd.schedule;

import com.easy.cd.service.ReplicaStatusSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 默认每 10 秒同步一次副本状态信息。
 */
@Component
@ConditionalOnProperty(prefix = "monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReplicaStatusRefreshTask {

    private final ReplicaStatusSyncService replicaStatusSyncService;

    @Scheduled(fixedRateString = "${monitor.collector.interval-ms:10000}", initialDelay = 5000)
    public void refreshReplicaStatus() {
        replicaStatusSyncService.refreshAllReplicaStatus();
    }
}
