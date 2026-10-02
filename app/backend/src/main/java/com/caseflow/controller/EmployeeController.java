package com.caseflow.controller;

import com.caseflow.common.Result;
import com.caseflow.dto.EmployeeSaveRequest;
import com.caseflow.security.FullAccessOnly;
import com.caseflow.service.EmployeeService;
import com.caseflow.vo.EmployeeVO;
import com.caseflow.vo.ImportResultVO;
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
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * 员工图谱。
 */
@RestController
@RequestMapping("/employees")
public class EmployeeController {

    @Resource
    private EmployeeService employeeService;

    /** 组织树；keyword 非空时过滤出命中分支 */
    @GetMapping("/tree")
    public Result<List<EmployeeVO>> tree(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) Integer status) {
        return Result.ok(employeeService.tree(keyword, status));
    }

    /** 扁平检索：指派抽屉使用，返回带完整链路的候选名单 */
    @GetMapping("/search")
    public Result<List<EmployeeVO>> search(@RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer limit) {
        return Result.ok(employeeService.search(keyword, limit == null ? 50 : limit));
    }

    @GetMapping("/{id}")
    public Result<EmployeeVO> detail(@PathVariable Long id) {
        return Result.ok(employeeService.detail(id));
    }

    @PostMapping
    @FullAccessOnly("新增员工")
    public Result<EmployeeVO> create(@Validated @RequestBody EmployeeSaveRequest req) {
        return Result.ok(employeeService.save(req));
    }

    @PutMapping("/{id}")
    @FullAccessOnly("编辑员工")
    public Result<EmployeeVO> update(@PathVariable Long id, @Validated @RequestBody EmployeeSaveRequest req) {
        req.setId(id);
        return Result.ok(employeeService.save(req));
    }

    @DeleteMapping("/{id}")
    @FullAccessOnly("删除员工")
    public Result<Void> delete(@PathVariable Long id) {
        employeeService.delete(id);
        return Result.ok();
    }

    /** Excel 导入员工图谱 */
    @PostMapping("/import")
    @FullAccessOnly("导入员工图谱")
    public Result<ImportResultVO> importExcel(@RequestParam("file") MultipartFile file) {
        return Result.ok(employeeService.importExcel(file));
    }

    /** 下载导入模板 */
    @GetMapping("/template")
    public void template(HttpServletResponse response) throws IOException {
        employeeService.writeTemplate(response);
    }
}
