package com.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caseflow.entity.CaseFile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CaseFileMapper extends BaseMapper<CaseFile> {
}
