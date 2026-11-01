package com.aischool.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** t_walk_class_student 走班-学生映射（批75） */
@Data
@TableName("t_walk_class_student")
public class WalkClassStudent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long walkClassId;
    private Long studentId;
}
