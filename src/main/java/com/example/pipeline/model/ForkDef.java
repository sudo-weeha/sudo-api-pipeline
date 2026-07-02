package com.example.pipeline.model;

import lombok.Data;
import java.util.List;

/**
 * FORK 分支定义（tasks 列表）
 */
@Data
public class ForkDef {
    private List<NodeDef> tasks;
}
