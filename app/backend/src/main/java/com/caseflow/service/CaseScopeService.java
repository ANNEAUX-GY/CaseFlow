package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseInfo;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.security.AuthContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「当前登录人名下案件」的唯一实现（2026-10-13 抽离）。
 *
 * <p><b>为什么抽出来</b>：这个口径被 {@link CaseService#myVisibleCaseIds()}、
 * {@link OpinionService}（意见收件箱）、{@link StaffTodoService}（民警待办列表）同时用到。
 * 而「指派 / 建案时一并录入待办」要求 {@code CaseService} 能反过来调用
 * {@link OpinionService}（提意见即派待办）——若 OpinionService 仍注入 CaseService，
 * 两个服务就成环。本类只依赖两个 Mapper，是这条依赖链的**末端**：
 * <pre>
 *   CaseService ─┬─> OpinionService ─> CaseScopeService
 *                └─> StaffTodoService ─┘
 * </pre>
 *
 * <p>口径本身没变，仍然是：已绑定员工账号 → 该员工 ACTIVE 指派的案件；
 * 未绑定（存量 boss / test1）→ 本人创建的案件。
 * {@code CaseService.myVisibleCaseIds()} 作为**对外唯一门面**保留，
 * 新增功能一律调它，别直接注入本类。
 */
@Service
public class CaseScopeService {

    @Resource
    private CaseAssigneeMapper assigneeMapper;
    @Resource
    private CaseInfoMapper caseMapper;

    /**
     * 当前登录人可见的案件 id 集合；未登录返回空集合（不是 null，调用方无需判空）。
     */
    public Set<Long> myVisibleCaseIds() {
        com.caseflow.security.CurrentUser u = AuthContext.get();
        if (u == null) {
            return Collections.emptySet();
        }
        Long empId = u.getEmployeeId();
        if (empId != null) {
            return assigneeMapper.selectList(new LambdaQueryWrapper<CaseAssignee>()
                            .eq(CaseAssignee::getEmployeeId, empId)
                            .eq(CaseAssignee::getStatus, "ACTIVE"))
                    .stream().map(CaseAssignee::getCaseId).collect(Collectors.toSet());
        }
        return caseMapper.selectList(new LambdaQueryWrapper<CaseInfo>()
                        .eq(CaseInfo::getCreatedBy, u.getUserId()))
                .stream().map(CaseInfo::getId).collect(Collectors.toSet());
    }
}
