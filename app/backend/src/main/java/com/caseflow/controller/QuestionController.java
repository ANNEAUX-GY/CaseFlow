package com.caseflow.controller;

import com.caseflow.common.Result;
import com.caseflow.entity.CaseQuestion;
import com.caseflow.service.QuestionService;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 疑问问答（2026-10-04）：员工提问、管理层回答。
 * 独立于待办与任务——不派生待办、不进完成规则。
 */
@RestController
@RequestMapping("/questions")
public class QuestionController {

    @Resource
    private QuestionService questionService;

    /** 某案件的问答列表（时间正序） */
    @GetMapping("/case/{caseId}")
    public Result<List<CaseQuestion>> listOfCase(@PathVariable Long caseId) {
        return Result.ok(questionService.listOfCase(caseId));
    }

    /** 提问（承办人或管理层）；todoId 可空，仅记录上下文 */
    @PostMapping
    public Result<CaseQuestion> ask(@RequestBody Map<String, Object> body) {
        Long caseId = body.get("caseId") == null ? null : Long.valueOf(String.valueOf(body.get("caseId")));
        Long todoId = body.get("todoId") == null ? null : Long.valueOf(String.valueOf(body.get("todoId")));
        String content = body.get("content") == null ? null : String.valueOf(body.get("content"));
        return Result.ok(questionService.ask(caseId, todoId, content));
    }

    /** 回答（仅管理层） */
    @PostMapping("/{id}/answer")
    public Result<CaseQuestion> answer(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String content = body.get("content") == null ? null : String.valueOf(body.get("content"));
        return Result.ok(questionService.answer(id, content));
    }
}
