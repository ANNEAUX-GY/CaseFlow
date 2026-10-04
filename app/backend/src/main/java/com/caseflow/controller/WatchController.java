package com.caseflow.controller;

import com.caseflow.common.PageResult;
import com.caseflow.common.Result;
import com.caseflow.dto.CaseQuery;
import com.caseflow.entity.CaseApproval;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseLeaderOpinion;
import com.caseflow.entity.CasePlan;
import com.caseflow.entity.CaseProgressComment;
import com.caseflow.flow.CaseFlowTemplate;
import com.caseflow.security.FullAccessOnly;
import com.caseflow.service.ApprovalService;
import com.caseflow.service.FlowService;
import com.caseflow.service.OpinionService;
import com.caseflow.service.PlanService;
import com.caseflow.service.ProgressCommentService;
import com.caseflow.service.WatchService;
import com.caseflow.vo.CaseVO;
import com.caseflow.vo.WatchBoardVO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 案件盯办：三子模块列表 / 看板 / 侦查计划 / 进度流转 / 强制措施 / 审批记录。
 * 审批与强制措施为管理层专属（@FullAccessOnly），计划与经办人流转在 Service 内按承办关系校验。
 */
@RestController
@RequestMapping("/watch")
public class WatchController {

    @Resource
    private WatchService watchService;
    @Resource
    private PlanService planService;
    @Resource
    private ApprovalService approvalService;
    @Resource
    private ProgressCommentService commentService;
    @Resource
    private OpinionService opinionService;
    @Resource
    private FlowService flowService;
    @Resource
    private com.caseflow.mapper.CaseInfoMapper caseMapper;

    /** 盯办列表：module=INITIAL/DETENTION/BAIL_RESIDENCE；支持嫌疑人姓名/身份证号检索 */
    @GetMapping("/cases")
    public Result<PageResult<CaseVO>> cases(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String module,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String caseType,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(required = false) String suspectName,
                                            @RequestParam(required = false) String suspectIdCard,
                                            @RequestParam(required = false) Long employeeId,
                                            @RequestParam(required = false) String investigationStatus) {
        CaseQuery q = new CaseQuery();
        q.setPage(page);
        q.setSize(size);
        q.setModule(module);
        q.setKeyword(keyword);
        q.setCaseType(caseType);
        q.setCategory(category);
        q.setSuspectName(suspectName);
        q.setSuspectIdCard(suspectIdCard);
        q.setEmployeeId(employeeId);
        q.setInvestigationStatus(investigationStatus);
        return Result.ok(watchService.page(q));
    }

    /** 盯办看板四组计数 */
    @GetMapping("/board")
    public Result<WatchBoardVO> board(@RequestParam(required = false) String caseType) {
        return Result.ok(watchService.board(caseType));
    }

    // ---------------- 侦查计划 ----------------

    @GetMapping("/cases/{caseId}/plans")
    public Result<List<CasePlan>> plans(@PathVariable Long caseId) {
        return Result.ok(planService.listOf(caseId));
    }

    @PostMapping("/cases/{caseId}/plans")
    public Result<CasePlan> addPlan(@PathVariable Long caseId, @RequestBody Map<String, Object> body) {
        return Result.ok(planService.add(caseId, str(body.get("content")), date(body.get("plannedAt"))));
    }

    @PutMapping("/plans/{id}")
    public Result<CasePlan> updatePlan(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.ok(planService.update(id, str(body.get("content")), date(body.get("plannedAt"))));
    }

    @PostMapping("/plans/{id}/done")
    public Result<CasePlan> donePlan(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(planService.done(id, body == null ? null : str(body.get("doneNote"))));
    }

    @PostMapping("/plans/{id}/cancel")
    public Result<CasePlan> cancelPlan(@PathVariable Long id) {
        return Result.ok(planService.cancel(id));
    }

    /** 撤销完成：阶段流程面板的任务勾选框需要双向切换，打错了能改回来 */
    @PostMapping("/plans/{id}/revert")
    public Result<CasePlan> revertPlan(@PathVariable Long id) {
        return Result.ok(planService.revert(id));
    }

    // ---------------- 侦查进度流转 / 强制措施 / 审批 ----------------

    /** 流转：START 开始侦查 / SUBMIT 提请审批 / APPROVE 同意侦查终结 / REJECT 退回补侦 */
    @PostMapping("/cases/{caseId}/transition")
    public Result<CaseInfo> transition(@PathVariable Long caseId, @RequestBody Map<String, Object> body) {
        return Result.ok(approvalService.transition(caseId, str(body.get("action")), str(body.get("comment"))));
    }

    /** 强制措施登记（管理层）；measure = NONE/DETENTION/BAIL/RESIDENCE，缺省期限自动推算 */
    @PostMapping("/cases/{caseId}/measure")
    @FullAccessOnly("登记强制措施")
    public Result<CaseInfo> measure(@PathVariable Long caseId, @RequestBody Map<String, Object> body) {
        return Result.ok(approvalService.measure(caseId, str(body.get("measure")),
                date(body.get("measureDate")), date(body.get("detainDeadline")), str(body.get("comment"))));
    }

    @GetMapping("/cases/{caseId}/approvals")
    public Result<List<CaseApproval>> approvals(@PathVariable Long caseId) {
        return Result.ok(approvalService.approvalsOf(caseId));
    }

    // ---------------- 办理进度批注（类似 Word 批注） ----------------
    // 写操作（增/改/删）= 管理层（管理员+领导），Service 内校验；
    // 查看 = 所有能看案件详情的人。

