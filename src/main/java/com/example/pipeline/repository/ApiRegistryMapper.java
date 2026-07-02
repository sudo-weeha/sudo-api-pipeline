package com.example.pipeline.repository;

import com.example.pipeline.model.ApiRegistry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiRegistryMapper {

    List<ApiRegistry> findAll();

    ApiRegistry findById(@Param("id") Long id);

    ApiRegistry findByCode(@Param("apiCode") String apiCode);

    int insert(ApiRegistry api);

    int update(ApiRegistry api);

    int deleteById(@Param("id") Long id);
}
