package com.pab.ficc.ibp.modelgate.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task_execution")
public class TaskExecution {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long totalRequests;

    private Long successCount;

    private Long failCount;

    private Long totalTokens;

    /** RUNNING / SUCCESS / STOPPED / FAILED */
    private String status;

    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
