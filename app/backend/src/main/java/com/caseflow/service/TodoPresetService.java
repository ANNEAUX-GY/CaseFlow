package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseLeaderOpinion;
import com.caseflow.entity.TodoPreset;
import com.caseflow.mapper.CaseLeaderOpinionMapper;
import com.caseflow.mapper.TodoPresetMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.vo.TodoPresetVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 常用待办记忆（2026-10-11）。
 *
 * <p>「添加待办」弹窗底下那排可点的标签：服务端记下<b>这个登录人</b>最常写的那几条待办，
 * 前端取前 N 条展示，点一下就按原文添一条进去。
 *
 * <p>两条设计边界：
 * <ul>
 *   <li><b>按人记，不共享</b>：甲天天写的「走访受害人」对乙未必常用，
 *       混在一起会变成谁都不想看的噪声。所以每行都带 user_id，查询永远带它。</li>
 *   <li><b>只记内容与重要性，不记截止时间</b>：截止时间是随案定的
 *       （这个案子 3 天、那个案子 7 天），写进模板只会让人加出一条过期时间；
 *       重要性反倒沿用——「这条我一直当 A 级办」是稳定的个人习惯。</li>
 * </ul>
 *
 * <p><b>记忆是附加价值</b>：{@link #hit} 自己吞掉异常只记日志。
 * 待办已经落库了，却因为"没记住"把整次添加回滚掉，是拿主业务给便利功能陪葬。
 */
@Slf4j
@Service
public class TodoPresetService {

    /** 默认给前端几条；再多就变成另一张待办清单了 */
    private static final int DEFAULT_LIMIT = 8;
    /** 单人最多留多少条不同内容。真正常用的也就那几条，200 足够，超出即不再新增 */
    private static final int MAX_KEEP = 200;
    /** 与 todo_preset.content 列宽一致；超长的（理论上不会）不记，绝不截断成另一句待办 */
    private static final int MAX_CONTENT = 500;

    @Resource
    private TodoPresetMapper presetMapper;
    @Resource
    private CaseLeaderOpinionMapper opinionMapper;

    /**
     * 记一次使用：已存在则次数 +1 并刷新最近使用时间与重要性，不存在则新增。
     *
     * <p>事务：本方法<b>不自己开事务</b>——{@code OpinionService.doAdd} 已在事务里，
     * 记忆与待办同生共死是最自然的语义；失败则被这里兜住（见类注释）。
     *
     * @param userId     登录人（null 时不记：没有归属的记忆无从推荐）
     * @param content    待办内容原文
     * @param importance 本次使用的重要性 A/B/C（可空，空按不更新处理）
     */
    public void hit(Long userId, String content, String importance) {
        if (userId == null || !StringUtils.hasText(content)) {
            return;
        }
        String text = content.trim();
        if (text.length() > MAX_CONTENT) {
            return;
        }
        try {
            TodoPreset hit = findMine(userId, text);
            LocalDateTime now = LocalDateTime.now();
            if (hit != null) {
                TodoPreset patch = new TodoPreset();
                patch.setId(hit.getId());
                patch.setUseCount((hit.getUseCount() == null ? 0 : hit.getUseCount()) + 1);
                patch.setLastUsedAt(now);
                patch.setUpdatedAt(now);
                if (StringUtils.hasText(importance)) {
                    patch.setLastImportance(importance.trim().toUpperCase());
                }
                presetMapper.updateById(patch);
                return;
            }
            Long cnt = presetMapper.selectCount(new LambdaQueryWrapper<TodoPreset>()
                    .eq(TodoPreset::getUserId, userId));
            if (cnt != null && cnt >= MAX_KEEP) {
                // 到上限就不再新增（不删旧的）：记忆的价值在"常用的那几条"，
                // 因为满了就去淘汰历史，等于让一个很久没写但曾经常用的条目凭空消失。
                return;
            }
            TodoPreset p = new TodoPreset();
            p.setUserId(userId);
            p.setContent(text);
            p.setLastImportance(StringUtils.hasText(importance) ? importance.trim().toUpperCase() : null);
            p.setUseCount(1);
            p.setLastUsedAt(now);
            p.setCreatedAt(now);
            p.setUpdatedAt(now);
            presetMapper.insert(p);
        } catch (Exception e) {
            log.warn("[TodoPreset] 常用待办记忆写入跳过（不影响待办本身）：{}", e.getMessage());
        }
    }

    /**
     * 我的常用待办（次数降序，同次数则最近用过的在前）。
     *
     * <p><b>首次自动回填</b>：本表是 2026-10-11 才建的，之前那些提过的意见一条都不在里面。
     * 若不做这一步，老用户打开弹窗底下是一片空白——"常用待办"明明有数据却显示不出来，
     * 会被当成功能坏了。所以第一次查询时按本人历史意见（creator_id = 我）聚合成初始记忆。
     *
     * @param limit 最多几条（null/非正数取 {@link #DEFAULT_LIMIT}）
     */
    public List<TodoPresetVO> listForMe(Integer limit) {
        Long uid = AuthContext.userId();
        if (uid == null) {
            return new ArrayList<>();
        }
        int n = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, 50);
        backfillIfEmpty(uid);

        List<TodoPreset> list = presetMapper.selectList(new LambdaQueryWrapper<TodoPreset>()
                .eq(TodoPreset::getUserId, uid)
                // COALESCE 兜住 last_used_at 为空的回填行：直接用列排序时
                // NULL 在两种方言里的排位不一样（H2 与 MySQL 相反），会莫名其妙把没用过的排到最前。
                .last("ORDER BY use_count DESC, COALESCE(last_used_at, created_at) DESC, id DESC"));
        List<TodoPresetVO> out = new ArrayList<>();
        for (int i = 0; i < list.size() && out.size() < n; i++) {
            TodoPreset p = list.get(i);
            if (!StringUtils.hasText(p.getContent())) {
                continue;
            }
            TodoPresetVO vo = new TodoPresetVO();
            vo.setId(p.getId());
            vo.setContent(p.getContent());
            vo.setImportance(p.getLastImportance());
            vo.setUseCount(p.getUseCount() == null ? 0 : p.getUseCount());
            out.add(vo);
        }
        return out;
    }

    /**
     * 首次回填：把本人历史意见按内容聚合成初始记忆。
     *
     * <p>幂等：只有本人在本表里一条记录都没有时才执行（回填完自然就不为空了）。
     * 意见内容相同的算同一条——"同一个交代在不同案子里提了五次"正是最常用的那种。
     */
    private void backfillIfEmpty(Long uid) {
        Long cnt = presetMapper.selectCount(new LambdaQueryWrapper<TodoPreset>()
                .eq(TodoPreset::getUserId, uid));
        if (cnt != null && cnt > 0) {
            return;
        }
        List<CaseLeaderOpinion> mine = opinionMapper.selectList(new LambdaQueryWrapper<CaseLeaderOpinion>()
                .eq(CaseLeaderOpinion::getCreatorId, uid)
                .isNotNull(CaseLeaderOpinion::getContent)
                .ne(CaseLeaderOpinion::getContent, ""));
        if (mine.isEmpty()) {
            return;
        }
        // LinkedHashMap 保持首次出现顺序（同次数时先提过的在前）
        Map<String, TodoPreset> byContent = new LinkedHashMap<>();
        for (CaseLeaderOpinion o : mine) {
            String text = o.getContent() == null ? "" : o.getContent().trim();
            if (!StringUtils.hasText(text) || text.length() > MAX_CONTENT) {
                continue;
            }
            TodoPreset p = byContent.get(text);
            if (p == null) {
                p = new TodoPreset();
                p.setUserId(uid);
                p.setContent(text);
                p.setUseCount(0);
                byContent.put(text, p);
            }
            p.setUseCount(p.getUseCount() + 1);
            // 后遍历到的未必更晚（没排序），所以两个时间都取较大值
            if (o.getCreatedAt() != null
                    && (p.getLastUsedAt() == null || o.getCreatedAt().isAfter(p.getLastUsedAt()))) {
                p.setLastUsedAt(o.getCreatedAt());
                if (StringUtils.hasText(o.getImportance())) {
                    p.setLastImportance(o.getImportance());
                }
            }
        }
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (TodoPreset p : byContent.values()) {
            if (n >= MAX_KEEP) {
                break;
            }
            p.setCreatedAt(now);
            p.setUpdatedAt(now);
            try {
                presetMapper.insert(p);
                n++;
            } catch (Exception e) {
                log.warn("[TodoPreset] 历史待办回填跳过一条：{}", e.getMessage());
            }
        }
        if (n > 0) {
            log.info("[TodoPreset] 已按历史意见回填常用待办：{} 条", n);
        }
    }

    /** 按原文精确匹配我的一条记忆（去空格后比较，避免"走访受害人 "与"走访受害人"分成两条） */
    private TodoPreset findMine(Long userId, String text) {
        List<TodoPreset> mine = presetMapper.selectList(new LambdaQueryWrapper<TodoPreset>()
                .eq(TodoPreset::getUserId, userId));
        for (TodoPreset p : mine) {
            if (p.getContent() != null && p.getContent().trim().equals(text)) {
                return p;
            }
        }
        return null;
    }
}
