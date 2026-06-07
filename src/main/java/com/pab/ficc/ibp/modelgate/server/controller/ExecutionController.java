package com.pab.ficc.ibp.modelgate.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.entity.TaskExecution;
import com.pab.ficc.ibp.modelgate.server.domain.entity.WarmTask;
import com.pab.ficc.ibp.modelgate.server.domain.vo.TaskExecutionVO;
import com.pab.ficc.ibp.modelgate.server.mapper.TaskExecutionMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.WarmTaskMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "执行历史")
@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final TaskExecutionMapper executionMapper;
    private final WarmTaskMapper taskMapper;

    @Operation(summary = "查询任务执行历史（按任务 ID 筛选）")
    @GetMapping("/list")
    public Result<List<TaskExecutionVO>> list(@RequestParam(required = false) Long taskId,
                                              @RequestParam(defaultValue = "50") int limit) {
        LambdaQueryWrapper<TaskExecution> wrapper = new LambdaQueryWrapper<TaskExecution>()
                .orderByDesc(TaskExecution::getStartTime)
                .last("LIMIT " + Math.min(limit, 500));
        if (taskId != null) {
            wrapper.eq(TaskExecution::getTaskId, taskId);
        }
        List<TaskExecution> executions = executionMapper.selectList(wrapper);
        if (executions.isEmpty()) return Result.ok(List.of());

        List<Long> taskIds = executions.stream().map(TaskExecution::getTaskId).distinct().collect(Collectors.toList());
        Map<Long, WarmTask> taskMap = taskMapper.selectBatchIds(taskIds)
                .stream().collect(Collectors.toMap(WarmTask::getId, Function.identity()));

        List<TaskExecutionVO> vos = executions.stream().map(e -> {
            TaskExecutionVO vo = new TaskExecutionVO();
            vo.setId(e.getId());
            vo.setTaskId(e.getTaskId());
            WarmTask task = taskMap.get(e.getTaskId());
            vo.setTaskName(task != null ? task.getName() : null);
            vo.setStartTime(e.getStartTime());
            vo.setEndTime(e.getEndTime());
            vo.setTotalRequests(e.getTotalRequests());
            vo.setSuccessCount(e.getSuccessCount());
            vo.setFailCount(e.getFailCount());
            vo.setTotalTokens(e.getTotalTokens());
            vo.setStatus(e.getStatus());
            vo.setErrorMsg(e.getErrorMsg());
            return vo;
        }).collect(Collectors.toList());

        return Result.ok(vos);
    }
}
