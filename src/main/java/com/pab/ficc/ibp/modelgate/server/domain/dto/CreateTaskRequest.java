package com.pab.ficc.ibp.modelgate.server.domain.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class CreateTaskRequest {

    @NotBlank(message = "任务名称不能为空")
    private String name;

    @NotNull(message = "端点 ID 不能为空")
    private Long endpointId;

    @NotBlank(message = "Cron 表达式不能为空")
    private String cronExpr;

    @Min(value = 1, message = "TPS 最小为 1")
    private int tps = 1;

    @Min(value = 1, message = "并发线程数最小为 1")
    private int threadCount = 1;

    /**
     * 持续时长（秒），-1 表示长期运行直到手动停止。
     */
    @Min(value = -1, message = "持续时长最小为 -1，-1 代表长期运行")
    private int durationSeconds = 3600;

    @NotBlank(message = "请求体不能为空")
    private String requestBody;
}
