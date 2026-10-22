package com.aischool.server.service.notify;

import com.aischool.server.entity.Notification;
import com.aischool.server.entity.SysConfig;
import com.aischool.server.mapper.SysConfigMapper;
import com.aischool.server.service.health.HealthScanService;
import com.aischool.server.service.health.HealthScanService.IssueGroup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 数据体检周报（批29）：每周配置日早上跑一次体检扫描，异常汇总推企微群 +
 * 管理员/领导 App 通知（未配群机器人时 App 通知兜底可见）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyReportService {

    private final HealthScanService scanService;
    private final SysConfigMapper sysConfigMapper;
    private final WecomService wecomService;
    private final NotificationService notificationService;

    private volatile LocalDate lastRun;

    /** 每天 07:40 醒一次，比对配置的星期（1=周一…7=周日，默认 5=周五）当天未跑才出报 */
    @Scheduled(cron = "0 40 7 * * ?")
    public void maybeWeekly() {
        if (!"1".equals(cfg("weekly_report_enabled"))) {
            return;
        }
        int day;
        try {
            day = Math.max(1, Math.min(7, Integer.parseInt(cfg("weekly_report_day"))));
        } catch (NumberFormatException e) {
            day = 5;
        }
        LocalDate today = LocalDate.now();
        if (today.getDayOfWeek().getValue() != day || today.equals(lastRun)) {
            return;
        }
        lastRun = today;
        try {
            runReport();
        } catch (Exception e) {
            log.warn("体检周报生成失败：{}", e.getMessage());
        }
    }

    /** 出报（定时器与管理端「立即出周报」共用）：返回 markdown 全文 */
    public String runReport() {
        List<IssueGroup> groups = scanService.scan();
        long total = groups.stream().mapToLong(IssueGroup::getCount).sum();
        StringBuilder md = new StringBuilder("【石实SHINE 数据体检周报】").append(LocalDate.now()).append("\n");
        StringBuilder brief = new StringBuilder();
        if (total == 0) {
            md.append("> 全部 8 项检查通过，数据健康 ✅");
            brief.append("全部 8 项检查通过，数据健康");
        } else {
            for (IssueGroup g : groups) {
                if (g.getCount() == 0) {
                    continue;
                }
                String line = g.getTitle() + "：" + g.getCount() + " 条";
                md.append("> ").append(line).append("（").append(firstLabel(g)).append("）\n");
                if (brief.length() > 0) {
                    brief.append("，");
                }
                brief.append(line);
            }
            md.append("> 共 ").append(total).append(" 项待完善，详见管理端「数据体检」页签");
        }
        wecomService.sendMarkdown(md.toString());
        // 批64 书记与领导平权接收
        notificationService.sendToRoles(List.of("ADMIN", "LEADER", "SECRETARY"), Notification.SYSTEM,
                "数据体检周报", brief.toString(), "/admin?tab=health");
        return md.toString();
    }

    private String firstLabel(IssueGroup g) {
        return g.getItems().isEmpty() ? "样例略" : g.getItems().get(0).getLabel();
    }

    private String cfg(String key) {
        SysConfig c = sysConfigMapper.selectById(key);
        return c == null || c.getCfgValue() == null ? "" : c.getCfgValue().trim();
    }
}
