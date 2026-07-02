package com.example.pipeline.service;

import com.example.pipeline.dto.ApiRegistryDTO;
import com.example.pipeline.model.ApiRegistry;
import com.example.pipeline.repository.ApiRegistryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

@Service
public class ApiRegistryService {

    @Resource
    private ApiRegistryMapper apiRegistryMapper;

    public List<ApiRegistry> findAll() {
        return apiRegistryMapper.findAll();
    }

    public ApiRegistry findById(Long id) {
        return apiRegistryMapper.findById(id);
    }

    public ApiRegistry findByCode(String apiCode) {
        return apiRegistryMapper.findByCode(apiCode);
    }

    @Transactional
    public ApiRegistry create(ApiRegistryDTO dto) {
        ApiRegistry api = new ApiRegistry();
        api.setApiName(dto.getApiName());
        api.setApiCode(dto.getApiCode());
        api.setRequestMethod(dto.getRequestMethod());
        api.setRequestUrl(dto.getRequestUrl());
        api.setRequestHeaders(dto.getRequestHeaders());
        api.setTimeoutMs(dto.getTimeoutMs() != null ? dto.getTimeoutMs() : 5000);
        api.setRetryCount(dto.getRetryCount() != null ? dto.getRetryCount() : 0);
        api.setCacheTtlMin(dto.getCacheTtlMin() != null ? dto.getCacheTtlMin() : 30);
        api.setDescription(dto.getDescription());
        api.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
        apiRegistryMapper.insert(api);
        return api;
    }

    @Transactional
    public ApiRegistry update(Long id, ApiRegistryDTO dto) {
        ApiRegistry api = apiRegistryMapper.findById(id);
        if (api == null) {
            throw new RuntimeException("API 不存在: " + id);
        }
        api.setApiName(dto.getApiName());
        api.setRequestMethod(dto.getRequestMethod());
        api.setRequestUrl(dto.getRequestUrl());
        api.setRequestHeaders(dto.getRequestHeaders());
        api.setTimeoutMs(dto.getTimeoutMs() != null ? dto.getTimeoutMs() : 5000);
        api.setRetryCount(dto.getRetryCount() != null ? dto.getRetryCount() : 0);
        api.setCacheTtlMin(dto.getCacheTtlMin() != null ? dto.getCacheTtlMin() : 30);
        api.setDescription(dto.getDescription());
        api.setStatus(dto.getStatus() != null ? dto.getStatus() : 0);
        apiRegistryMapper.update(api);
        return api;
    }

    @Transactional
    public void delete(Long id) {
        apiRegistryMapper.deleteById(id);
    }
}
