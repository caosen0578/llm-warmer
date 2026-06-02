package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WarmTaskVO {

    private Long id;
    private String name;
    private Long endpointId;
    private String endpointName;
    private String cronExpr;
    private Integer tps;
    private Integer threadCount;
    /** -1 表示长期运行 */
    private Integer durationSeconds;
    private String requestBody;
    private Integer enabled;
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
