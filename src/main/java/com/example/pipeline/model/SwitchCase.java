package com.example.pipeline.model;

import lombok.Data;
import java.util.List;

/**
 * SWITCH 节点的单个分支
 */
@Data
public class SwitchCase {
    /** JS 表达式，求值为 true 则命中此 case */
    private String when;
    /** 命中后顺序执行的子节点 */
    private List<NodeDef> nodes;
}
