package com.example.pipeline.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class NodeResult {
    private int httpStatus;
    private Object data;
    private long elapsedMs;
}
