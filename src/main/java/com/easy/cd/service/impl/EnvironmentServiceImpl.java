package com.easy.cd.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.entity.Environment;
import com.easy.cd.exception.BusinessException;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.service.EnvironmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnvironmentServiceImpl implements EnvironmentService {
    
    private final EnvironmentMapper environmentMapper;
    
    @Override
    public List<Environment> listAll() {
        return environmentMapper.selectList(null);
    }
    
    @Override
    public Environment getById(Long id) {
        Environment environment = environmentMapper.selectById(id);
        if (environment == null) {
            throw new BusinessException("环境不存在");
        }
        return environment;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Environment add(Environment environment) {
        log.info("添加环境: {}", environment.getName());
        
        // 校验环境名唯一性
        validateEnvironmentNameUnique(null, environment.getName());
        
        // 校验配置
        validateEnvironmentConfig(environment);
        
        // 设置创建时间
        environment.setCreatedTime(LocalDateTime.now());
        
        // 保存
        environmentMapper.insert(environment);
        log.info("环境创建成功: ID={}, Name={}", environment.getId(), environment.getName());
        
        return environment;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Environment update(Environment environment) {
        log.info("更新环境: ID={}, Name={}", environment.getId(), environment.getName());
        
        // 校验环境是否存在
        Environment existing = getById(environment.getId());
        
        // 校验环境名唯一性（如果修改了名称）
        if (!existing.getName().equals(environment.getName())) {
            validateEnvironmentNameUnique(environment.getId(), environment.getName());
        }
        
        // 校验配置
        validateEnvironmentConfig(environment);
        
        // 更新
        environmentMapper.updateById(environment);
        log.info("环境更新成功: {}", environment.getName());
        
        return environment;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id) {
        log.info("删除环境: ID={}", id);
        
        // 校验环境是否存在
        Environment environment = getById(id);
        
        // TODO: 校验环境下是否还有服务，如果有则不允许删除
        // 这里需要查询 service 表
        
        int deleted = environmentMapper.deleteById(id);
        if (deleted > 0) {
            log.info("环境删除成功: {}", environment.getName());
        }
        return deleted > 0;
    }
    
    /**
     * 校验环境名唯一性
     */
    private void validateEnvironmentNameUnique(Long excludeId, String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException("环境名称不能为空");
        }
        
        LambdaQueryWrapper<Environment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Environment::getName, name);
        
        // 排除当前环境（更新时）
        if (excludeId != null) {
            queryWrapper.ne(Environment::getId, excludeId);
        }
        
        Long count = environmentMapper.selectCount(queryWrapper);
        if (count > 0) {
            throw new BusinessException("环境名称已存在: " + name);
        }
    }
    
    /**
     * 校验环境配置
     */
    private void validateEnvironmentConfig(Environment environment) {
        if (environment.getDeployType() == null || environment.getDeployType().trim().isEmpty()) {
            throw new BusinessException("部署类型不能为空");
        }
        
        // 校验部署类型是否支持
        if (!"docker".equals(environment.getDeployType()) && !"kubernetes".equals(environment.getDeployType())) {
            throw new BusinessException("不支持的部署类型: " + environment.getDeployType());
        }
        
        // TODO: 根据 deployType 校验 config 字段的内容是否合法
    }
}
