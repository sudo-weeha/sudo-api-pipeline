package com.example.pipeline.repository;

import com.example.pipeline.model.PipelineDef;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PipelineDefMapper {

    List<PipelineDef> findAll();

    PipelineDef findById(@Param("id") Long id);

    PipelineDef findByCode(@Param("pipelineCode") String pipelineCode);

    int insert(PipelineDef pipelineDef);

    int update(PipelineDef pipelineDef);

    int deleteById(@Param("id") Long id);
}
