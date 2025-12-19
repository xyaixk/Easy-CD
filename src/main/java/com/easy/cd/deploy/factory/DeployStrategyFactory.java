package com.easy.cd.deploy.factory;

import com.easy.cd.deploy.DeployStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 部署策略工厂
 * 负责管理和获取不同的部署策略实例
 */
@Component
public class DeployStrategyFactory {
    
    private final Map<String, DeployStrategy> strategyMap = new ConcurrentHashMap<>();
    
    /**
     * 构造函数注入所有部署策略实现
     */
    public DeployStrategyFactory(List<DeployStrategy> strategies) {
        for (DeployStrategy strategy : strategies) {
            strategyMap.put(strategy.getDeployType().toLowerCase(), strategy);
        }
    }
    
    /**
     * 根据部署类型获取对应的策略
     * @param deployType 部署类型 (docker/jar/kubernetes)
     * @return 部署策略实例
     */
    public DeployStrategy getStrategy(String deployType) {
        DeployStrategy strategy = strategyMap.get(deployType.toLowerCase());
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的部署类型: " + deployType);
        }
        return strategy;
    }
    
    /**
     * 检查是否支持该部署类型
     * @param deployType 部署类型
     * @return 是否支持
     */
    public boolean isSupported(String deployType) {
        return strategyMap.containsKey(deployType.toLowerCase());
    }
}
