package com.example.pipeline.model;

import lombok.Data;

@Data
public class InputParamDef {
    private String name;
    private String type;
    private Boolean required;
    private String description;
}
