package com.example.pipeline.http;

import com.example.pipeline.model.ApiRegistry;
import com.example.pipeline.repository.ApiRegistryMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class ApiInvokerImpl implements ApiInvoker {

    private static final Logger log = LoggerFactory.getLogger(ApiInvokerImpl.class);

    @Resource
    private ApiRegistryMapper apiRegistryMapper;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ApiInvokeResult invoke(String apiCode, Map<String, Object> resolvedParams) {
        // 1. 查询 API 注册信息
        ApiRegistry api = apiRegistryMapper.findByCode(apiCode);
        if (api == null || api.getStatus() == 0) {
            throw new RuntimeException("API 不存在或已禁用: " + apiCode);
        }

        // 2. 构建缓存 key
        String cacheKey = buildCacheKey(apiCode, resolvedParams);

        // 3. 查 Redis 缓存
        Object cached = null;
        try {
            cached = redisTemplate.opsForValue().get(cacheKey);
        } catch (RuntimeException e) {
            log.warn("读取 Redis 缓存失败，跳过缓存: {}", e.getMessage());
        }
        if (cached != null) {
            log.debug("缓存命中: {}", cacheKey);
            return new ApiInvokeResult(200, cached);
        }

        // 4. 发起 HTTP 调用（支持重试）
        ApiInvokeResult result = invokeWithRetry(api, resolvedParams);

        // 5. 仅成功响应写缓存
        if (result.getHttpStatus() >= 200 && result.getHttpStatus() < 300) {
            int ttl = api.getCacheTtlMin() != null ? api.getCacheTtlMin() : 30;
            if (ttl > 0) {
                try {
                    redisTemplate.opsForValue().set(cacheKey, result.getResponseBody(), ttl, TimeUnit.MINUTES);
                } catch (RuntimeException e) {
                    log.warn("写入 Redis 缓存失败，忽略缓存写入: {}", e.getMessage());
                }
            }
        }

        return result;
    }

    private ApiInvokeResult invokeWithRetry(ApiRegistry api, Map<String, Object> resolvedParams) {
        int maxRetries = api.getRetryCount() != null ? api.getRetryCount() : 0;
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return doInvoke(api, resolvedParams);
            } catch (ResourceAccessException e) {
                // 网络不可达/超时类异常，支持重试
                lastException = e;
                log.warn("API 调用网络异常 (attempt {}/{}): {} {}", attempt + 1, maxRetries + 1, api.getApiCode(), e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(500L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (RestClientException e) {
                // 其他 RestClient 异常（序列化等），不重试
                throw new RuntimeException("API 调用失败: " + api.getApiCode(), e);
            }
        }

        // 重试耗尽，判断是否为超时
        boolean isTimeout = false;
        if (lastException != null) {
            Throwable cause = lastException;
            while (cause != null) {
                if (cause instanceof SocketTimeoutException) {
                    isTimeout = true;
                    break;
                }
                cause = cause.getCause();
            }
        }
        int status = isTimeout ? 408 : 500;
        String msg = isTimeout ? "API 调用超时: " + api.getApiCode() : "API 调用失败（已重试 " + maxRetries + " 次）: " + api.getApiCode();
        log.error(msg, lastException);
        return new ApiInvokeResult(status, Map.of("error", msg));
    }

    private ApiInvokeResult doInvoke(ApiRegistry api, Map<String, Object> resolvedParams) {
        HttpMethod method = HttpMethod.resolve(api.getRequestMethod().toUpperCase());
        if (method == null) {
            method = HttpMethod.POST;
        }

        String url = api.getRequestUrl();

        // 构建请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (api.getRequestHeaders() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> headerMap = objectMapper.readValue(api.getRequestHeaders(), Map.class);
                headerMap.forEach(headers::set);
            } catch (JsonProcessingException e) {
                log.warn("解析固定请求头失败: {}", api.getRequestHeaders());
            }
        }

        // 构建请求体
        String body;
        try {
            body = objectMapper.writeValueAsString(resolvedParams);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("参数序列化失败", e);
        }

        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        log.debug("HTTP 调用: {} {} Body: {}", method, url, body);

        // 使用 API 专属超时配置创建 RestTemplate
        RestTemplate callTemplate = restTemplate;
        int timeoutMs = api.getTimeoutMs() != null ? api.getTimeoutMs() : 5000;
        if (timeoutMs != 5000) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Math.min(timeoutMs, 5000));
            factory.setReadTimeout(timeoutMs);
            callTemplate = new RestTemplate(factory);
            callTemplate.setErrorHandler(restTemplate.getErrorHandler());
        }

        // 发起请求（RestTemplate 不会在 4xx/5xx 时抛异常）
        ResponseEntity<Object> response = callTemplate.exchange(url, method, entity, Object.class);

        int httpStatus = response.getStatusCodeValue();
        log.debug("HTTP 响应: {} {}", httpStatus, response.getBody());

        return new ApiInvokeResult(httpStatus, response.getBody());
    }

    private String buildCacheKey(String apiCode, Map<String, Object> params) {
        try {
            String paramsJson = objectMapper.writeValueAsString(params);
            String hash = sha256(paramsJson);
            return apiCode + ":" + hash;
        } catch (JsonProcessingException e) {
            return apiCode + ":" + params.hashCode();
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }
}
