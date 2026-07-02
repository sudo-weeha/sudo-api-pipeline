package com.example.pipeline.service;

import com.example.pipeline.dto.PipelineExecResponse;
import com.example.pipeline.model.EdgeDef;
import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeDef;
import com.example.pipeline.model.PipelineConfig;
import com.example.pipeline.model.PipelineDef;
import com.example.pipeline.node.NodeRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PipelineExecutorTest {

    private PipelineExecutor executor;
    private PipelineDefService pipelineDefService;
    private PipelineExecLogService pipelineExecLogService;
    private NodeRunner nodeRunner;
    private DagPipelineExecutor dagPipelineExecutor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new PipelineExecutor();
        pipelineDefService = mock(PipelineDefService.class);
        pipelineExecLogService = mock(PipelineExecLogService.class);
        nodeRunner = mock(NodeRunner.class);
        dagPipelineExecutor = mock(DagPipelineExecutor.class);
        setField(executor, "pipelineDefService", pipelineDefService);
        setField(executor, "pipelineExecLogService", pipelineExecLogService);
        setField(executor, "nodeRunner", nodeRunner);
        setField(executor, "dagPipelineExecutor", dagPipelineExecutor);
        when(pipelineExecLogService.toJson(any())).thenReturn("{}");

        PipelineDef def = new PipelineDef();
        def.setId(1L);
        def.setPipelineCode("P");
        def.setPipelineName("P");
        def.setStatus(1);
        when(pipelineDefService.findByCode("P")).thenReturn(def);
    }

    @Test
    void 无edges走旧NodeRunner() {
        when(pipelineDefService.parseConfig(any())).thenReturn(config(false));

        PipelineExecResponse response = executor.execute("P", Map.of());

        assertThat(response.getCode()).isEqualTo(0);
        verify(nodeRunner).run(anyList(), any(ExecutionContext.class), anyList());
        verifyNoInteractions(dagPipelineExecutor);
    }

    @Test
    void 有edges走DagPipelineExecutor() {
        when(pipelineDefService.parseConfig(any())).thenReturn(config(true));

        PipelineExecResponse response = executor.execute("P", Map.of());

        assertThat(response.getCode()).isEqualTo(0);
        verify(dagPipelineExecutor).run(any(PipelineConfig.class), any(ExecutionContext.class), anyList());
        verify(nodeRunner, never()).run(anyList(), any(), anyList());
    }

    private PipelineConfig config(boolean dag) {
        PipelineConfig config = new PipelineConfig();
        NodeDef node = new NodeDef();
        node.setNodeId("n1");
        node.setNodeType("SERIAL");
        config.setNodes(List.of(node));
        if (dag) {
            EdgeDef start = new EdgeDef();
            start.setFrom("START");
            start.setTo("n1");
            EdgeDef end = new EdgeDef();
            end.setFrom("n1");
            end.setTo("END");
            config.setEdges(List.of(start, end));
        } else {
            config.setEdges(new ArrayList<>());
        }
        return config;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
