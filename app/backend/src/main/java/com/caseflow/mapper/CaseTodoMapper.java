package com.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caseflow.entity.CaseTodo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CaseTodoMapper extends BaseMapper<CaseTodo> {

    /** 还原快照时保留原主键（IdType.AUTO 的 insert 会省主键列） */
    @Insert("INSERT INTO case_todo (id, case_id, content, status, sort, done_at, done_by, remark, created_by, created_at, updated_at) VALUES "
            + "(#{id}, #{caseId}, #{content}, #{status}, #{sort}, #{doneAt}, #{doneBy}, #{remark}, #{createdBy}, #{createdAt}, #{updatedAt})")
    int insertWithId(CaseTodo entity);
}
