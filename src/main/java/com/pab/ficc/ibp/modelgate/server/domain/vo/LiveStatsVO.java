package com.pab.ficc.ibp.modelgate.server.domain.vo;

import lombok.Data;

import java.util.List;

@Data
public class LiveStatsVO {

    private boolean running;
    private long totalRequests;
    private long successCount;
    private long failCount;
    private double successRate;
    private long totalTokens;
    private long elapsedMs;
    private double currentTps;
    private List<RecentCall> recentCalls;

    @Data
    public static class RecentCall {
        private String time;
        private boolean success;
        private long latencyMs;
        private int tokens;
        private String error;

        public RecentCall(String time, boolean success, long latencyMs, int tokens, String error) {
            this.time = time;
            this.success = success;
            this.latencyMs = latencyMs;
            this.tokens = tokens;
            this.error = error;
        }
    }
}