    /** 某案件全部批注（前端按 log_id 挂到办理进度时间线上） */
    @GetMapping("/cases/{caseId}/comments")
    public Result<List<CaseProgressComment>> comments(@PathVariable Long caseId) {
        return Result.ok(commentService.listOf(caseId));
    }

    /** 给某条办理进度（operation_log.id）添加批注 */
    @PostMapping("/logs/{logId}/comments")
    public Result<CaseProgressComment> addComment(@PathVariable Long logId,
                                                  @RequestBody Map<String, Object> body) {
        return Result.ok(commentService.add(logId, str(body.get("content"))));
    }

    @PutMapping("/comments/{id}")
    public Result<CaseProgressComment> updateComment(@PathVariable Long id,
                                                     @RequestBody Map<String, Object> body) {
        return Result.ok(commentService.update(id, str(body.get("content"))));
    }

    @DeleteMapping("/comments/{id}")
    public Result<Void> removeComment(@PathVariable Long id) {
        commentService.remove(id);
        return Result.ok(null);
    }

    // ---------------- 阶段→环节→任务 流程流转 ----------------
    // 定义见 com.caseflow.flow.CaseFlowTemplate；流转 = 管理层确认。

    /** 某案件当前阶段的流程视图：环节顺序、每环节任务、进度、可选流转分支 */
    @GetMapping("/cases/{caseId}/flow")
    public Result<FlowService.FlowView> flow(@PathVariable Long caseId) {
        return Result.ok(flowService.viewOf(caseId));
    }

    /** 仅取进度（列表页进度条用，避免每行都拉全量流程） */
    @GetMapping("/cases/{caseId}/flow/progress")
    public Result<FlowService.Progress> flowProgress(@PathVariable Long caseId) {
        return Result.ok(flowService.progressOf(caseId));
    }

    /** 阶段流转（管理层确认）：body.action = DETAIN/BAIL/RELEASE/ARREST/PUNISH/CLOSE */
    @PostMapping("/cases/{caseId}/flow/transfer")
    public Result<Void> flowTransfer(@PathVariable Long caseId,
                                      @RequestBody Map<String, Object> body) {
        flowService.transfer(caseId, str(body.get("action")));
        return Result.ok();
    }

    /** 补全当前阶段的标准任务（取保流程细化后用；幂等，不重复生成） */
    @PostMapping("/cases/{caseId}/flow/seed")
    public Result<Integer> flowSeed(@PathVariable Long caseId) {
        CaseInfo c = caseMapper.selectById(caseId);
        String stage = c == null ? CaseFlowTemplate.STAGE_INITIAL
                : flowService.stageOf(c);
        return Result.ok(flowService.generateStageTasks(caseId, stage));
    }

    // ---------------- 领导意见与落实反馈 ----------------
    // 提出 = 管理层；反馈（完成/进行中/未完成 + 说明）= 本案现职承办人（办案人）。

    /** 某案件全部领导意见（含各条最新反馈） */
    @GetMapping("/cases/{caseId}/opinions")
    public Result<List<CaseLeaderOpinion>> opinions(@PathVariable Long caseId) {
        return Result.ok(opinionService.listOf(caseId));
    }

    /** 提出意见（管理层，可多条）。可带截止时间与重要性（缺省 C） */
    @PostMapping("/cases/{caseId}/opinions")
    public Result<CaseLeaderOpinion> addOpinion(@PathVariable Long caseId,
                                                @RequestBody Map<String, Object> body) {
        return Result.ok(opinionService.add(caseId, str(body.get("content")),
                str(body.get("deadline")), str(body.get("importance"))));
    }

    /**
     * 拖拽排序（管理层）：按新顺序重排位次。
     *
     * <p>body.opinionIds = 拖拽后的 opinionId 数组（完整、有序）。
     * 列表显示的序号由前端按下标实时算，所以这里只落库顺序。
     */
    @PostMapping("/cases/{caseId}/opinions/reorder")
    public Result<Void> reorderOpinions(@PathVariable Long caseId,
                                         @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) body.get("opinionIds");
        List<Long> ids = new ArrayList<>();
        if (raw != null) {
            for (Object o : raw) {
                if (o != null) {
                    ids.add(Long.valueOf(String.valueOf(o)));
                }
            }
        }
        opinionService.reorder(caseId, ids);
        return Result.ok();
    }

    /** 修改意见的截止时间与重要性（管理层）；传空串即清空截止时间 */
    @PostMapping("/opinions/{id}/meta")
    public Result<CaseLeaderOpinion> updateOpinionMeta(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body) {
        return Result.ok(opinionService.updateMeta(id,
                str(body.get("deadline")), str(body.get("importance"))));
    }

    /** 办案人对某条意见反馈落实情况；note 含结构化上传声明句（替代佐证材料上传） */
    @PostMapping("/opinions/{id}/feedback")
    public Result<CaseLeaderOpinion> feedbackOpinion(@PathVariable Long id,
                                                     @RequestBody Map<String, Object> body) {
        return Result.ok(opinionService.feedback(id, str(body.get("status")), str(body.get("note"))));
    }

    // ------------------------------------------------------------------

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 前端传 "yyyy-MM-dd HH:mm:ss" 或 "yyyy-MM-dd" 均可 */
    private LocalDateTime date(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        String s = String.valueOf(o).trim();
        try {
            if (s.length() == 10) {
                return LocalDateTime.parse(s + "T00:00:00");
            }
            return LocalDateTime.parse(s.replace(" ", "T"));
        } catch (Exception e) {
            return null;
        }
    }
}
