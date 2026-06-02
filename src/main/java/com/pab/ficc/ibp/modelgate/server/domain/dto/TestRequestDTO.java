package com.pab.ficc.ibp.modelgate.server.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class TestRequestDTO {

    @NotNull(message = "端点 ID 不能为空")
    private Long endpointId;

    @NotBlank(message = "请求体不能为�?)
    private String requestBody;
}
