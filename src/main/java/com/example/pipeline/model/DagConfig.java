package com.example.pipeline.model;

import lombok.Data;

@Data
public class DagConfig {
    private Integer maxConcurrency;
    private String failurePolicy;
}
