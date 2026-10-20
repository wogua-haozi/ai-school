package com.aischool.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** t_repair 报修单（批10；批63 三层流转）：文字描述+拍照凭证，
 *  PENDING→后勤审批 APPROVED/REJECTED→维修工抢单 CLAIMED→完成 DONE；报修人 PENDING/APPROVED 可撤回 CANCELED */
@Data
@TableName("t_repair")
public class Repair {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED"; // 批63 后勤审批通过，待维修工接单
    public static final String CLAIMED = "CLAIMED";   // 批63 维修工已接单，维修中
    public static final String DONE = "DONE";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELED = "CANCELED"; // 批40e 撤回普适：发起人撤回（未接单前）

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reporterId;
    private String location;
    private String description;
    /** 凭证照片 objectName JSON 数组（≤3，MinIO repair/ 前缀） */
    private String photos;
    private String status;
    private Long handlerId;
    private String handleNote;
    private LocalDateTime handleTime;
    private Long approverId;   // 批63 后勤审批人
    private LocalDateTime approveTime;
    private String approveNote;
    private Long claimerId;    // 批63 接单维修工
    private LocalDateTime claimTime;
    private LocalDateTime createTime;
}
