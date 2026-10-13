package com.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caseflow.entity.CaseTodo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CaseTodoMapper extends BaseMapper<CaseTodo> {

    /**
     * 还原快照时保留原主键（IdType.AUTO 的 insert 会省主键列）。
     *
     * <p><b>2026-10-13 修正</b>：原先漏了 opinion_id / parent_id / urgency /
     * importance / dept_source / deadline 六列。
     * 后果是撤回一次带待办的操作后，意见派生出来的待办会退化成"无来源的孤儿待办"——
     * opinion_id 变 NULL 就再也对不回它的领导意见，而且派生待办最重要的
     * 截止时间与重要程度会被静默清空。快照的意义是"原样写回"，少一列就不成立。
     */
    @Insert("INSERT INTO case_todo (id, case_id, opinion_id, parent_id, content, status, sort, "
            + "urgency, importance, dept_source, deadline, done_at, done_by, flow_stage, remark, "
            + "created_by, created_at, updated_at) VALUES "
            + "(#{id}, #{caseId}, #{opinionId}, #{parentId}, #{content}, #{status}, #{sort}, "
            + "#{urgency}, #{importance}, #{deptSource}, #{deadline}, #{doneAt}, #{doneBy}, "
            + "#{flowStage}, #{remark}, #{createdBy}, #{createdAt}, #{updatedAt})")
    int insertWithId(CaseTodo entity);
}
