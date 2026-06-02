package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskExecutionVO {

    private Long id;
    private Long taskId;
    private String taskName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    private Long totalRequests;
    private Long successCount;
    private Long failCount;
    private Long totalTokens;
    private String status;
    private String errorMsg;
}
