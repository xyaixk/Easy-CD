package com.easy.cd.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easy.cd.entity.AppService;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ServiceMapper extends BaseMapper<AppService> {
}
