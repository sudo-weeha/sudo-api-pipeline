package com.example.pipeline.config;

import com.example.pipeline.model.ApiRegistry;
import com.example.pipeline.model.PipelineDef;
import com.example.pipeline.repository.ApiRegistryMapper;
import com.example.pipeline.repository.PipelineDefMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DemoDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    @Resource
    private ApiRegistryMapper apiRegistryMapper;

    @Resource
    private PipelineDefMapper pipelineDefMapper;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${server.port:8080}")
    private int serverPort;

    @EventListener(ApplicationReadyEvent.class)
    public void initDemoData() {
        upsertApi("获取用户ID", "GET_USER_ID", "/mock-api/user/query", "本地测试 API：根据 id 返回 userid");
        upsertApi("获取用户详情", "GET_USER_DETAIL", "/mock-api/user/detail", "本地测试 API：根据 userid 返回用户详情");
        upsertApi("获取用户类型", "GET_USER_TYPE", "/mock-api/user/type", "本地测试 API：根据 id 返回 userid 与 type(VIP/NORMAL)");
        upsertApi("VIP折扣", "GET_VIP_DISCOUNT", "/mock-api/vip/discount", "本地测试 API：VIP 专属折扣");
        upsertApi("普通用户信息", "GET_NORMAL_INFO", "/mock-api/normal/info", "本地测试 API：普通用户信息");
        upsertPipeline();
        upsertSwitchPipeline();
    }

    private void upsertApi(String apiName, String apiCode, String path, String description) {
        ApiRegistry api = apiRegistryMapper.findByCode(apiCode);
        if (api == null) {
            api = new ApiRegistry();
            api.setApiCode(apiCode);
        }
        api.setApiName(apiName);
        api.setRequestMethod("POST");
        api.setRequestUrl("http://127.0.0.1:" + serverPort + path);
        api.setRequestHeaders(null);
        api.setTimeoutMs(5000);
        api.setRetryCount(0);
        api.setCacheTtlMin(0);
        api.setDescription(description);
        api.setStatus(1);

        if (api.getId() == null) {
            apiRegistryMapper.insert(api);
        } else {
            apiRegistryMapper.update(api);
        }
    }

    private void upsertPipeline() {
        PipelineDef pipeline = pipelineDefMapper.findByCode("USER_CHAIN");
        if (pipeline == null) {
            pipeline = new PipelineDef();
            pipeline.setPipelineCode("USER_CHAIN");
        }
        pipeline.setPipelineName("用户信息串联");
        pipeline.setPipelineConfig(buildPipelineConfigJson());
        pipeline.setDescription("本地测试编排：id -> userid -> 用户详情");
        pipeline.setStatus(1);

        if (pipeline.getId() == null) {
            pipelineDefMapper.insert(pipeline);
        } else {
            pipelineDefMapper.update(pipeline);
        }
        log.info("本地测试 API 和 USER_CHAIN 编排已初始化");
    }

    private String buildPipelineConfigJson() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("inputParams", List.of(inputParam()));
        config.put("nodes", List.of(getUserIdNode(), getUserDetailNode()));
        config.put("edges", List.of(
            edge("START", "n1"),
            edge("n1", "n2"),
            edge("n2", "END")
        ));
        config.put("dagConfig", dagConfig());
        config.put("outputMapping", outputMapping());
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("初始化测试编排 JSON 失败", e);
        }
    }

    private Map<String, Object> inputParam() {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("name", "id");
        param.put("type", "string");
        param.put("required", true);
        param.put("description", "测试用户 ID");
        return param;
    }

    private Map<String, Object> getUserIdNode() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeId", "n1");
        node.put("nodeName", "获取用户ID");
        node.put("nodeType", "SERIAL");
        node.put("apiCode", "GET_USER_ID");
        node.put("inputMapping", Map.of("id", "${request.id}"));
        return node;
    }

    private Map<String, Object> getUserDetailNode() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeId", "n2");
        node.put("nodeName", "获取用户详情");
        node.put("nodeType", "SERIAL");
        node.put("apiCode", "GET_USER_DETAIL");
        node.put("inputMapping", Map.of("userid", "${n1.data.data.userid}"));
        return node;
    }

    private Map<String, Object> outputMapping() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", "${n1.data.data.userid}");
        data.put("detail", "${n2.data.data}");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("code", 0);
        output.put("data", data);
        output.put("msg", "success");
        return output;
    }

    private void upsertSwitchPipeline() {
        PipelineDef pipeline = pipelineDefMapper.findByCode("USER_TYPE_SWITCH");
        if (pipeline == null) {
            pipeline = new PipelineDef();
            pipeline.setPipelineCode("USER_TYPE_SWITCH");
        }
        pipeline.setPipelineName("用户ID分流");
        pipeline.setPipelineConfig(buildSwitchPipelineConfigJson());
        pipeline.setDescription("条件分支示例：userid=1 走接口1 / userid=2 走接口2");
        pipeline.setStatus(1);

        if (pipeline.getId() == null) {
            pipelineDefMapper.insert(pipeline);
        } else {
            pipelineDefMapper.update(pipeline);
        }
        log.info("USER_TYPE_SWITCH 条件分支编排已初始化");
    }

    private String buildSwitchPipelineConfigJson() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("inputParams", List.of(inputParam()));
        config.put("nodes", List.of(getUserTypeNode(), getVipDiscountNode(), getNormalInfoNode()));
        config.put("edges", List.of(
            edge("START", "n1"),
            conditionalEdge("n1", "n2", "n1.data.data.userid == '1'"),
            conditionalEdge("n1", "n3", "n1.data.data.userid == '2'"),
            edge("n2", "END"),
            edge("n3", "END")
        ));
        config.put("dagConfig", dagConfig());
        config.put("outputMapping", switchOutputMapping());
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("初始化 SWITCH 编排 JSON 失败", e);
        }
    }

    private Map<String, Object> getUserTypeNode() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeId", "n1");
        node.put("nodeName", "获取用户ID");
        node.put("nodeType", "SERIAL");
        node.put("apiCode", "GET_USER_TYPE");
        node.put("inputMapping", Map.of("id", "${request.id}"));
        return node;
    }

    private Map<String, Object> getVipDiscountNode() {
        Map<String, Object> vipNode = new LinkedHashMap<>();
        vipNode.put("nodeId", "n2");
        vipNode.put("nodeName", "接口1");
        vipNode.put("nodeType", "SERIAL");
        vipNode.put("apiCode", "GET_VIP_DISCOUNT");
        vipNode.put("inputMapping", Map.of("userid", "${n1.data.data.userid}"));
        return vipNode;
    }

    private Map<String, Object> getNormalInfoNode() {
        Map<String, Object> normalNode = new LinkedHashMap<>();
        normalNode.put("nodeId", "n3");
        normalNode.put("nodeName", "接口2");
        normalNode.put("nodeType", "SERIAL");
        normalNode.put("apiCode", "GET_NORMAL_INFO");
        normalNode.put("inputMapping", Map.of("userid", "${n1.data.data.userid}"));
        return normalNode;
    }

    private Map<String, Object> edge(String from, String to) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", from);
        edge.put("to", to);
        edge.put("onStatus", "SUCCESS");
        return edge;
    }

    private Map<String, Object> conditionalEdge(String from, String to, String condition) {
        Map<String, Object> edge = edge(from, to);
        edge.put("condition", condition);
        return edge;
    }

    private Map<String, Object> dagConfig() {
        Map<String, Object> dagConfig = new LinkedHashMap<>();
        dagConfig.put("maxConcurrency", 8);
        dagConfig.put("failurePolicy", "CONTINUE");
        return dagConfig;
    }

    private Map<String, Object> switchOutputMapping() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", "${n1.data.data.userid}");
        data.put("userType", "${n1.data.data.type}");
        data.put("interface1", "${n2.data.data}");
        data.put("interface2", "${n3.data.data}");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("code", 0);
        output.put("data", data);
        output.put("msg", "success");
        return output;
    }
}
