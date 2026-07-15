package com.easy.cd.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easy.cd.entity.ReplicaStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 副本状态 Mapper
 */
@Mapper
public interface ReplicaStatusMapper extends BaseMapper<ReplicaStatus> {

    /**
     * 按环境查询 platform=docker 的所有副本记录（用于 replica_metrics 采集关联）
     */
    @Select("SELECT rs.* FROM replica_status rs " +
            "JOIN app_service s ON rs.service_id = s.id " +
            "WHERE s.environment_id = #{envId} AND rs.platform = 'docker'")
    List<ReplicaStatus> selectDockerReplicasByEnvironmentId(@Param("envId") Long envId);

    /**
     * 按节点名查询该节点上的所有副本（宿主机详情 · 节点内容器列表用）。
     * Docker Swarm 下 node_name 与 host_info.hostname 通常一致。
     */
    @Select("SELECT * FROM replica_status WHERE node_name = #{nodeName}")
    List<ReplicaStatus> selectByNodeName(@Param("nodeName") String nodeName);

    /**
     * 按节点分组统计副本数，一次返回全部，避免循环 N+1。
     * 返回 map key = nodeName, value = cnt（Long）。
     */
    @Select("SELECT node_name AS nodeName, COUNT(*) AS cnt FROM replica_status " +
            "WHERE node_name IS NOT NULL AND node_name <> '' GROUP BY node_name")
    List<Map<String, Object>> selectReplicaCountGroupByNode();
}
