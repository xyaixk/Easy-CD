package com.easy.cd.dto;

import lombok.Data;

import java.util.List;

@Data
public class ServiceLayoutUpdateDTO {

    private Long environmentId;

    /**
     * 命名分组 ID，数组顺序即显示顺序；不包含虚拟的“未分组”。
     */
    private List<Long> groupIds;

    /**
     * 每个分组的服务顺序；groupId=null 表示“未分组”。
     */
    private List<ServiceBucket> buckets;

    @Data
    public static class ServiceBucket {

        private Long groupId;

        private List<Long> serviceIds;
    }
}
