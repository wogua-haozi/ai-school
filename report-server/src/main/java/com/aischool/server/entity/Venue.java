package com.aischool.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** t_venue 场地字典（批11，管理端 CRUD；批53③ 加照片/设备） */
@Data
@TableName("t_venue")
public class Venue {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String location;
    private Integer capacity;
    private Integer status;
    /** 照片 MinIO 对象名（批53③，管理端上传） */
    private String photo;
    /** 设备清单（批53③，中文顿号分隔，如「投影仪、麦克风」） */
    private String equip;
    private LocalDateTime createTime;
}
