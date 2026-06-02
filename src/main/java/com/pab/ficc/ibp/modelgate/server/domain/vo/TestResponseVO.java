package com.pab.ficc.ibp.modelgate.server.domain.vo;

import lombok.Data;

@Data
public class TestResponseVO {

    private boolean success;
    private int totalTokens;
    private String responseBody;
    private long latencyMs;
    private String errorMessage;
}
