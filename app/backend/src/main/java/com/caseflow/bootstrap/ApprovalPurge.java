package com.caseflow.bootstrap;

import com.caseflow.service.ApprovalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 启动时清一遍悬空的待审批申请（2026-10-24）。
 *
 * <p>悬空 = 强制措施变更申请指向一个已经不存在的案件。
 * 在「删案件顺手清 PENDING」这条规则上线之前产生的记录，工作台会显示
 * 「待审批 N 条」，点开却是一行"（案件已删除）"，既批不了也删不掉。
 *
 * <p>只清 PENDING：已同意/已退回的是审批留痕，属于办案历史，不能删。
 * 幂等，可重复执行。
 */
@Slf4j
@Component
public class ApprovalPurge implements ApplicationRunner {

    @Resource
    private ApprovalService approvalService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int n = approvalService.purgeOrphanPending();
            if (n > 0) {
                log.info("[ApprovalPurge] 已清掉指向已删案件的待审批申请 {} 条", n);
            }
        } catch (Exception e) {
            // 收尾活，失败不挡启动
            log.warn("[ApprovalPurge] 悬空待审批清理跳过：{}", e.getMessage());
        }
    }
}
