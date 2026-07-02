package com.example.pipeline.node;

import com.example.pipeline.http.ExpressionException;
import com.example.pipeline.http.OutputScriptExecutor;
import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeDef;
import com.example.pipeline.model.SwitchCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SwitchNodeExecutorTest {

    private OutputScriptExecutor outputScriptExecutor;
    private NodeRunner nodeRunner;
    private SwitchNodeExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        outputScriptExecutor = mock(OutputScriptExecutor.class);
        nodeRunner = mock(NodeRunner.class);
        executor = new SwitchNodeExecutor();
        setField(executor, "outputScriptExecutor", outputScriptExecutor);
        setField(executor, "nodeRunner", nodeRunner);
    }

    @Test
    void 命中第一个case_只执行该case的子节点_且context写入matchedCase0() {
        when(outputScriptExecutor.evaluateCondition(eq("c0"), any())).thenReturn(true);
        ExecutionContext ctx = new ExecutionContext(Map.of());
        NodeDef node = switchNode(List.of(aCase("c0", "n2"), aCase("c1", "n3")), List.of());

        executor.execute(node, ctx, new ArrayList<>());

        List<NodeDef> run = captureSingleRun();
        assertThat(run).extracting(NodeDef::getNodeId).containsExactly("n2");
        assertMatchedCase(ctx, 0);
        assertThat(ctx.get("sw1").getHttpStatus()).isEqualTo(200);
    }

    @Test
    void 第一个不命中第二个命中_执行第二个case() {
        when(outputScriptExecutor.evaluateCondition(eq("c0"), any())).thenReturn(false);
        when(outputScriptExecutor.evaluateCondition(eq("c1"), any())).thenReturn(true);
        ExecutionContext ctx = new ExecutionContext(Map.of());
        NodeDef node = switchNode(List.of(aCase("c0", "n2"), aCase("c1", "n3")), List.of());

        executor.execute(node, ctx, new ArrayList<>());

        List<NodeDef> run = captureSingleRun();
        assertThat(run).extracting(NodeDef::getNodeId).containsExactly("n3");
        assertMatchedCase(ctx, 1);
    }

    @Test
    void 全不命中_有default_执行default_匹配为负二() {
        when(outputScriptExecutor.evaluateCondition(anyString(), any())).thenReturn(false);
        ExecutionContext ctx = new ExecutionContext(Map.of());
        NodeDef node = switchNode(List.of(aCase("c0", "n2")), List.of(serialNode("ndef")));

        executor.execute(node, ctx, new ArrayList<>());

        List<NodeDef> run = captureSingleRun();
        assertThat(run).extracting(NodeDef::getNodeId).containsExactly("ndef");
        assertMatchedCase(ctx, -2);
    }

    @Test
    void 全不命中_无default_不执行子节点_匹配为负一() {
        when(outputScriptExecutor.evaluateCondition(anyString(), any())).thenReturn(false);
        ExecutionContext ctx = new ExecutionContext(Map.of());
        NodeDef node = switchNode(List.of(aCase("c0", "n2")), null);

        executor.execute(node, ctx, new ArrayList<>());

        verify(nodeRunner, never()).run(anyList(), any(), any());
        assertMatchedCase(ctx, -1);
    }

    @Test
    void when抛异常_该case跳过_继续评估后续case() {
        when(outputScriptExecutor.evaluateCondition(eq("c0"), any()))
            .thenThrow(new ExpressionException("boom"));
        when(outputScriptExecutor.evaluateCondition(eq("c1"), any())).thenReturn(true);
        ExecutionContext ctx = new ExecutionContext(Map.of());
        NodeDef node = switchNode(List.of(aCase("c0", "n2"), aCase("c1", "n3")), List.of());

        executor.execute(node, ctx, new ArrayList<>());

        List<NodeDef> run = captureSingleRun();
        assertThat(run).extracting(NodeDef::getNodeId).containsExactly("n3");
        assertMatchedCase(ctx, 1);
    }

    @Test
    void cases为空_无default_直接通过_匹配为负一() {
        ExecutionContext ctx = new ExecutionContext(Map.of());
        NodeDef node = switchNode(null, null);

        executor.execute(node, ctx, new ArrayList<>());

        verify(nodeRunner, never()).run(anyList(), any(), any());
        assertMatchedCase(ctx, -1);
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private List<NodeDef> captureSingleRun() {
        ArgumentCaptor<List<NodeDef>> captor = ArgumentCaptor.forClass(List.class);
        verify(nodeRunner, times(1)).run(captor.capture(), any(), any());
        return captor.getValue();
    }

    private void assertMatchedCase(ExecutionContext ctx, int expected) {
        assertThat(ctx.get("sw1")).as("sw1 应写入 NodeResult").isNotNull();
        Map<String, Object> data = (Map<String, Object>) ctx.get("sw1").getData();
        assertThat(data.get("matchedCase")).isEqualTo(expected);
    }

    private NodeDef switchNode(List<SwitchCase> cases, List<NodeDef> defaultCase) {
        NodeDef node = new NodeDef();
        node.setNodeId("sw1");
        node.setNodeName("分支");
        node.setNodeType("SWITCH");
        node.setCases(cases);
        node.setDefaultCase(defaultCase);
        return node;
    }

    private SwitchCase aCase(String when, String... childIds) {
        SwitchCase c = new SwitchCase();
        c.setWhen(when);
        List<NodeDef> nodes = new ArrayList<>();
        for (String id : childIds) {
            nodes.add(serialNode(id));
        }
        c.setNodes(nodes);
        return c;
    }

    private NodeDef serialNode(String id) {
        NodeDef n = new NodeDef();
        n.setNodeId(id);
        n.setNodeType("SERIAL");
        return n;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
