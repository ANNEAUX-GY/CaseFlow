package com.caseflow.controller;

import com.caseflow.common.Result;
import com.caseflow.dto.TodoReorderRequest;
import com.caseflow.dto.TodoSaveRequest;
import com.caseflow.security.FullAccessOnly;
import com.caseflow.service.FileService;
import com.caseflow.service.TodoService;
import com.caseflow.vo.CaseFileVO;
import com.caseflow.vo.CaseTodoVO;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 案件待办（to do）：管理员维护清单，办案人逐项完成并上传佐证材料。
 *
 * <p>权限划分：
 * <ul>
 *   <li>维护（增 / 改 / 删 / 排序 / 撤销完成）—— 仅管理层，方法上挂 {@code @FullAccessOnly}</li>
 *   <li>勾选完成 —— 承办人或管理层，服务端强制校验已有佐证材料</li>
 *   <li>查看 —— 任意登录账号</li>
 * </ul>
 */
@RestController
@RequestMapping("/todos")
public class TodoController {

    @Resource
    private TodoService todoService;
    @Resource
    private FileService fileService;

    /** 某案件的待办清单（含佐证材料明细：上传人、上传时间） */
    @GetMapping("/case/{caseId}")
    public Result<List<CaseTodoVO>> listOfCase(@PathVariable Long caseId) {
        return Result.ok(todoService.listOf(caseId));
    }

    /** 新增待办（管理层） */
    @PostMapping("/case/{caseId}")
    @FullAccessOnly("维护案件待办")
    public Result<CaseTodoVO> add(@PathVariable Long caseId, @Validated @RequestBody TodoSaveRequest req) {
        return Result.ok(todoService.add(caseId, req.getContent()));
    }

    /** 编辑待办内容（管理层） */
    @PutMapping("/{todoId}")
    @FullAccessOnly("维护案件待办")
    public Result<CaseTodoVO> update(@PathVariable Long todoId, @Validated @RequestBody TodoSaveRequest req) {
        return Result.ok(todoService.update(todoId, req.getContent()));
    }

    /** 删除待办（管理层） */
    @DeleteMapping("/{todoId}")
    @FullAccessOnly("维护案件待办")
    public Result<Void> remove(@PathVariable Long todoId) {
        todoService.remove(todoId);
        return Result.ok();
    }

    /** 排序（管理层）：按 ids 顺序重排 */
    @PostMapping("/case/{caseId}/reorder")
    @FullAccessOnly("维护案件待办")
    public Result<List<CaseTodoVO>> reorder(@PathVariable Long caseId, @RequestBody TodoReorderRequest req) {
        return Result.ok(todoService.reorder(caseId, req.getIds()));
    }

    /** 勾选完成：无佐证材料会被拒绝 */
    @PostMapping("/{todoId}/done")
    public Result<CaseTodoVO> done(@PathVariable Long todoId, @RequestBody(required = false) TodoSaveRequest req) {
        return Result.ok(todoService.done(todoId, req == null ? null : req.getRemark()));
    }

    /** 撤销完成（管理层） */
    @PostMapping("/{todoId}/reopen")
    @FullAccessOnly("撤销待办完成")
    public Result<CaseTodoVO> reopen(@PathVariable Long todoId) {
        return Result.ok(todoService.reopen(todoId));
    }

    // ------------------------------------------------------------------
    // 佐证材料
    // ------------------------------------------------------------------

    /** 上传佐证材料（限定类型与大小，规则见 /todos/rules） */
    @PostMapping("/{todoId}/evidence")
    public Result<CaseFileVO> uploadEvidence(@PathVariable Long todoId,
                                             @RequestParam("file") MultipartFile file) {
        Long caseId = todoService.caseIdOf(todoId);
        return Result.ok(fileService.toVO(fileService.upload(file, caseId, todoId)));
    }

    /** 某条待办的佐证材料清单 */
    @GetMapping("/{todoId}/evidence")
    public Result<List<CaseFileVO>> evidenceOf(@PathVariable Long todoId) {
        return Result.ok(fileService.filesOfTodo(todoId));
    }

    /**
     * 佐证材料上传规则：允许的类型、大小上限、存储方式。
     * 前端在详情页直接展示，避免用户上传后才被拒绝。
     */
    @GetMapping("/rules")
    public Result<Map<String, Object>> rules() {
        Map<String, Object> m = new HashMap<>();
        m.put("allowedExt", FileService.evidenceExtSet());
        m.put("extHint", FileService.evidenceExtHint());
        m.put("maxBytes", FileService.EVIDENCE_MAX_BYTES);
        m.put("maxText", "20MB");
        m.put("storage", "服务端按「上传年月」分目录存放（data/uploads/yyyyMM/），"
                + "数据库只记录相对路径与元信息；同一份材料在案件详情中可下载，并标注上传人与上传时间。");
        return Result.ok(m);
    }

    // ------------------------------------------------------------------
    // 总览（管理员）
    // ------------------------------------------------------------------

    /** 各待办的完成状态与对应材料（跨案件）
     *  caseType 由统一类型选择器注入（2026-10），列表与汇总共用同一口径 */
    @GetMapping("/overview")
    @FullAccessOnly("查看待办总览")
    public Result<List<CaseTodoVO>> overview(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) Long caseId,
                                             @RequestParam(required = false) String caseType) {
        return Result.ok(todoService.overview(status, caseId, caseType));
    }

    /** 总览汇总数字 */
    @GetMapping("/overview/summary")
    @FullAccessOnly("查看待办总览")
    public Result<Map<String, Object>> summary(@RequestParam(required = false) String caseType) {
        return Result.ok(todoService.overviewSummary(caseType));
    }
}
