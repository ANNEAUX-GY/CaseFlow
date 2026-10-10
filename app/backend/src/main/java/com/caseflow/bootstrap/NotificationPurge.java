package com.caseflow.bootstrap;

import com.caseflow.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 启动时清一遍悬空信件（2026-10-11）。
 *
 * <p>悬空 = 信件里的 {@code case_id} 指向一个已经不存在的案件。
 * 在「删案件连信件一起删」这条规则上线之前删掉的案件，它们的信件还留在库里，
 * 用户点「查看案件」只会落到一句「案件不存在」——信箱里躺着一批点不开的信。
 *
 * <p>幂等，可重复执行；跑完把条数记进日志，便于核对。
 */
@Slf4j
@Component
public class NotificationPurge implements ApplicationRunner {

    @Resource
    private NotificationService notificationService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int n = notificationService.purgeOrphan();
            if (n > 0) {
                log.info("[NotificationPurge] 已清掉指向已删案件的信件 {} 条", n);
            }
        } catch (Exception e) {
            // 清理是收尾活，失败不该挡住启动
            log.warn("[NotificationPurge] 悬空信件清理跳过：{}", e.getMessage());
        }
    }
}
