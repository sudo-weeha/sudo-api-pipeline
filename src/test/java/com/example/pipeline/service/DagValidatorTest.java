package com.example.pipeline.service;

import com.example.pipeline.model.ApiRegistry;
import com.example.pipeline.model.EdgeDef;
import com.example.pipeline.model.NodeDef;
import com.example.pipeline.model.PipelineConfig;
import com.example.pipeline.repository.ApiRegistryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DagValidatorTest {

    private DagValidator validator;
    private ApiRegistryMapper apiRegistryMapper;

    @BeforeEach
    void setUp() throws Exception {
        validator = new DagValidator();
        apiRegistryMapper = mock(ApiRegistryMapper.class);
        when(apiRegistryMapper.findByCode(anyString())).thenReturn(new ApiRegistry());
        setField(validator, "apiRegistryMapper", apiRegistryMapper);
    }

    @Test
    void 合法DAG通过() {
        validator.validate(config(
            List.of(node("n1"), node("n2")),
            List.of(edge("START", "n1"), edge("n1", "n2"), edge("n2", "END"))));
    }

    @Test
    void 重复nodeId报错() {
        PipelineConfig config = config(
            List.of(node("n1"), node("n1")),
            List.of(edge("START", "n1"), edge("n1", "END")));

        assertThatThrownBy(() -> validator.validate(config))
            .hasMessageContaining("nodeId 重复");
    }

    @Test
    void 边引用不存在节点报错() {
        PipelineConfig config = config(
            List.of(node("n1")),
            List.of(edge("START", "n1"), edge("n1", "n9"), edge("n1", "END")));

        assertThatThrownBy(() -> validator.validate(config))
            .hasMessageContaining("边引用的节点不存在")
            .hasMessageContaining("n9");
    }

    @Test
    void 环路报错() {
        PipelineConfig config = config(
            List.of(node("n1"), node("n2")),
            List.of(edge("START", "n1"), edge("n1", "n2"), edge("n2", "n1"), edge("n2", "END")));

        assertThatThrownBy(() -> validator.validate(config))
            .hasMessageContaining("环路");
    }

    @Test
    void 缺少START报错() {
        PipelineConfig config = config(
            List.of(node("n1")),
            List.of(edge("n1", "END")));

        assertThatThrownBy(() -> validator.validate(config))
            .hasMessageContaining("START");
    }

    @Test
    void 缺少END报错() {
        PipelineConfig config = config(
            List.of(node("n1")),
            List.of(edge("START", "n1")));

        assertThatThrownBy(() -> validator.validate(config))
            .hasMessageContaining("END");
    }

    @Test
    void 无edges旧配置仍兼容() {
        PipelineConfig config = new PipelineConfig();
        config.setNodes(List.of(node("n1")));

        validator.validate(config);
    }

    private PipelineConfig config(List<NodeDef> nodes, List<EdgeDef> edges) {
        PipelineConfig config = new PipelineConfig();
        config.setNodes(nodes);
        config.setEdges(edges);
        return config;
    }

    private NodeDef node(String id) {
        NodeDef node = new NodeDef();
        node.setNodeId(id);
        node.setNodeName(id);
        node.setNodeType("SERIAL");
        node.setApiCode("API_" + id);
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
