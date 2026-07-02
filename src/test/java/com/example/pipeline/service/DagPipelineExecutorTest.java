package com.example.pipeline.service;

import com.example.pipeline.http.OutputScriptExecutor;
import com.example.pipeline.model.*;
import com.example.pipeline.node.NodeRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DagPipelineExecutorTest {

    private DagPipelineExecutor executor;
    private NodeRunner nodeRunner;
    private OutputScriptExecutor outputScriptExecutor;
    private final Executor directExecutor = Runnable::run;
    private final Map<String, Integer> statuses = new HashMap<>();
    private final List<String> executed = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        executor = new DagPipelineExecutor();
        nodeRunner = mock(NodeRunner.class);
        outputScriptExecutor = mock(OutputScriptExecutor.class);
        setField(executor, "nodeRunner", nodeRunner);
        setField(executor, "outputScriptExecutor", outputScriptExecutor);
        setField(executor, "taskExecutor", directExecutor);

        doAnswer(invocation -> {
            NodeDef node = invocation.getArgument(0);
            ExecutionContext context = invocation.getArgument(1);
            executed.add(node.getNodeId());
            int status = statuses.getOrDefault(node.getNodeId(), 200);
            context.put(node.getNodeId(), status, Map.of("node", node.getNodeId()), 1);
            return null;
        }).when(nodeRunner).executeSingle(any(), any(), anyList());
    }

    @Test
    void 单链路按依赖执行() {
        ExecutionContext context = new ExecutionContext(Map.of());

        executor.run(config(
            List.of(node("n1"), node("n2")),
            List.of(edge("START", "n1"), edge("n1", "n2"), edge("n2", "END")),
            null), context, new ArrayList<>());

        assertThat(executed).containsExactly("n1", "n2");
        assertThat(context.get("n1")).isNotNull();
        assertThat(context.get("n2")).isNotNull();
    }

    @Test
    void 一个节点分叉到多个后继都执行() {
        ExecutionContext context = new ExecutionContext(Map.of());

        executor.run(config(
            List.of(node("n1"), node("n2"), node("n3")),
            List.of(edge("START", "n1"), edge("n1", "n2"), edge("n1", "n3"), edge("n2", "END"), edge("n3", "END")),
            null), context, new ArrayList<>());

        assertThat(executed).containsExactly("n1", "n2", "n3");
    }

    @Test
    void 上游失败时默认SUCCESS边不触发() {
        statuses.put("n1", 500);
        ExecutionContext context = new ExecutionContext(Map.of());

        executor.run(config(
            List.of(node("n1"), node("n2")),
            List.of(edge("START", "n1"), edge("n1", "n2"), edge("n2", "END")),
            null), context, new ArrayList<>());

        assertThat(executed).containsExactly("n1");
        assertThat(context.get("n2").getHttpStatus()).isEqualTo(204);
        assertThat((Map<String, Object>) context.get("n2").getData())
            .containsEntry("skipped", true)
            .containsEntry("data", null);
    }

    @Test
    void onStatusFAILED触发失败分支() {
        statuses.put("n1", 500);
        ExecutionContext context = new ExecutionContext(Map.of());
        EdgeDef failedEdge = edge("n1", "n2");
        failedEdge.setOnStatus("FAILED");

        executor.run(config(
            List.of(node("n1"), node("n2")),
            List.of(edge("START", "n1"), failedEdge, edge("n2", "END")),
            null), context, new ArrayList<>());

        assertThat(executed).containsExactly("n1", "n2");
    }

    @Test
    void conditionFalse的边不触发() {
        when(outputScriptExecutor.evaluateCondition(eq("false"), any())).thenReturn(false);
        ExecutionContext context = new ExecutionContext(Map.of());
        EdgeDef conditional = edge("n1", "n2");
        conditional.setCondition("false");

        executor.run(config(
            List.of(node("n1"), node("n2")),
            List.of(edge("START", "n1"), conditional, edge("n2", "END")),
            null), context, new ArrayList<>());

        assertThat(executed).containsExactly("n1");
        assertThat(context.get("n2").getHttpStatus()).isEqualTo(204);
        assertThat((Map<String, Object>) context.get("n2").getData())
            .containsEntry("skipped", true)
            .containsEntry("data", null);
    }

    @Test
    void failFast遇失败停止调度() {
        statuses.put("n1", 500);
        ExecutionContext context = new ExecutionContext(Map.of());
        DagConfig dagConfig = new DagConfig();
        dagConfig.setFailurePolicy("FAIL_FAST");
        dagConfig.setMaxConcurrency(1);

        executor.run(config(
            List.of(node("n1"), node("n2")),
            List.of(edge("START", "n1"), edge("START", "n2"), edge("n1", "END"), edge("n2", "END")),
            dagConfig), context, new ArrayList<>());

        assertThat(executed).containsExactly("n1");
    }

    @Test
    void continue下无依赖的其他分支继续执行() {
        statuses.put("n1", 500);
        ExecutionContext context = new ExecutionContext(Map.of());
        DagConfig dagConfig = new DagConfig();
        dagConfig.setFailurePolicy("CONTINUE");
        dagConfig.setMaxConcurrency(1);

        executor.run(config(
            List.of(node("n1"), node("n2")),
            List.of(edge("START", "n1"), edge("START", "n2"), edge("n1", "END"), edge("n2", "END")),
            dagConfig), context, new ArrayList<>());

        assertThat(executed).containsExactly("n1", "n2");
    }

    private PipelineConfig config(List<NodeDef> nodes, List<EdgeDef> edges, DagConfig dagConfig) {
        PipelineConfig config = new PipelineConfig();
        config.setNodes(nodes);
        config.setEdges(edges);
        config.setDagConfig(dagConfig);
        return config;
    }

    private NodeDef node(String id) {
        NodeDef node = new NodeDef();
        node.setNodeId(id);
        node.setNodeName(id);
        node.setNodeType("SERIAL");
        return node;
    }

    private EdgeDef edge(String from, String to) {
        EdgeDef edge = new EdgeDef();
        edge.setFrom(from);
        edge.setTo(to);
        return edge;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
