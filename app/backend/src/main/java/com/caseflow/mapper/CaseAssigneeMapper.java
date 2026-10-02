package com.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caseflow.entity.CaseAssignee;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CaseAssigneeMapper extends BaseMapper<CaseAssignee> {

    /** 带显式主键插入，理由同 {@link CaseInfoMapper#insertWithId}：撤回要还原成一模一样的行 */
    @Insert("INSERT INTO case_assignee (id, case_id, employee_id, assign_role, note, status, assigned_by, "
            + "assigned_at, closed_at) VALUES "
            + "(#{id}, #{caseId}, #{employeeId}, #{assignRole}, #{note}, #{status}, #{assignedBy}, "
            + "#{assignedAt}, #{closedAt})")
    int insertWithId(CaseAssignee entity);
}
