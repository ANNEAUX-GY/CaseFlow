package com.caseflow.support;

import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseFile;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CasePlan;
import com.caseflow.entity.CaseSuspect;
import com.caseflow.entity.CaseTodo;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 案件完整快照：撤回时按它原样回写。
 *
 * <p>{@code exists=false} 表示「这个案件当时不存在」——新建操作的 before、删除操作的 after
 * 都是这种形态，回写即等价于删除，因此撤回逻辑可以统一成「把 before 快照写回去」。
 */
@Data
public class CaseSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private Boolean exists;
    private CaseInfo info;
    private List<CaseAssignee> assignees;
    private List<CaseFile> files;
    private List<CaseSuspect> suspects;
    private List<CasePlan> plans;
    /** 案件待办（to do）。佐证材料不在此列——它是 case_file，随 files 一并快照。 */
    private List<CaseTodo> todos;
}
