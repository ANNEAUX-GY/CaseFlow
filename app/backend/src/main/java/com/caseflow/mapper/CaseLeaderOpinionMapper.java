package com.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caseflow.entity.CaseLeaderOpinion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CaseLeaderOpinionMapper extends BaseMapper<CaseLeaderOpinion> {

    /**
     * 带显式主键插入——撤回案件操作时要把意见还原成<b>原来的 id</b>，
     * 否则 {@code case_todo.opinion_id} 会指向一条不存在（或被别人占用的）意见，
     * 派生待办就失去了来源。
     */
    @Insert("INSERT INTO case_leader_opinion (id, case_id, content, creator_id, creator_name, "
            + "created_at, feedback_status, feedback_note, feedback_by, feedback_by_name, feedback_at, "
            + "sort_order, deadline, importance) VALUES "
            + "(#{id}, #{caseId}, #{content}, #{creatorId}, #{creatorName}, "
            + "#{createdAt}, #{feedbackStatus}, #{feedbackNote}, #{feedbackBy}, #{feedbackByName}, #{feedbackAt}, "
            + "#{sortOrder}, #{deadline}, #{importance})")
    int insertWithId(CaseLeaderOpinion entity);
}
