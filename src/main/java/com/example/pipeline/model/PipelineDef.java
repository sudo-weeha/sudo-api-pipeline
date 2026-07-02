package com.example.pipeline.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class PipelineDef {
    private Long id;
    private String pipelineName;
    private String pipelineCode;
    private String pipelineConfig;
    private String description;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
