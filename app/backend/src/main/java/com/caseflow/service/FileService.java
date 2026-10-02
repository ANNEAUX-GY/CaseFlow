package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseFile;
import com.caseflow.entity.CaseTodo;
import com.caseflow.entity.SysUser;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseFileMapper;
import com.caseflow.mapper.CaseTodoMapper;
import com.caseflow.mapper.SysUserMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.LogService;
import com.caseflow.vo.CaseFileVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 案件附件（PDF / Word / Excel）上传与下载。
 */
@Service
public class FileService {

    @Value("${caseflow.upload-dir:./data/uploads}")
    private String uploadDir;

    @Resource
    private CaseFileMapper fileMapper;
    @Resource
    private CaseTodoMapper todoMapper;

    @Resource
    private SysUserMapper userMapper;

    @Resource
    private LogService logService;

    private Path root;

    @PostConstruct
    public void init() {
        root = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            throw new IllegalStateException("上传目录创建失败：" + root, e);
        }
    }

    /**
     * 佐证材料允许的扩展名。
     * 现场回传的凭据以照片、扫描件、文档为主，另放开压缩包便于多张照片打包上传。
     */
    private static final Map<String, String> EVIDENCE_EXT = new LinkedHashMap<>();

    static {
        for (String e : new String[]{"jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif"}) {
            EVIDENCE_EXT.put(e, "图片");
        }
        for (String e : new String[]{"pdf", "doc", "docx", "wps", "ofd"}) {
            EVIDENCE_EXT.put(e, "文档");
        }
        for (String e : new String[]{"xls", "xlsx", "et", "csv"}) {
            EVIDENCE_EXT.put(e, "表格");
        }
        for (String e : new String[]{"ppt", "pptx", "dps"}) {
            EVIDENCE_EXT.put(e, "演示");
        }
        for (String e : new String[]{"txt", "zip", "rar", "7z"}) {
            EVIDENCE_EXT.put(e, "其他");
        }
    }

    /** 单个佐证材料大小上限：20MB（案件材料仍走 multipart 的 50MB 上限） */
    public static final long EVIDENCE_MAX_BYTES = 20L * 1024 * 1024;

    private static final Map<String, String> TYPE_MAP = new HashMap<>();

    static {
        TYPE_MAP.put("pdf", "PDF");
        TYPE_MAP.put("doc", "WORD");
        TYPE_MAP.put("docx", "WORD");
        TYPE_MAP.put("wps", "WORD");
        TYPE_MAP.put("xls", "EXCEL");
        TYPE_MAP.put("xlsx", "EXCEL");
        TYPE_MAP.put("et", "EXCEL");
        TYPE_MAP.put("csv", "EXCEL");
    }

    public static String detectSourceType(String fileName) {
        if (fileName == null) {
            return "MANUAL";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0) {
            return "MANUAL";
        }
        return TYPE_MAP.getOrDefault(fileName.substring(idx + 1).toLowerCase(), "MANUAL");
    }

    public String getUploadDir() {
        return root.toString();
    }

    /**
     * 上传附件。caseId 可为空（先传材料再建案件的场景）。
     */
    public CaseFile upload(MultipartFile file, Long caseId) {
        return upload(file, caseId, null);
    }

    /**
     * 上传附件；{@code todoId} 非空时按「待办佐证材料」的规则校验
     * （限定扩展名白名单 + 单文件 20MB 上限）。
     */
    public CaseFile upload(MultipartFile file, Long caseId, Long todoId) {
        if (file == null || file.isEmpty()) {
            throw new BizException("文件为空");
        }
        String original = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String ext = "";
        int idx = original.lastIndexOf('.');
        if (idx > 0) {
            ext = original.substring(idx + 1);
        }
        if (todoId != null) {
            checkEvidence(file, ext);
        }
        String month = new SimpleDateFormat("yyyyMM").format(new Date());
        Path dir = root.resolve(month);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new BizException("创建存储目录失败");
        }
        String stored = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8)
                + (ext.isEmpty() ? "" : "." + ext);
        Path target = dir.resolve(stored);
        try {
            file.transferTo(target.toFile());
        } catch (Exception e) {
            throw new BizException("文件保存失败：" + e.getMessage());
        }
        CaseFile entity = new CaseFile();
        entity.setCaseId(caseId);
        entity.setTodoId(todoId);
        entity.setFileName(original);
        entity.setFileType(ext.isEmpty() ? null : ext.toLowerCase());
        entity.setFileSize(file.getSize());
        entity.setStoragePath(root.relativize(target).toString());
        entity.setUploadedBy(AuthContext.userId());
        entity.setUploadedAt(LocalDateTime.now());
        fileMapper.insert(entity);
        logService.log("FILE", "UPLOAD", "CASE", caseId,
                todoId == null ? "上传附件：" + original : "上传待办佐证：" + original);
        return entity;
    }

    /** 佐证材料校验：扩展名白名单 + 大小上限。错误信息直接说清规则，便于现场自查。 */
    private void checkEvidence(MultipartFile file, String ext) {
        if (ext == null || !EVIDENCE_EXT.containsKey(ext.toLowerCase())) {
            throw new BizException("佐证材料不支持该格式（." + (ext == null ? "" : ext.toLowerCase())
                    + "）。允许：" + evidenceExtHint());
        }
        if (file.getSize() > EVIDENCE_MAX_BYTES) {
            throw new BizException("佐证材料不能超过 20MB，当前 "
                    + String.format("%.1fMB", file.getSize() / 1024.0 / 1024.0));
        }
    }

    /** 允许的扩展名提示串，供接口与前端展示 */
    public static String evidenceExtHint() {
        return String.join(" / ", EVIDENCE_EXT.keySet());
    }

    public static java.util.Set<String> evidenceExtSet() {
        return EVIDENCE_EXT.keySet();
    }

    /** 某条待办的佐证材料（按上传时间倒序，最新在前） */
    public List<CaseFileVO> filesOfTodo(Long todoId) {
        if (todoId == null) {
            return new ArrayList<>();
        }
        List<CaseFile> list = fileMapper.selectList(new LambdaQueryWrapper<CaseFile>()
                .eq(CaseFile::getTodoId, todoId).orderByDesc(CaseFile::getId));
        List<CaseFileVO> vos = new ArrayList<>();
        for (CaseFile f : list) {
            vos.add(toVO(f));
        }
        return vos;
    }

    public int countOfTodo(Long todoId) {
        if (todoId == null) {
            return 0;
        }
        Long n = fileMapper.selectCount(new LambdaQueryWrapper<CaseFile>()
                .eq(CaseFile::getTodoId, todoId));
        return n == null ? 0 : n.intValue();
    }

    /** 案件材料清单。待办佐证（todo_id 非空）不在此列，它们在待办面板里单独展示。 */
    public List<CaseFileVO> filesOf(Long caseId) {
        if (caseId == null) {
            return new ArrayList<>();
        }
        List<CaseFile> list = fileMapper.selectList(new LambdaQueryWrapper<CaseFile>()
                .eq(CaseFile::getCaseId, caseId)
                .isNull(CaseFile::getTodoId)
                .orderByDesc(CaseFile::getId));
        List<CaseFileVO> vos = new ArrayList<>();
        for (CaseFile f : list) {
            vos.add(toVO(f));
        }
        return vos;
    }

    public CaseFileVO toVO(CaseFile f) {
        CaseFileVO vo = new CaseFileVO();
        vo.setId(f.getId());
        vo.setCaseId(f.getCaseId());
        vo.setTodoId(f.getTodoId());
        vo.setFileName(f.getFileName());
        vo.setFileType(f.getFileType());
        vo.setFileSize(f.getFileSize());
        vo.setSizeText(sizeText(f.getFileSize()));
        vo.setUploadedAt(f.getUploadedAt());
        vo.setUploadedByName(userName(f.getUploadedBy()));
        return vo;
    }

    private String userName(Long id) {
        if (id == null) {
            return null;
        }
        SysUser u = userMapper.selectById(id);
        return u == null ? null : u.getDisplayName();
    }

    private String sizeText(Long size) {
        if (size == null) {
            return "-";
        }
        double s = size;
        if (s < 1024) {
            return size + " B";
        }
        if (s < 1024 * 1024) {
            return String.format("%.1f KB", s / 1024);
        }
        return String.format("%.1f MB", s / 1024 / 1024);
    }

    public CaseFile get(Long fileId) {
        CaseFile f = fileMapper.selectById(fileId);
        if (f == null) {
            throw new BizException("附件不存在");
        }
        return f;
    }

    public org.springframework.core.io.Resource load(CaseFile f) {
        Path p = root.resolve(f.getStoragePath()).normalize();
        File file = p.toFile();
        if (!file.exists()) {
            throw new BizException("附件在磁盘上已丢失：" + f.getFileName());
        }
        return new FileSystemResource(file);
    }

    public void delete(Long fileId) {
        CaseFile f = get(fileId);
        // 不变式：待办「已完成」必然有佐证。删掉最后一份佐证会破坏它，
        // 因此要求先撤销完成再删除，避免出现「已完成但无凭据」的脏状态。
        if (f.getTodoId() != null) {
            CaseTodo t = todoMapper.selectById(f.getTodoId());
            if (t != null && "DONE".equals(t.getStatus()) && countOfTodo(f.getTodoId()) <= 1) {
                throw new BizException("该佐证正用于确认待办完成，请先撤销完成再删除");
            }
        }
        fileMapper.deleteById(fileId);
        try {
            Files.deleteIfExists(root.resolve(f.getStoragePath()).normalize());
        } catch (Exception ignored) {
        }
        logService.log("FILE", "DELETE", "CASE", f.getCaseId(), "删除附件：" + f.getFileName());
    }

    public void bindToCase(Long fileId, Long caseId) {
        CaseFile f = get(fileId);
        f.setCaseId(caseId);
        fileMapper.updateById(f);
    }
}
