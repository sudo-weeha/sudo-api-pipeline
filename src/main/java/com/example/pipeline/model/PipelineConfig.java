package com.example.pipeline.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class PipelineConfig {
    private List<InputParamDef> inputParams;
    private List<NodeDef> nodes;
    private List<EdgeDef> edges;
    private DagConfig dagConfig;
    private Map<String, Object> outputMapping;
    private String outputScript;
}
