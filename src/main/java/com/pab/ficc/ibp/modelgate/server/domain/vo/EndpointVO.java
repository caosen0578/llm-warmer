package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EndpointVO {

    private Long id;
    private String name;
    private String baseUrl;
    private String modelName;
    private String httpMethod;
    /** 自定义请求头数量（不返回具体内容，避免敏感信息泄露） */
    private Integer headerCount;
    private Integer enabled;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
