package com.example.pipeline.model;

import lombok.Data;

@Data
public class IterateDef {
    private String iterateOn;
    private String splitBy;
    private String itemAlias;
    private Integer maxConcurrency;
    private SubPipelineDef subPipeline;
}
