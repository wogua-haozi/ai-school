package com.aischool.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** t_activity */
@Data
@TableName("t_activity")
public class Activity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String type;
    private LocalDateTime startTime;
    private String place;
    private String coverUrl;
    /** 批72d：课程视频外链（腾讯视频/B站等，点击跳转观看） */
    private String videoUrl;
    private String intro;
    private Long creatorId;
}
