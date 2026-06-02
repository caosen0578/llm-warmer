package com.pab.ficc.ibp.modelgate.server.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class UpdateTaskRequest {

    @NotNull(message = "任务 ID 不能为空")
    private Long id;

    private String name;
    private Long endpointId;
    private String cronExpr;
    private Integer tps;
    private Integer threadCount;
    /** -1 表示长期运行直到手动停止 */
    private Integer durationSeconds;
    private String requestBody;
    /** 0=禁用 1=启用 */
    private Integer enabled;
}
