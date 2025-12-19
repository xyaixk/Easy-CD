package com.easy.cd.controller;

import com.easy.cd.common.Result;
import com.easy.cd.entity.Environment;
import com.easy.cd.service.EnvironmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/environment")
@RequiredArgsConstructor
public class EnvironmentController {
    
    private final EnvironmentService environmentService;
    
    /**
     * 查询所有环境
     */
    @GetMapping("/list")
    public Result<List<Environment>> list() {
        List<Environment> list = environmentService.listAll();
        return Result.success(list);
    }
    
    /**
     * 根据ID查询环境
     */
    @GetMapping("/{id}")
    public Result<Environment> getById(@PathVariable Long id) {
        Environment environment = environmentService.getById(id);
        return Result.success(environment);
    }
    
    /**
     * 新增环境
     */
    @PostMapping
    public Result<Environment> add(@RequestBody Environment environment) {
        Environment result = environmentService.add(environment);
        return Result.success(result);
    }
    
    /**
     * 更新环境
     */
    @PutMapping("/{id}")
    public Result<Environment> update(@PathVariable Long id, @RequestBody Environment environment) {
        environment.setId(id);
        Environment result = environmentService.update(environment);
        return Result.success(result);
    }
    
    /**
     * 删除环境
     */
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        boolean result = environmentService.delete(id);
        return Result.success(result);
    }
}
