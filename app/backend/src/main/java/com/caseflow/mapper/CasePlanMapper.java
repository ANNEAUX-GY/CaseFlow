package com.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caseflow.entity.CasePlan;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CasePlanMapper extends BaseMapper<CasePlan> {

    /** 还原快照时保留原主键（IdType.AUTO 的 insert 会省主键列） */
    @Insert("INSERT INTO case_plan (id, case_id, content, planned_at, status, done_at, done_note, sort, created_by, created_at, updated_at) VALUES "
            + "(#{id}, #{caseId}, #{content}, #{plannedAt}, #{status}, #{doneAt}, #{doneNote}, #{sort}, #{createdBy}, #{createdAt}, #{updatedAt})")
    int insertWithId(CasePlan entity);
}
