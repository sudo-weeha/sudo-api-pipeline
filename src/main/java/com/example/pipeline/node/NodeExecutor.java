package com.example.pipeline.node;

import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeDef;

/**
 * 节点执行器接口
 */
public interface NodeExecutor {
    void execute(NodeDef node, ExecutionContext context);
}
