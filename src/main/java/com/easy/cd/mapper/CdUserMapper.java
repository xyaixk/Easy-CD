package com.easy.cd.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easy.cd.entity.CdUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CdUserMapper extends BaseMapper<CdUser> {
}
