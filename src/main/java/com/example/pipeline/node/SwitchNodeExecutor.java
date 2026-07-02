package com.example.pipeline.node;

import com.example.pipeline.http.ExpressionException;
import com.example.pipeline.http.OutputScriptExecutor;
import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeDef;
import com.example.pipeline.model.SwitchCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SWITCH 节点执行器：按 cases 顺序评估 when（JS 表达式），
 * 命中第一个为 true 的 case 后顺序执行其子节点；
 * 全不命中则执行 defaultCase（若有）。
 * 子节点结果写入父 context，SWITCH 自身写入 matchedCase。
 */
@Component
public class SwitchNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(SwitchNodeExecutor.class);

    /** matchedCase 约定 */
    private static final int MATCHED_NONE = -1;
    private static final int MATCHED_DEFAULT = -2;

    @Resource
    private OutputScriptExecutor outputScriptExecutor;

    @Resource
    @org.springframework.context.annotation.Lazy
    private NodeRunner nodeRunner;

    public void execute(NodeDef node, ExecutionContext context, List<Map<String, Object>> nodeErrors) {
        long start = System.currentTimeMillis();
        String nodeId = node.getNodeId();

        List<SwitchCase> cases = node.getCases();
        int matchedCase = MATCHED_NONE;
        boolean executed = false;

        if (cases != null) {
            for (int i = 0; i < cases.size(); i++) {
                SwitchCase switchCase = cases.get(i);
                String when = switchCase.getWhen();
                boolean hit;
                try {
                    hit = outputScriptExecutor.evaluateCondition(when, context);
                } catch (ExpressionException e) {
                    // when 异常/求值失败：视为不命中，继续下一个 case，不中断编排
                    log.warn("SWITCH 节点 {} case[{}] when 求值失败，跳过: expr=[{}] err={}",
                        nodeId, i, when, e.getMessage());
                    continue;
                }
                if (hit) {
                    matchedCase = i;
                    log.debug("SWITCH 节点 {} 命中 case[{}], expr=[{}]", nodeId, i, when);
                    nodeRunner.run(switchCase.getNodes(), context, nodeErrors);
                    executed = true;
                    break;
                }
            }
        }

        if (!executed) {
            List<NodeDef> defaultCase = node.getDefaultCase();
            if (defaultCase != null && !defaultCase.isEmpty()) {
                matchedCase = MATCHED_DEFAULT;
                log.debug("SWITCH 节点 {} 无 case 命中，执行 defaultCase", nodeId);
                nodeRunner.run(defaultCase, context, nodeErrors);
            } else {
                log.debug("SWITCH 节点 {} 无 case 命中且无 defaultCase，跳过", nodeId);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("matchedCase", matchedCase);
        long elapsedMs = System.currentTimeMillis() - start;
        context.put(nodeId, 200, data, elapsedMs);
        log.debug("SWITCH 节点 {} 完成, matchedCase={}, elapsed={}ms", nodeId, matchedCase, elapsedMs);
    }
}
