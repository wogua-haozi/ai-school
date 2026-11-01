package com.aischool.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** t_walk_class 走班（教学班）：年级+学科的学生重排，行政班不动（批75） */
@Data
@TableName("t_walk_class")
public class WalkClass {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long gradeId;
    private Long subjectId;
    private String name;
    private Long teacherId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
