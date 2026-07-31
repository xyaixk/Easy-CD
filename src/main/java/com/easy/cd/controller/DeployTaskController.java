package com.easy.cd.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.common.Result;
import com.easy.cd.entity.DeployTask;
import com.easy.cd.exception.BusinessException;
import com.easy.cd.mapper.DeployTaskMapper;
import com.easy.cd.vo.DeployTaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 部署任务查询Controller
 */
@Slf4j
@RestController
@RequestMapping("/task")
@RequiredArgsConstructor
public class DeployTaskController {

    private final DeployTaskMapper deployTaskMapper;

    /**
     * 查询环境下的任务列表（按创建时间倒序，日志截断为预览）
     * beforeId：游标翻页，只取 id 小于该值的更早任务（轮询首页时不传）
     */
    @GetMapping("/list")
    public Result<List<DeployTaskVO>> list(@RequestParam Long environmentId,
                                           @RequestParam(defaultValue = "50") Integer limit,
                                           @RequestParam(required = false) Long beforeId) {
        LambdaQueryWrapper<DeployTask> wrapper = new LambdaQueryWrapper<DeployTask>()
                .eq(DeployTask::getEnvironmentId, environmentId)
                .lt(beforeId != null, DeployTask::getId, beforeId)
                .orderByDesc(DeployTask::getCreatedTime)
                .orderByDesc(DeployTask::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        List<DeployTaskVO> tasks = deployTaskMapper.selectList(wrapper).stream()
                .map(task -> DeployTaskVO.from(task, false))
                .collect(Collectors.toList());
        return Result.success(tasks);
    }

    /**
     * 查询任务详情（完整命令日志）
     */
    @GetMapping("/{id}")
    public Result<DeployTaskVO> getById(@PathVariable Long id) {
        DeployTask task = deployTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException("任务不存在: " + id);
        }
        return Result.success(DeployTaskVO.from(task, true));
    }
}
