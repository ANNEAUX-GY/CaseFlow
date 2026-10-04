package com.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caseflow.entity.CaseQuestion;
import org.apache.ibatis.annotations.Mapper;

/** 疑问问答（见 {@link CaseQuestion} 说明：独立于待办与任务的沟通记录） */
@Mapper
public interface CaseQuestionMapper extends BaseMapper<CaseQuestion> {
}
