package com.example.pipeline.node;

import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NodeRunner 的分发与错误收集逻辑用 mock 的子执行器验证，
 * 不依赖真实 SERIAL/FORK/ITERATE/SWITCH 执行器。
 */
class NodeRunnerTest {

    private NodeRunner runner;
    private SerialNodeExecutor serial;
    private ForkNodeExecutor fork;
    private IterateNodeExecutor iterate;
    private SwitchNodeExecutor sw;

    @BeforeEach
    void setUp() throws Exception {
        runner = new NodeRunner();
        serial = mock(SerialNodeExecutor.class);
        fork = mock(ForkNodeExecutor.class);
        iterate = mock(IterateNodeExecutor.class);
        sw = mock(SwitchNodeExecutor.class);
        setField(runner, "serialNodeExecutor", serial);
        setField(runner, "forkNodeExecutor", fork);
        setField(runner, "iterateNodeExecutor", iterate);
        setField(runner, "switchNodeExecutor", sw);
    }

    @Test
    void run空列表_不调用任何执行器() {
        runner.run(null, new ExecutionContext(Map.of()), new ArrayList<>());
        verifyNoInteractions(serial, fork, iterate, sw);
    }

    @Test
    void 按类型分发_SERIAL_FORK_ITERATE_SWITCH() {
        ExecutionContext ctx = new ExecutionContext(Map.of());
        List<NodeDef> nodes = List.of(
            node("s1", "SERIAL"),
            node("f1", "FORK"),
            node("i1", "ITERATE"),
            node("sw1", "SWITCH"));

        runner.run(nodes, ctx, new ArrayList<>());

        verify(serial, times(1)).execute(eq(nodes.get(0)), eq(ctx));
        verify(fork, times(1)).execute(eq(nodes.get(1)), eq(ctx));
        verify(iterate, times(1)).execute(eq(nodes.get(2)), eq(ctx));
        verify(sw, times(1)).execute(eq(nodes.get(3)), eq(ctx), anyList());
    }

    @Test
    void SWITCH分发携带nodeErrors参数_其他类型不带() {
        ExecutionContext ctx = new ExecutionContext(Map.of());
        List<NodeDef> nodes = List.of(node("s1", "SERIAL"), node("sw1", "SWITCH"));
        List<Map<String, Object>> errors = new ArrayList<>();

        runner.run(nodes, ctx, errors);

        verify(serial).execute(any(), any());              // 二参
        verify(sw).execute(any(), any(), eq(errors));      // 三参含 errors
    }

    @Test
    void 未知节点类型_抛异常() {
        NodeDef bad = node("x", "WAT");
        try {
            runner.run(List.of(bad), new ExecutionContext(Map.of()), new ArrayList<>());
            org.assertj.core.api.Assertions.fail("应抛异常");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("未知的节点类型").contains("WAT");
        }
    }

    @Test
    void httpStatus大于等于400_收集到nodeErrors() {
        ExecutionContext ctx = new ExecutionContext(Map.of());
        doAnswer(inv -> {
            ctx.put("s1", 500, Map.of("error", "boom"), 10);
            return null;
        }).when(serial).execute(any(), any());

        List<Map<String, Object>> errors = new ArrayList<>();
        runner.run(List.of(node("s1", "SERIAL")), ctx, errors);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("nodeId", "s1");
        assertThat(errors.get(0)).containsEntry("httpStatus", 500);
        assertThat(errors.get(0)).containsEntry("message", "boom");
    }

    private NodeDef node(String id, String type) {
        NodeDef n = new NodeDef();
        n.setNodeId(id);
        n.setNodeName(id);
        n.setNodeType(type);
        return n;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
