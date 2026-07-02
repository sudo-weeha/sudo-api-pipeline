package com.example.pipeline.dto;

import com.example.pipeline.model.PipelineConfig;
import lombok.Data;

@Data
public class PipelineDefDTO {
    private Long id;
    private String pipelineName;
    private String pipelineCode;
    private PipelineConfig pipelineConfig;
    private String description;
    private Integer status;
}
