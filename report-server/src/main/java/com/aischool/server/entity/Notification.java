package com.aischool.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** t_notification 通知中心（批29）：点对点待办/结果类消息，点击按 link 直达处理页 */
@Data
@TableName("t_notification")
public class Notification {

    public static final String OA_TODO = "OA_TODO";
    public static final String OA_RESULT = "OA_RESULT";
    public static final String LEAVE_TODO = "LEAVE_TODO";
    public static final String LEAVE_RESULT = "LEAVE_RESULT";
    /** 批32：请假登记回执（→家长）/ 审批结果同步（→门卫/生活老师） */
    public static final String LEAVE_NOTICE = "LEAVE_NOTICE";
    public static final String REGISTER_TODO = "REGISTER_TODO";
    /** 批63 报修三层流转：待办（提交→后勤审批 / 通过→维修工接单）/ 结果（拒绝·接单·完成→报修人、完成抄送） */
    public static final String REPAIR_TODO = "REPAIR_TODO";
    public static final String REPAIR_RESULT = "REPAIR_RESULT";
    public static final String SYSTEM = "SYSTEM";
    public static final String ALERT = "ALERT";
    /** 批66② 校园通知广播：发布即推可见老师，点直达 /notices/{id} */
    public static final String NOTICE_BROADCAST = "NOTICE_BROADCAST";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private String title;
    private String content;
    private String link;
    private LocalDateTime readTime;
    private LocalDateTime createTime;
}
