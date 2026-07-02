package com.example.pipeline.dto;

import java.util.List;
import java.util.Map;

/**
 * 统一响应包装
 */
public class PipelineExecResponse {
    private int code;
    private Object data;
    private String msg;
    private List<Map<String, Object>> errors;

    public static PipelineExecResponse success(Object data) {
        PipelineExecResponse resp = new PipelineExecResponse();
        resp.code = 0;
        resp.data = data;
        resp.msg = "success";
        return resp;
    }

    public static PipelineExecResponse partial(Object data, List<Map<String, Object>> errors) {
        PipelineExecResponse resp = new PipelineExecResponse();
        resp.code = -1;
        resp.data = data;
        resp.msg = "编排执行部分失败";
        resp.errors = errors;
        return resp;
    }

    public static PipelineExecResponse error(String msg) {
        PipelineExecResponse resp = new PipelineExecResponse();
        resp.code = -1;
        resp.data = null;
        resp.msg = msg;
        return resp;
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public Object getData() { return data; }
    public void setData(Object data) { this.data = data; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public List<Map<String, Object>> getErrors() { return errors; }
    public void setErrors(List<Map<String, Object>> errors) { this.errors = errors; }
}
