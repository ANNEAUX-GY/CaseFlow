package com.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caseflow.entity.CaseInfo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CaseInfoMapper extends BaseMapper<CaseInfo> {

    /**
     * 带显式主键插入——撤回「删除案件」时必须把案件还原成**原来的 id**，
     * 否则历史操作日志里的 target_id 会指向一个不存在的案件。
     * MyBatis-Plus 的 {@code IdType.AUTO} 在 insert 时会省掉主键列，所以这里手写一条。
     */
    @Insert("INSERT INTO case_info (id, case_no, name, source_type, source_file_id, case_type, category, "
            + "filing_no, mediation_no, case_measure, measure_date, detain_deadline, investigation_status, "
            + "description, priority, deadline, status, remark, created_by, created_at, updated_at) VALUES "
            + "(#{id}, #{caseNo}, #{name}, #{sourceType}, #{sourceFileId}, #{caseType}, #{category}, "
            + "#{filingNo}, #{mediationNo}, #{caseMeasure}, #{measureDate}, #{detainDeadline}, #{investigationStatus}, "
            + "#{description}, #{priority}, #{deadline}, #{status}, #{remark}, #{createdBy}, #{createdAt}, #{updatedAt})")
    int insertWithId(CaseInfo entity);
}
