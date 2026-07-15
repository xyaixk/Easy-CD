package com.easy.cd.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easy.cd.entity.ReplicaMetrics;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 副本监控指标 Mapper
 */
@Mapper
public interface ReplicaMetricsMapper extends BaseMapper<ReplicaMetrics> {

    /**
     * 批量插入：一轮采集 N 个副本，一次 SQL 落库。
     * 极简字段集：归属键 + 4 个核心数值指标 + 时间。
     */
    @Insert("<script>" +
            "INSERT INTO replica_metrics(replica_status_id, service_id, replica_id, replica_name, " +
            "platform, node_name, status, cpu_percent, memory_usage, memory_limit, memory_percent, " +
            "collected_time) VALUES " +
            "<foreach collection='list' item='m' separator=','>" +
            "(#{m.replicaStatusId}, #{m.serviceId}, #{m.replicaId}, #{m.replicaName}, " +
            "#{m.platform}, #{m.nodeName}, #{m.status}, #{m.cpuPercent}, #{m.memoryUsage}, " +
            "#{m.memoryLimit}, #{m.memoryPercent}, #{m.collectedTime})" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("list") List<ReplicaMetrics> list);

    /**
     * 查询时间区间内的 replica_metrics（降采样时使用），区间 [from, to) 左闭右开
     */
    @Select("SELECT * FROM replica_metrics WHERE collected_time >= #{from} AND collected_time < #{to}")
    List<ReplicaMetrics> selectByTimeRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 删除时间区间内的 replica_metrics（降采样后删原、或超时物理删除）
     */
    @Delete("DELETE FROM replica_metrics WHERE collected_time >= #{from} AND collected_time < #{to}")
    int deleteByTimeRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 删除早于指定时间的 replica_metrics
     */
    @Delete("DELETE FROM replica_metrics WHERE collected_time < #{cutoff}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 拉取指定服务、指定时间区间内的所有副本采样（升序）——用于 sparkline 时间桶聚合。
     */
    @Select("SELECT * FROM replica_metrics WHERE service_id = #{serviceId} " +
            "AND collected_time >= #{from} ORDER BY collected_time ASC")
    List<ReplicaMetrics> selectRecentByServiceId(@Param("serviceId") Long serviceId,
                                                  @Param("from") LocalDateTime from);

    /** 一次查询多个服务的时间窗采样，避免首页按服务 N 次查库。 */
    @Select("<script>" +
            "SELECT * FROM replica_metrics WHERE service_id IN " +
            "<foreach collection='serviceIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "AND collected_time &gt;= #{from} ORDER BY service_id, collected_time ASC" +
            "</script>")
    List<ReplicaMetrics> selectRecentByServiceIds(@Param("serviceIds") List<Long> serviceIds,
                                                   @Param("from") LocalDateTime from);

    /**
     * 拉取指定副本、指定时间区间内的采样（升序）——用于副本级 sparkline。
     */
    @Select("SELECT * FROM replica_metrics WHERE replica_id = #{replicaId} " +
            "AND collected_time >= #{from} ORDER BY collected_time ASC")
    List<ReplicaMetrics> selectRecentByReplicaId(@Param("replicaId") String replicaId,
                                                  @Param("from") LocalDateTime from);

    /** 一次查询多个副本的时间窗采样，避免副本弹窗按副本 N 次查库。 */
    @Select("<script>" +
            "SELECT * FROM replica_metrics WHERE replica_id IN " +
            "<foreach collection='replicaIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "AND collected_time &gt;= #{from} ORDER BY replica_id, collected_time ASC" +
            "</script>")
    List<ReplicaMetrics> selectRecentByReplicaIds(@Param("replicaIds") List<String> replicaIds,
                                                   @Param("from") LocalDateTime from);

    /**
     * 拉取指定节点上所有副本的最新一行指标。
     * 先在时间窗内 GROUP BY replica_id 取 MAX(collected_time)，再 JOIN 回表，
     * 避免相关子查询在高频时序表上扫全量。
     */
    @Select("SELECT rm.* FROM replica_metrics rm " +
            "INNER JOIN (" +
            "  SELECT replica_id, MAX(collected_time) AS mt FROM replica_metrics " +
            "  WHERE node_name = #{nodeName} AND collected_time >= #{cutoff} " +
            "  GROUP BY replica_id" +
            ") t ON rm.replica_id = t.replica_id AND rm.collected_time = t.mt " +
            "WHERE rm.node_name = #{nodeName} AND rm.collected_time >= #{cutoff}")
    List<ReplicaMetrics> selectLatestPerReplicaByNode(@Param("nodeName") String nodeName,
                                                      @Param("cutoff") LocalDateTime cutoff);
}
