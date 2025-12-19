package com.easy.cd.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easy.cd.entity.ReplicaMetrics;
import org.apache.ibatis.annotations.Mapper;

/**
 * 副本监控指标 Mapper
 */
@Mapper
public interface ReplicaMetricsMapper extends BaseMapper<ReplicaMetrics> {
}
