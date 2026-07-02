package com.example.pipeline.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class NodeDef {
    private String nodeId;
    private String nodeName;
    private String nodeType;        // SERIAL, FORK, ITERATE
    private String apiCode;         // SERIAL 节点使用
    private Map<String, Object> inputMapping;  // SERIAL 节点使用
    private List<NodeDef> tasks;    // FORK 节点使用
    private String iterateOn;       // ITERATE 节点使用
    private String splitBy;         // ITERATE 节点使用（可选）
    private String itemAlias;       // ITERATE 节点使用
    private Integer maxConcurrency; // ITERATE 节点使用
    private SubPipelineDef subPipeline; // ITERATE 节点使用
    private List<SwitchCase> cases;        // SWITCH 节点使用
    private List<NodeDef> defaultCase;     // SWITCH 节点使用（可选）
}
