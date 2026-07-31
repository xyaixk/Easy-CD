package com.easy.cd.controller;

import com.easy.cd.common.Result;
import com.easy.cd.dto.ServiceGroupCreateDTO;
import com.easy.cd.dto.ServiceGroupUpdateDTO;
import com.easy.cd.dto.ServiceLayoutUpdateDTO;
import com.easy.cd.service.ServiceGroupService;
import com.easy.cd.vo.ServiceGroupVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/service-group")
@RequiredArgsConstructor
public class ServiceGroupController {

    private final ServiceGroupService serviceGroupService;

    @GetMapping("/list")
    public Result<List<ServiceGroupVO>> list(@RequestParam Long environmentId) {
        return Result.success(serviceGroupService.listByEnvironment(environmentId));
    }

    @PostMapping
    public Result<ServiceGroupVO> create(@RequestBody ServiceGroupCreateDTO createDTO) {
        log.info("创建服务分组, environmentId={}, name={}",
                createDTO.getEnvironmentId(), createDTO.getName());
        return Result.success(serviceGroupService.create(createDTO));
    }

    @PutMapping("/{id}")
    public Result<ServiceGroupVO> update(@PathVariable Long id,
                                         @RequestBody ServiceGroupUpdateDTO updateDTO) {
        log.info("重命名服务分组, id={}, name={}", id, updateDTO.getName());
        return Result.success(serviceGroupService.update(id, updateDTO));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("删除服务分组, id={}", id);
        serviceGroupService.delete(id);
        return Result.success();
    }

    @PutMapping("/layout")
    public Result<Void> updateLayout(@RequestBody ServiceLayoutUpdateDTO layoutDTO) {
        log.info("更新服务布局, environmentId={}", layoutDTO.getEnvironmentId());
        serviceGroupService.updateLayout(layoutDTO);
        return Result.success();
    }
}
