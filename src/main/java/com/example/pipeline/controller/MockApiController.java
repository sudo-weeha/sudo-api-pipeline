package com.example.pipeline.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/mock-api")
public class MockApiController {

    @PostMapping("/user/query")
    public Map<String, Object> queryUser(@RequestBody Map<String, Object> params) {
        Object id = params != null ? params.get("id") : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userid", id != null ? "user-" + id : "user-default");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", "200");
        response.put("data", data);
        response.put("msg", "success");
        return response;
    }

    @PostMapping("/user/detail")
    public Map<String, Object> queryUserDetail(@RequestBody Map<String, Object> params) {
        Object userid = params != null ? params.get("userid") : null;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userid", userid);
        data.put("name", userid != null ? "测试用户-" + userid : "测试用户");
        data.put("level", 3);
        data.put("active", true);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", "200");
        response.put("data", data);
        response.put("msg", "success");
        return response;
    }

    @PostMapping("/user/type")
    public Map<String, Object> queryUserType(@RequestBody Map<String, Object> params) {
        Object id = params != null ? params.get("id") : null;
        String idStr = id != null ? String.valueOf(id) : "0";
        // id 末位偶数 → VIP，奇数 → NORMAL
        char lastChar = idStr.charAt(idStr.length() - 1);
        String type = (Character.isDigit(lastChar) && (lastChar - '0') % 2 != 0) ? "NORMAL" : "VIP";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userid", id != null ? String.valueOf(id) : "0");
        data.put("type", type);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", "200");
        response.put("data", data);
        response.put("msg", "success");
        return response;
    }

    @PostMapping("/vip/discount")
    public Map<String, Object> vipDiscount(@RequestBody Map<String, Object> params) {
        Object userid = params != null ? params.get("userid") : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userid", userid);
        data.put("level", "VIP");
        data.put("discount", 0.7);
        data.put("benefit", "专属客服 + 生日礼");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", "200");
        response.put("data", data);
        response.put("msg", "success");
        return response;
    }

    @PostMapping("/normal/info")
    public Map<String, Object> normalInfo(@RequestBody Map<String, Object> params) {
        Object userid = params != null ? params.get("userid") : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userid", userid);
        data.put("level", "NORMAL");
        data.put("points", 100);
        data.put("tip", "消费累计积分可升级");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", "200");
        response.put("data", data);
        response.put("msg", "success");
        return response;
    }
}
