package com.caseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caseflow.entity.CaseTimeNode;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CaseTimeNodeMapper extends BaseMapper<CaseTimeNode> {

    /**
     * 带显式主键插入——撤回案件操作时要把节点还原成**原来的 id**，
     * 与 {@code CaseSuspectMapper.insertWithId} 同理
     * （{@code IdType.AUTO} 在 insert 时会省掉主键列）。
     */
    @Insert("INSERT INTO case_time_node (id, case_id, node_key, node_name, node_time, "
            + "remind_days, is_primary, sort, remark, created_by, created_at, updated_at) VALUES "
            + "(#{id}, #{caseId}, #{nodeKey}, #{nodeName}, #{nodeTime}, "
            + "#{remindDays}, #{isPrimary}, #{sort}, #{remark}, "
            + "#{createdBy}, #{createdAt}, #{updatedAt})")
    int insertWithId(CaseTimeNode entity);
}