package com.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caseflow.entity.CaseTodoFeedback;
import org.apache.ibatis.annotations.Mapper;

/** 待办反馈记录（累积式，见 {@link CaseTodoFeedback} 说明） */
@Mapper
public interface CaseTodoFeedbackMapper extends BaseMapper<CaseTodoFeedback> {
}
