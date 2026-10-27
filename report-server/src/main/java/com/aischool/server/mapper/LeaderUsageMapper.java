package com.aischool.server.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 领导端「教师使用情况」聚合（批2）：各类行为按教师分组计数。
 * 各表时间列口径不同（eval_time/create_time），统一以 since 起算；
 * 登录次数取自 t_audit_log 的 /api/auth/login 行（9-15 登录审计，成功行才回填 user_id）。
 * 批70：until 可选上界（自然月口径）+ 扬长课程/导师谈心/荣誉证书三模块计数
 * （t_honor 无录入人列，荣誉按审计日志 POST /api/honor/upload 成功行归属）。
 */
public interface LeaderUsageMapper {

    @Select("""
            <script>
            SELECT teacher_id AS userId, COUNT(*) AS cnt
            FROM t_evaluation WHERE eval_time &gt;= #{since} AND teacher_id IS NOT NULL
            <if test="until != null">AND eval_time &lt; #{until}</if>
            GROUP BY teacher_id
            </script>
            """)
    List<Map<String, Object>> evalCount(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    @Select("""
            <script>
            SELECT created_by AS userId, COUNT(*) AS cnt
            FROM t_score WHERE create_time &gt;= #{since} AND created_by IS NOT NULL
            <if test="until != null">AND create_time &lt; #{until}</if>
            GROUP BY created_by
            </script>
            """)
    List<Map<String, Object>> scoreCount(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    @Select("""
            <script>
            SELECT teacher_id AS userId, COUNT(*) AS cnt
            FROM t_moment WHERE create_time &gt;= #{since} AND teacher_id IS NOT NULL
            <if test="until != null">AND create_time &lt; #{until}</if>
            GROUP BY teacher_id
            </script>
            """)
    List<Map<String, Object>> momentCount(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    /** 扬长课程录入（批70）：t_activity.creator_id */
    @Select("""
            <script>
            SELECT creator_id AS userId, COUNT(*) AS cnt
            FROM t_activity WHERE create_time &gt;= #{since} AND creator_id IS NOT NULL
            <if test="until != null">AND create_time &lt; #{until}</if>
            GROUP BY creator_id
            </script>
            """)
    List<Map<String, Object>> activityCount(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    /** 导师谈心（批70）：t_talk.teacher_id */
    @Select("""
            <script>
            SELECT teacher_id AS userId, COUNT(*) AS cnt
            FROM t_talk WHERE create_time &gt;= #{since} AND teacher_id IS NOT NULL
            <if test="until != null">AND create_time &lt; #{until}</if>
            GROUP BY teacher_id
            </script>
            """)
    List<Map<String, Object>> talkCount(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    /** 荣誉证书录入（批70）：t_honor 无录入人列，按审计成功 POST 归属操作教师 */
    @Select("""
            <script>
            SELECT user_id AS userId, COUNT(*) AS cnt
            FROM t_audit_log
            WHERE method = 'POST' AND uri = '/api/honor/upload' AND status = 200
              AND user_id IS NOT NULL AND create_time &gt;= #{since}
            <if test="until != null">AND create_time &lt; #{until}</if>
            GROUP BY user_id
            </script>
            """)
    List<Map<String, Object>> honorCount(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    /** AI 报告生成次数与 tokens（与管理端 AI 用量同口径：仅走 LLM 的成功任务） */
    @Select("""
            <script>
            SELECT created_by AS userId, COUNT(*) AS cnt,
                   IFNULL(SUM(prompt_tokens + completion_tokens), 0) AS tokens
            FROM t_ai_task
            WHERE source = 'llm' AND status = '成功' AND create_time &gt;= #{since} AND created_by IS NOT NULL
            <if test="until != null">AND create_time &lt; #{until}</if>
            GROUP BY created_by
            </script>
            """)
    List<Map<String, Object>> aiCount(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    @Select("""
            <script>
            SELECT user_id AS userId, COUNT(*) AS cnt, MAX(create_time) AS lastLogin
            FROM t_audit_log
            WHERE uri = '/api/auth/login' AND user_id IS NOT NULL AND create_time &gt;= #{since}
            <if test="until != null">AND create_time &lt; #{until}</if>
            GROUP BY user_id
            </script>
            """)
    List<Map<String, Object>> loginCount(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
}
