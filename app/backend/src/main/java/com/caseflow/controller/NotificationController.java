package com.caseflow.controller;

import com.caseflow.common.Result;
import com.caseflow.entity.CaseNotification;
import com.caseflow.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 统一信箱（2026-10-04）：管理层与普通用户的「与自己有关」操作变更归集。
 * 所有端点都按当前登录人收敛，无越权风险。
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    @Resource
    private NotificationService notificationService;

    /** 我的未读通知（新→旧） */
    @GetMapping("/unread")
    public Result<java.util.List<CaseNotification>> unread() {
        return Result.ok(notificationService.unreadForMe());
    }

    /** 我的信箱列表（新→旧）：box=unread 未读 / read 已读 / all 全部，缺省 all。
     *  已读历史不删行，从这查（2026-10-06 需求：能看到历史的已读信息）。 */
    @GetMapping("/list")
    public Result<java.util.List<CaseNotification>> list(@RequestParam(defaultValue = "all") String box) {
        return Result.ok(notificationService.listForMe(box));
    }

    /** 未读数（顶栏角标） */
    @GetMapping("/unread-count")
    public Result<Integer> unreadCount() {
        return Result.ok(notificationService.unreadCount());
    }

    /** 标记单条已读（幂等） */
    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return Result.ok();
    }

    /** 全部标为已读 */
    @PostMapping("/read-all")
    public Result<Integer> markAllRead() {
        return Result.ok(notificationService.markAllRead());
    }
}
