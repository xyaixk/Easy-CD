package com.easy.cd.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easy.cd.monitor.entity.HostMetrics;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface HostMetricsMapper extends BaseMapper<HostMetrics> {

    /**
     * 批量插入：一轮采集 N 个节点，一次 SQL 落库
     */
    @Insert("<script>" +
            "INSERT INTO host_metrics(host_id, environment_id, cpu_percent, load1, load5, load15, " +
            "mem_used, mem_total, mem_percent, disk_used, disk_total, disk_percent, collected_time) VALUES " +
            "<foreach collection='list' item='m' separator=','>" +
            "(#{m.hostId}, #{m.environmentId}, #{m.cpuPercent}, #{m.load1}, #{m.load5}, #{m.load15}, " +
            "#{m.memUsed}, #{m.memTotal}, #{m.memPercent}, #{m.diskUsed}, #{m.diskTotal}, #{m.diskPercent}, " +
            "#{m.collectedTime})" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("list") List<HostMetrics> list);

    /**
     * 查询时间区间内的 host_metrics（降采样时使用），区间 [from, to) 左闭右开
     */
    @Select("SELECT * FROM host_metrics WHERE collected_time >= #{from} AND collected_time < #{to}")
    List<HostMetrics> selectByTimeRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 删除时间区间内的 host_metrics（降采样后删原、或超时物理删除）
     */
    @Delete("DELETE FROM host_metrics WHERE collected_time >= #{from} AND collected_time < #{to}")
    int deleteByTimeRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 删除早于指定时间的 host_metrics
     */
    @Delete("DELETE FROM host_metrics WHERE collected_time < #{cutoff}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 查询指定宿主机、指定时间区间内的采样（升序）——用于大图降采样。
     * 区间 [from, to) 左闭右开。
     */
    @Select("SELECT * FROM host_metrics WHERE host_id = #{hostId} " +
            "AND collected_time >= #{from} AND collected_time < #{to} " +
            "ORDER BY collected_time ASC")
    List<HostMetrics> selectByHostAndTimeRange(@Param("hostId") Long hostId,
                                               @Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to);

    /**
     * 拉取一个环境下每台 host 的最新一行指标。
     * 先在 5 min 时间窗内 GROUP BY host_id 拿 MAX(collected_time)，
     * 再 JOIN 回 host_metrics 取整行，避免相关子查询扫 1s 时序大表。
     * 要求命中 idx_env_collected(environment_id, collected_time) 与 idx_host_collected(host_id, collected_time)。
     */
    @Select("SELECT hm.* FROM host_metrics hm " +
            "INNER JOIN (" +
            "  SELECT host_id, MAX(collected_time) AS mt FROM host_metrics " +
            "  WHERE environment_id = #{environmentId} AND collected_time >= #{cutoff} " +
            "  GROUP BY host_id" +
            ") t ON hm.host_id = t.host_id AND hm.collected_time = t.mt " +
            "WHERE hm.environment_id = #{environmentId} AND hm.collected_time >= #{cutoff}")
    List<HostMetrics> selectLatestPerHost(@Param("environmentId") Long environmentId,
                                          @Param("cutoff") LocalDateTime cutoff);

    /**
     * 单台 host 最新一行（详情弹窗头部用），限时间窗避免全量排序。
     */
    @Select("SELECT * FROM host_metrics WHERE host_id = #{hostId} " +
            "AND collected_time >= #{cutoff} " +
            "ORDER BY collected_time DESC LIMIT 1")
    HostMetrics selectLatestByHostId(@Param("hostId") Long hostId,
                                     @Param("cutoff") LocalDateTime cutoff);
}
