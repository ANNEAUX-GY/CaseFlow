package com.caseflow.controller;

import com.caseflow.common.Result;
import com.caseflow.dto.CategorySaveRequest;
import com.caseflow.security.FullAccessOnly;
import com.caseflow.service.CategoryService;
import com.caseflow.vo.CategoryVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * 案件类别（小类）字典。读取对全部登录用户开放；增删改仅管理权限。
 */
@RestController
@RequestMapping("/case-categories")
public class CategoryController {

    @Resource
    private CategoryService categoryService;

    /** 级联树：前端「案件分类」选择器据此构建大类 → 小类 */
    @GetMapping("/tree")
    public Result<List<CategoryVO.Node>> tree() {
        return Result.ok(categoryService.tree());
    }

    /** 管理列表（平铺，含 id） */
    @GetMapping
    @FullAccessOnly("案件类别维护")
    public Result<List<CategoryVO.Item>> list() {
        return Result.ok(categoryService.list());
    }

    @PostMapping
    @FullAccessOnly("案件类别维护")
    public Result<CategoryVO.Item> add(@RequestBody CategorySaveRequest req) {
        return Result.ok(categoryService.add(req.getCaseType(), req.getName()));
    }

    @PutMapping("/{id}")
    @FullAccessOnly("案件类别维护")
    public Result<CategoryVO.Item> update(@PathVariable Long id, @RequestBody CategorySaveRequest req) {
        return Result.ok(categoryService.update(id, req.getCaseType(), req.getName(), req.getSort()));
    }

    @DeleteMapping("/{id}")
    @FullAccessOnly("案件类别维护")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.ok();
    }
}
