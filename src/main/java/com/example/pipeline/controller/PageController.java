package com.example.pipeline.controller;

import com.example.pipeline.model.ApiRegistry;
import com.example.pipeline.model.PipelineConfig;
import com.example.pipeline.model.PipelineDef;
import com.example.pipeline.model.PipelineExecLog;
import com.example.pipeline.service.ApiRegistryService;
import com.example.pipeline.service.PipelineDefService;
import com.example.pipeline.service.PipelineExecLogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import javax.annotation.Resource;
import java.util.List;

@Controller
public class PageController {

    @Resource
    private ApiRegistryService apiRegistryService;

    @Resource
    private PipelineDefService pipelineDefService;

    @Resource
    private PipelineExecLogService pipelineExecLogService;

    // ===== API 注册页面 =====

    @GetMapping("/page/api-registry")
    public String apiRegistryList(Model model) {
        List<ApiRegistry> apis = apiRegistryService.findAll();
        model.addAttribute("title", "API 注册列表");
        model.addAttribute("apis", apis);
        model.addAttribute("content", "api-registry/list");
        return "layout";
    }

    @GetMapping("/page/api-registry/add")
    public String apiRegistryAdd(Model model) {
        model.addAttribute("title", "注册新 API");
        model.addAttribute("content", "api-registry/form");
        return "layout";
    }

    @GetMapping("/page/api-registry/{id}/edit")
    public String apiRegistryEdit(@PathVariable Long id, Model model) {
        ApiRegistry api = apiRegistryService.findById(id);
        if (api == null) {
            return "redirect:/page/api-registry";
        }
        model.addAttribute("title", "编辑 API");
        model.addAttribute("api", api);
        model.addAttribute("content", "api-registry/form");
        return "layout";
    }

    // ===== 编排管理页面 =====

    @GetMapping("/page/pipeline")
    public String pipelineList(Model model) {
        List<PipelineDef> pipelines = pipelineDefService.findAll();
        model.addAttribute("title", "编排管理");
        model.addAttribute("pipelines", pipelines);
        model.addAttribute("content", "pipeline/list");
        return "layout";
    }

    @GetMapping("/page/pipeline/add")
    public String pipelineAdd(Model model) {
        model.addAttribute("title", "新增编排");
        model.addAttribute("content", "pipeline/form");
        return "layout";
    }

    @GetMapping("/page/pipeline/{id}/edit")
    public String pipelineEdit(@PathVariable Long id, Model model) {
        PipelineDef pipeline = pipelineDefService.findById(id);
        if (pipeline == null) {
            return "redirect:/page/pipeline";
        }
        PipelineConfig config = pipelineDefService.parseConfig(pipeline);
        model.addAttribute("title", "编辑编排");
        model.addAttribute("pipeline", pipeline);
        model.addAttribute("pipelineConfig", config);
        model.addAttribute("content", "pipeline/form");
        return "layout";
    }

    @GetMapping("/page/pipeline/{id}/test")
    public String pipelineTest(@PathVariable Long id, Model model) {
        PipelineDef pipeline = pipelineDefService.findById(id);
        if (pipeline == null) {
            return "redirect:/page/pipeline";
        }
        PipelineConfig config = pipelineDefService.parseConfig(pipeline);
        model.addAttribute("title", "测试运行");
        model.addAttribute("pipeline", pipeline);
        model.addAttribute("pipelineConfig", config);
        model.addAttribute("content", "pipeline/test");
        return "layout";
    }

    // ===== 执行日志页面 =====

    @GetMapping("/page/exec-log")
    public String execLogList(Model model) {
        List<PipelineExecLog> logs = pipelineExecLogService.findAll();
        model.addAttribute("title", "执行日志");
        model.addAttribute("logs", logs);
        model.addAttribute("content", "exec-log/list");
        return "layout";
    }
}
