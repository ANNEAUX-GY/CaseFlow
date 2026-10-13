package com.caseflow.support;

import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseFile;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseLeaderOpinion;
import com.caseflow.entity.CasePlan;
import com.caseflow.entity.CaseSuspect;
import com.caseflow.entity.CaseTimeNode;
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
    /** 重要时间节点（2026-10-13）：节点增删改随快照一并还原 */
    private List<CaseTimeNode> timeNodes;
    private List<CasePlan> plans;
    /** 案件待办（to do）。佐证材料不在此列——它是 case_file，随 files 一并快照。 */
    private List<CaseTodo> todos;
    /**
     * 领导意见（2026-10-13 纳入）。
     *
     * <p><b>为什么必须一起快照</b>：待办现在是「意见派生」的（{@code case_todo.opinion_id}），
     * 指派 / 建案时录入的待办同样走意见链路。若只快照 todos 不快照 opinions，
     * 撤回建案会删掉 todo 却把 opinion 留成孤儿——
     * 而 {@code StaffTodoService.backfillFromOpinions()} 可能把孤儿意见重新派生出待办，
     * 表现为「案件已经撤了，待办却又冒出来」。
     */
    private List<CaseLeaderOpinion> opinions;
}
