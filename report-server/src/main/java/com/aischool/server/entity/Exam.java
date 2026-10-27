package com.aischool.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

/** t_exam */
@Data
@TableName("t_exam")
public class Exam {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long termId;
    private String name;
    private LocalDate examDate;
    private Long gradeId;
    private BigDecimal classMaxTotal;
    private BigDecimal gradeMaxTotal;
    private Integer entryOpen;
}
