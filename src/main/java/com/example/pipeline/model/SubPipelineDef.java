package com.example.pipeline.model;

import lombok.Data;
import java.util.List;

@Data
public class SubPipelineDef {
    private List<NodeDef> nodes;
}
