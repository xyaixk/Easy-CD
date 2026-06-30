package com.easy.cd.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.auth.AuthContext;
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
        LambdaQueryWrapper<Environment> queryWrapper = new LambdaQueryWrapper<>();
        if (AuthContext.getCurrentUser() == null) {
            queryWrapper.ne(Environment::getNeedLogin, true)
                .or()
                .isNull(Environment::getNeedLogin);
        }
        queryWrapper.orderByAsc(Environment::getId);
        return environmentMapper.selectList(queryWrapper);
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
        validateEnvironmentNameUnique(null, environment.getName());
        validateEnvironmentConfig(environment);
        environment.setNeedLogin(Boolean.TRUE.equals(environment.getNeedLogin()));
        environment.setCreatedTime(LocalDateTime.now());
        environmentMapper.insert(environment);
        log.info("环境创建成功: ID={}, Name={}", environment.getId(), environment.getName());
        return environment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Environment update(Environment environment) {
        log.info("更新环境: ID={}, Name={}", environment.getId(), environment.getName());
        Environment existing = getById(environment.getId());
        if (!existing.getName().equals(environment.getName())) {
            validateEnvironmentNameUnique(environment.getId(), environment.getName());
        }
        validateEnvironmentConfig(environment);
        if (environment.getNeedLogin() == null) {
            environment.setNeedLogin(existing.getNeedLogin());
        }
        environmentMapper.updateById(environment);
        log.info("环境更新成功: {}", environment.getName());
        return environmentMapper.selectById(environment.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id) {
        log.info("删除环境: ID={}", id);
        Environment environment = getById(id);
        int deleted = environmentMapper.deleteById(id);
        if (deleted > 0) {
            log.info("环境删除成功: {}", environment.getName());
        }
        return deleted > 0;
    }

    private void validateEnvironmentNameUnique(Long excludeId, String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException("环境名称不能为空");
        }

        LambdaQueryWrapper<Environment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Environment::getName, name);
        if (excludeId != null) {
            queryWrapper.ne(Environment::getId, excludeId);
        }

        Long count = environmentMapper.selectCount(queryWrapper);
        if (count > 0) {
            throw new BusinessException("环境名称已存在: " + name);
        }
    }

    private void validateEnvironmentConfig(Environment environment) {
        if (environment.getDeployType() == null || environment.getDeployType().trim().isEmpty()) {
            throw new BusinessException("部署类型不能为空");
        }
        if (!"docker".equals(environment.getDeployType()) && !"kubernetes".equals(environment.getDeployType())) {
            throw new BusinessException("不支持的部署类型: " + environment.getDeployType());
        }
    }
}
