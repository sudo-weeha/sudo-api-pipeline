package com.example.pipeline.model;

import lombok.Data;

@Data
public class EdgeDef {
    private String from;
    private String to;
    private String condition;
    private String onStatus;
}
