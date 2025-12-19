package com.easy.cd.service;

import com.easy.cd.entity.Environment;

import java.util.List;

public interface EnvironmentService {
    
    /**
     * 查询所有环境
     */
    List<Environment> listAll();
    
    /**
     * 根据ID查询环境
     */
    Environment getById(Long id);
    
    /**
     * 新增环境
     */
    Environment add(Environment environment);
    
    /**
     * 更新环境
     */
    Environment update(Environment environment);
    
    /**
     * 删除环境
     */
    boolean delete(Long id);
}
