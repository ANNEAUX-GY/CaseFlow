package com.caseflow.service;

import com.caseflow.entity.SystemConfig;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.SystemConfigMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.LogService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 系统级配置读写（2026-11-13）。
 *
 * <p>案件阶段变更模式开关 {@code flow_change_mode}：
 * <ul>
 *   <li>{@code AUTO}   —— 执行刑拘/取保措施后自动变更阶段；</li>
 *   <li>{@code MANUAL} —— 不自动变，生成「待管理用户确认变更」的待办；</li>
 *   <li>{@code BOTH}   —— 自动变更 + 保留手动确认入口，两套都要。</li>
 * </ul>
 */
@Service
public class SystemConfigService {

    public static final String FLOW_CHANGE_MODE = "flow_change_mode";

    /** 三态取值 */
    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_MANUAL = "MANUAL";
    public static final String MODE_BOTH = "BOTH";

    private static final List<String> VALID_MODES = Arrays.asList(MODE_AUTO, MODE_MANUAL, MODE_BOTH);

    @Resource
    private SystemConfigMapper configMapper;
    @Resource
    private LogService logService;

    /** 读配置值（无值返回 null） */
    public String get(String key) {
        SystemConfig c = configMapper.selectById(key);
        return c == null ? null : c.getCfgValue();
    }

    /** 当前变更模式；未配置时默认 MANUAL（手动确认，最稳妥） */
    public String flowChangeMode() {
        String v = get(FLOW_CHANGE_MODE);
        return (v == null || !VALID_MODES.contains(v)) ? MODE_MANUAL : v;
    }

    /** 是否允许自动变更（AUTO / BOTH） */
    public boolean autoFlowEnabled() {
        String mode = flowChangeMode();
        return MODE_AUTO.equals(mode) || MODE_BOTH.equals(mode);
    }

    /** 是否保留手动确认入口（MANUAL / BOTH） */
    public boolean manualFlowEnabled() {
        String mode = flowChangeMode();
        return MODE_MANUAL.equals(mode) || MODE_BOTH.equals(mode);
    }

    /** 设置变更模式（管理层专属），校验取值 */
    public void setFlowChangeMode(String mode) {
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "设置系统开关需管理层权限");
        }
        if (mode == null || !VALID_MODES.contains(mode)) {
            throw new BizException("非法的变更模式，可选：AUTO / MANUAL / BOTH");
        }
        SystemConfig c = configMapper.selectById(FLOW_CHANGE_MODE);
        if (c == null) {
            c = new SystemConfig();
            c.setCfgKey(FLOW_CHANGE_MODE);
        }
        c.setCfgValue(mode);
        c.setUpdatedAt(LocalDateTime.now());
        if (c.getCfgKey() != null && configMapper.selectById(c.getCfgKey()) != null) {
            configMapper.updateById(c);
        } else {
            configMapper.insert(c);
        }
        logService.log("SYSTEM", "CONFIG", "SYSTEM", null,
                "设置案件阶段变更模式：" + modeLabel(mode));
    }

    /** 模式中文名（日志/界面展示用） */
    public String modeLabel(String mode) {
        if (MODE_AUTO.equals(mode)) return "自动变更";
        if (MODE_MANUAL.equals(mode)) return "手动确认";
        if (MODE_BOTH.equals(mode)) return "两套都要";
        return mode;
    }
}
