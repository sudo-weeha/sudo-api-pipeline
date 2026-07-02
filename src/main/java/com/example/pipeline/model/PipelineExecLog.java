package com.example.pipeline.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class PipelineExecLog {
    private Long id;
    private Long pipelineId;
    private String pipelineCode;
    private String requestParams;
    private String nodeResults;
    private String finalResponse;
    private Integer totalMs;
    private String status;
    private String errorMsg;
    private LocalDateTime createdAt;
}
