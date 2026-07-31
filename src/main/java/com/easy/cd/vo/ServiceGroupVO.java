package com.easy.cd.vo;

import com.easy.cd.entity.ServiceGroup;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ServiceGroupVO {

    private Long id;

    private Long environmentId;

    private String name;

    private Integer sortOrder;

    public static ServiceGroupVO from(ServiceGroup group) {
        return ServiceGroupVO.builder()
                .id(group.getId())
                .environmentId(group.getEnvironmentId())
                .name(group.getName())
                .sortOrder(group.getSortOrder())
                .build();
    }
}
