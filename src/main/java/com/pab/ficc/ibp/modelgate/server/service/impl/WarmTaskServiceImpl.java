package com.pab.ficc.ibp.modelgate.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.domain.dto.CreateTaskRequest;
import com.pab.ficc.ibp.modelgate.server.domain.dto.UpdateTaskRequest;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelEndpoint;
import com.pab.ficc.ibp.modelgate.server.domain.entity.WarmTask;
import com.pab.ficc.ibp.modelgate.server.domain.vo.WarmTaskVO;
import com.pab.ficc.ibp.modelgate.server.mapper.ModelEndpointMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.WarmTaskMapper;
import com.pab.ficc.ibp.modelgate.server.service.WarmTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WarmTaskServiceImpl implements WarmTaskService {

    private final WarmTaskMapper taskMapper;
    private final ModelEndpointMapper endpointMapper;

    @Override
    public Long create(CreateTaskRequest req) {
        // 创建前校验端点是否存在
        if (endpointMapper.selectById(req.getEndpointId()) == null) {
            throw new BusinessException("端点不存在: " + req.getEndpointId());
        }
        WarmTask task = new WarmTask();
        task.setName(req.getName());
        task.setEndpointId(req.getEndpointId());
        task.setCronExpr(req.getCronExpr());
        task.setTps(req.getTps());
        task.setThreadCount(req.getThreadCount());
        task.setDurationSeconds(req.getDurationSeconds());
        task.setRequestBody(req.getRequestBody());
        task.setEnabled(1);     // 新建任务默认启用
        task.setStatus("IDLE"); // 初始状态为空闲
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
        return task.getId();
    }

    @Override
    public void update(UpdateTaskRequest req) {
        WarmTask task = requireTask(req.getId());
        // 运行中的任务禁止修改，防止配置与实际执行不一致
        if ("RUNNING".equals(task.getStatus())) {
            throw new BusinessException("任务运行中，不可修改");
        }
        // 只更新请求中非 null 的字段（局部更新语义）
        if (req.getName() != null)            task.setName(req.getName());
        if (req.getEndpointId() != null)      task.setEndpointId(req.getEndpointId());
        if (req.getCronExpr() != null)        task.setCronExpr(req.getCronExpr());
        if (req.getTps() != null)             task.setTps(req.getTps());
        if (req.getThreadCount() != null)     task.setThreadCount(req.getThreadCount());
        if (req.getDurationSeconds() != null) task.setDurationSeconds(req.getDurationSeconds());
        if (req.getRequestBody() != null)     task.setRequestBody(req.getRequestBody());
        if (req.getEnabled() != null)         task.setEnabled(req.getEnabled());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    @Override
    public void delete(Long id) {
        WarmTask task = requireTask(id);
        // 运行中的任务禁止删除，需先手动停止
        if ("RUNNING".equals(task.getStatus())) {
            throw new BusinessException("任务运行中，请先停止后再删除");
        }
        taskMapper.deleteById(id);
    }

    @Override
    public WarmTaskVO getById(Long id) {
        WarmTask task = requireTask(id);
        ModelEndpoint endpoint = endpointMapper.selectById(task.getEndpointId());
        return toVO(task, endpoint);
    }

    @Override
    public List<WarmTaskVO> listAll() {
        List<WarmTask> tasks = taskMapper.selectList(null);
        if (tasks.isEmpty()) return List.of();

        // 批量查询关联端点，避免 N+1 查询
        List<Long> endpointIds = tasks.stream()
                .map(WarmTask::getEndpointId).distinct().collect(Collectors.toList());
        Map<Long, ModelEndpoint> endpointMap = endpointMapper.selectBatchIds(endpointIds)
                .stream().collect(Collectors.toMap(ModelEndpoint::getId, Function.identity()));

        return tasks.stream()
                .map(t -> toVO(t, endpointMap.get(t.getEndpointId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<WarmTask> listEnabledEntities() {
        // 仅查询 enabled=1 的任务，用于启动时加载调度
        return taskMapper.selectList(
                new LambdaQueryWrapper<WarmTask>().eq(WarmTask::getEnabled, 1));
    }

    @Override
    public void updateStatus(Long id, String status) {
        // 仅更新状态字段，使用条件更新避免影响其他字段
        taskMapper.update(null,
                new LambdaUpdateWrapper<WarmTask>()
                        .eq(WarmTask::getId, id)
                        .set(WarmTask::getStatus, status)
                        .set(WarmTask::getUpdatedAt, LocalDateTime.now()));
    }

    /** 查询任务，不存在则抛 404 业务异常 */
    private WarmTask requireTask(Long id) {
        WarmTask task = taskMapper.selectById(id);
        if (task == null) throw new BusinessException(404, "任务不存在: " + id);
        return task;
    }

    /** 将实体转换为 VO（含端点名称冗余字段，避免前端二次查询） */
    private WarmTaskVO toVO(WarmTask t, ModelEndpoint endpoint) {
        WarmTaskVO vo = new WarmTaskVO();
        vo.setId(t.getId());
        vo.setName(t.getName());
        vo.setEndpointId(t.getEndpointId());
        vo.setEndpointName(endpoint != null ? endpoint.getName() : null);
        vo.setCronExpr(t.getCronExpr());
        vo.setTps(t.getTps());
        vo.setThreadCount(t.getThreadCount());
        vo.setDurationSeconds(t.getDurationSeconds());
        vo.setRequestBody(t.getRequestBody());
        vo.setEnabled(t.getEnabled());
        vo.setStatus(t.getStatus());
        vo.setCreatedAt(t.getCreatedAt());
        vo.setUpdatedAt(t.getUpdatedAt());
        return vo;
    }
}
