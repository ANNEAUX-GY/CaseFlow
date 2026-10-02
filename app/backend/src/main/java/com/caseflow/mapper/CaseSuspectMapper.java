package com.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caseflow.entity.CaseSuspect;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CaseSuspectMapper extends BaseMapper<CaseSuspect> {

    /**
     * 带显式主键插入——撤回案件操作时要把嫌疑人还原成**原来的 id**，
     * 与 CaseInfoMapper.insertWithId 同理（IdType.AUTO 在 insert 时会省掉主键列）。
     */
    @Insert("INSERT INTO case_suspect (id, case_id, name, gender, id_card, phone, address, remark, "
            + "created_by, created_at, updated_at) VALUES "
            + "(#{id}, #{caseId}, #{name}, #{gender}, #{idCard}, #{phone}, #{address}, #{remark}, "
            + "#{createdBy}, #{createdAt}, #{updatedAt})")
    int insertWithId(CaseSuspect entity);
}
