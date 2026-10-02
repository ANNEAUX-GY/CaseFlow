package com.caseflow.controller;

import com.caseflow.common.Result;
import com.caseflow.entity.CaseFile;
import com.caseflow.service.FileService;
import com.caseflow.vo.CaseFileVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 附件：上传（PDF/Word/Excel）、下载、删除。
 */
@RestController
@RequestMapping("/files")
public class FileController {

    @Resource
    private FileService fileService;

    @PostMapping("/upload")
    public Result<CaseFileVO> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam(required = false) Long caseId) {
        CaseFile saved = fileService.upload(file, caseId);
        return Result.ok(fileService.toVO(saved));
    }

    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id, HttpServletResponse response) throws IOException {
        CaseFile f = fileService.get(id);
        org.springframework.core.io.Resource resource = fileService.load(f);
        String name = URLEncoder.encode(f.getFileName(), StandardCharsets.UTF_8.name()).replaceAll("\\+", "%20");
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"; filename*=utf-8''" + name);
        try (java.io.InputStream in = resource.getInputStream();
             java.io.OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        fileService.delete(id);
        return Result.ok();
    }

    @GetMapping("/case/{caseId}")
    public Result<java.util.List<CaseFileVO>> listOfCase(@PathVariable Long caseId) {
        return Result.ok(fileService.filesOf(caseId));
    }
}
