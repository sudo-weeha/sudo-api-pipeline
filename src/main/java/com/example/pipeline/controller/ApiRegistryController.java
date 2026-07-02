package com.example.pipeline.controller;

import com.example.pipeline.dto.ApiRegistryDTO;
import com.example.pipeline.model.ApiRegistry;
import com.example.pipeline.service.ApiRegistryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ApiRegistryController {

    @Resource
    private ApiRegistryService apiRegistryService;

    /**
     * 注册一个新 API
     */
    @PostMapping("/api-registry")
    public ResponseEntity<Map<String, Object>> create(@RequestBody ApiRegistryDTO dto) {
        ApiRegistry api = apiRegistryService.create(dto);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", api);
        result.put("msg", "success");
        return ResponseEntity.ok(result);
    }

    /**
     * 删除 API
     */
    @DeleteMapping("/api-registry/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        apiRegistryService.delete(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("msg", "success");
        return ResponseEntity.ok(result);
    }

    /**
     * 修改 API（URL、超时、缓存等）
     */
    @PutMapping("/api-registry/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody ApiRegistryDTO dto) {
        ApiRegistry api = apiRegistryService.update(id, dto);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", api);
        result.put("msg", "success");
        return ResponseEntity.ok(result);
    }

    /**
     * 列表查询
     */
    @GetMapping("/api-registry")
    public ResponseEntity<Map<String, Object>> list() {
        List<ApiRegistry> apis = apiRegistryService.findAll();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", apis);
        result.put("msg", "success");
        return ResponseEntity.ok(result);
    }
}
