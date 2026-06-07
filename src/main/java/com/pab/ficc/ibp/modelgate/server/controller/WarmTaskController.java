package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.dto.CreateTaskRequest;
import com.pab.ficc.ibp.modelgate.server.domain.dto.UpdateTaskRequest;
import com.pab.ficc.ibp.modelgate.server.domain.entity.WarmTask;
import com.pab.ficc.ibp.modelgate.server.domain.vo.LiveStatsVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.WarmTaskVO;
import com.pab.ficc.ibp.modelgate.server.engine.WarmExecutionEngine;
import com.pab.ficc.ibp.modelgate.server.mapper.WarmTaskMapper;
import com.pab.ficc.ibp.modelgate.server.scheduler.DynamicTaskScheduler;
import com.pab.ficc.ibp.modelgate.server.service.WarmTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@Tag(name = "预热任务管理")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class WarmTaskController {

    private final WarmTaskService taskService;
    private final WarmTaskMapper taskMapper;
    private final DynamicTaskScheduler dynamicScheduler;
    private final WarmExecutionEngine executionEngine;

    @Operation(summary = "新增预热任务")
    @PostMapping("/create")
    public Result<Long> create(@Valid @RequestBody CreateTaskRequest req) {
        Long id = taskService.create(req);
        WarmTask task = taskMapper.selectById(id);
        if (task.getEnabled() == 1) {
            dynamicScheduler.schedule(task);
        }
        return Result.ok(id);
    }

    @Operation(summary = "更新预热任务（id 放在请求体中，自动重新调度）")
    @PostMapping("/update")
    public Result<Void> update(@Valid @RequestBody UpdateTaskRequest req) {
        taskService.update(req);
        WarmTask task = taskMapper.selectById(req.getId());
        dynamicScheduler.cancel(req.getId());
        if (task.getEnabled() == 1) {
            dynamicScheduler.schedule(task);
        }
        return Result.ok();
    }

    @Operation(summary = "删除预热任务")
    @PostMapping("/delete")
    public Result<Void> delete(@RequestParam Long id) {
        dynamicScheduler.cancel(id);
        taskService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "查询单个任务")
    @GetMapping("/detail")
    public Result<WarmTaskVO> detail(@RequestParam Long id) {
        return Result.ok(taskService.getById(id));
    }

    @Operation(summary = "查询所有任务")
    @GetMapping("/list")
    public Result<List<WarmTaskVO>> list() {
        return Result.ok(taskService.listAll());
    }

    @Operation(summary = "手动立即触发任务")
    @PostMapping("/trigger")
    public Result<Void> trigger(@RequestParam Long id) {
        WarmTask task = taskMapper.selectById(id);
        if (task == null) throw new BusinessException(404, "任务不存在");
        if (task.getEnabled() != 1) throw new BusinessException("任务已禁用");
        executionEngine.execute(task);
        return Result.ok();
    }

    @Operation(summary = "手动停止运行中的任务")
    @PostMapping("/stop")
    public Result<Void> stop(@RequestParam Long id) {
        executionEngine.stop(id);
        return Result.ok();
    }

    @Operation(summary = "查询任务当前运行状态")
    @GetMapping("/running")
    public Result<Boolean> running(@RequestParam Long id) {
        return Result.ok(executionEngine.isRunning(id));
    }

    @Operation(summary = "查询运行中任务的实时统计数据")
    @GetMapping("/live-stats")
    public Result<LiveStatsVO> liveStats(@RequestParam Long id) {
        return Result.ok(executionEngine.getLiveStats(id));
    }
}
