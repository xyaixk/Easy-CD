package com.easy.cd.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easy.cd.monitor.entity.HostInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HostInfoMapper extends BaseMapper<HostInfo> {

    /**
     * 拉取一个环境下所有 host_info（供采集器建立 hostKey -> hostId 映射）
     */
    @Select("SELECT * FROM host_info WHERE environment_id = #{environmentId}")
    List<HostInfo> selectByEnvironmentId(@Param("environmentId") Long environmentId);
}
