package com.aischool.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** t_grade_binding 年级岗位绑定（批32：级长-年级；批51：级部主任-年级，请假审批与级长同权；生活老师分年级方案到位后同表 duty=DORM） */
@Data
@TableName("t_grade_binding")
public class GradeBinding {

    public static final String DUTY_GRADE_LEADER = "GRADE_LEADER";
    public static final String DUTY_GRADE_DIRECTOR = "GRADE_DIRECTOR";
    public static final String DUTY_DORM = "DORM";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long gradeId;
    private String duty;
    private LocalDateTime createTime;
}
