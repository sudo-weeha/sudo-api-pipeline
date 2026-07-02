package com.example.pipeline.repository;

import com.example.pipeline.model.PipelineExecLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PipelineExecLogMapper {

    int insert(PipelineExecLog log);

    List<PipelineExecLog> findByPipelineCode(@Param("pipelineCode") String pipelineCode);

    List<PipelineExecLog> findAll();
}
