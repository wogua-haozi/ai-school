package com.aischool.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

/** t_score */
@Data
@TableName("t_score")
public class Score {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long examId;
    private Long subjectId;
    private Long studentId;
    private BigDecimal score;
    private String grade; // 批63c 校方上传等级（A~D 可带 +/-）；subject_id NULL 行=总分等级
    private Integer classRank;
    private Integer gradeRank;
    private Long createdBy;
}
