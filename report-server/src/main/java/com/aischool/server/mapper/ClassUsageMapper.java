package com.aischool.server.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 批73c 班主任/级长统计：本班（分管年级）学生的填写情况聚合。
 * 班级面统一经 t_student.class_id 子查询圈学生（外层不 IN 大量学号）；
 * 时间口径沿用 LeaderUsageMapper（评价 eval_time、其余 create_time / signup_time）；
 * 荣誉无录入人列（批70 同题）：按成功上传审计行以荣誉落库时刻 +5s 窗口对齐归属操作教师
 * （审计在响应后写入，同师两次上传间隔必大于窗口，无双计）。
 */
public interface ClassUsageMapper {

    /* ── 老师维度（第4条）：范围学生被各老师填写次数 ── */

    @Select("""
            <script>
            SELECT m.teacher_id AS userId, COUNT(*) AS cnt
            FROM t_moment m JOIN t_moment_student ms ON ms.moment_id = m.id
            WHERE m.teacher_id IS NOT NULL
              AND ms.student_id IN (SELECT id FROM t_student WHERE class_id IN
              <foreach collection="classIds" item="c" open="(" separator="," close=")">#{c}</foreach>)
              <if test="since != null">AND m.create_time &gt;= #{since}</if>
              <if test="until != null">AND m.create_time &lt; #{until}</if>
            GROUP BY m.teacher_id
            </script>
            """)
    List<Map<String, Object>> momentsByTeacher(@Param("classIds") List<Long> classIds,
                                               @Param("since") LocalDateTime since,
                                               @Param("until") LocalDateTime until);

    @Select("""
            <script>
            SELECT e.teacher_id AS userId, COUNT(*) AS cnt
            FROM t_evaluation e
            WHERE e.teacher_id IS NOT NULL
              AND e.student_id IN (SELECT id FROM t_student WHERE class_id IN
              <foreach collection="classIds" item="c" open="(" separator="," close=")">#{c}</foreach>)
              <if test="since != null">AND e.eval_time &gt;= #{since}</if>
              <if test="until != null">AND e.eval_time &lt; #{until}</if>
            GROUP BY e.teacher_id
            </script>
            """)
    List<Map<String, Object>> evalsByTeacher(@Param("classIds") List<Long> classIds,
                                             @Param("since") LocalDateTime since,
                                             @Param("until") LocalDateTime until);

    @Select("""
            <script>
            SELECT s.created_by AS userId, COUNT(*) AS cnt
            FROM t_score s
            WHERE s.created_by IS NOT NULL
              AND s.student_id IN (SELECT id FROM t_student WHERE class_id IN
              <foreach collection="classIds" item="c" open="(" separator="," close=")">#{c}</foreach>)
              <if test="since != null">AND s.create_time &gt;= #{since}</if>
              <if test="until != null">AND s.create_time &lt; #{until}</if>
            GROUP BY s.created_by
            </script>
            """)
    List<Map<String, Object>> scoresByTeacher(@Param("classIds") List<Long> classIds,
                                              @Param("since") LocalDateTime since,
                                              @Param("until") LocalDateTime until);

    /** 扬长参与无录入人列，按课程创建者归属（录参与者通常即建课教师） */
    @Select("""
            <script>
            SELECT a.creator_id AS userId, COUNT(*) AS cnt
            FROM t_activity_signup su JOIN t_activity a ON su.activity_id = a.id
            WHERE a.creator_id IS NOT NULL
              AND su.student_id IN (SELECT id FROM t_student WHERE class_id IN
              <foreach collection="classIds" item="c" open="(" separator="," close=")">#{c}</foreach>)
              <if test="since != null">AND su.signup_time &gt;= #{since}</if>
              <if test="until != null">AND su.signup_time &lt; #{until}</if>
            GROUP BY a.creator_id
            </script>
            """)
    List<Map<String, Object>> activitiesByTeacher(@Param("classIds") List<Long> classIds,
                                                  @Param("since") LocalDateTime since,
                                                  @Param("until") LocalDateTime until);

    @Select("""
            <script>
            SELECT t.teacher_id AS userId, COUNT(*) AS cnt
            FROM t_talk t
            WHERE t.teacher_id IS NOT NULL
              AND t.student_id IN (SELECT id FROM t_student WHERE class_id IN
              <foreach collection="classIds" item="c" open="(" separator="," close=")">#{c}</foreach>)
              <if test="since != null">AND t.create_time &gt;= #{since}</if>
              <if test="until != null">AND t.create_time &lt; #{until}</if>
            GROUP BY t.teacher_id
            </script>
            """)
    List<Map<String, Object>> talksByTeacher(@Param("classIds") List<Long> classIds,
                                             @Param("since") LocalDateTime since,
                                             @Param("until") LocalDateTime until);

    @Select("""
            <script>
            SELECT al.user_id AS userId, COUNT(*) AS cnt
            FROM t_honor h JOIN t_audit_log al
              ON al.method = 'POST' AND al.uri = '/api/honor/upload' AND al.status = 200
             AND al.user_id IS NOT NULL
             AND al.create_time BETWEEN h.create_time AND DATE_ADD(h.create_time, INTERVAL 5 SECOND)
            WHERE h.student_id IN (SELECT id FROM t_student WHERE class_id IN
            <foreach collection="classIds" item="c" open="(" separator="," close=")">#{c}</foreach>)
            <if test="since != null">AND h.create_time &gt;= #{since}</if>
            <if test="until != null">AND h.create_time &lt; #{until}</if>
            GROUP BY al.user_id
            </script>
            """)
    List<Map<String, Object>> honorsByTeacher(@Param("classIds") List<Long> classIds,
                                              @Param("since") LocalDateTime since,
                                              @Param("until") LocalDateTime until);

    /* ── 学生维度（第5条）：本班每生六项记录数 ── */

    @Select("""
            <script>
            SELECT ms.student_id AS sid, COUNT(*) AS cnt
            FROM t_moment m JOIN t_moment_student ms ON ms.moment_id = m.id
            WHERE ms.student_id IN (SELECT id FROM t_student WHERE class_id IN
            <foreach collection="classIds" item="c" open="(" separator="," close=")">#{c}</foreach>)
            <if test="since != null">AND m.create_time &gt;= #{since}</if>
            <if test="until != null">AND m.create_time &lt; #{until}</if>
            GROUP BY ms.student_id
            </script>
            """)
    List<Map<String, Object>> momentsByStudent(@Param("classIds") List<Long> classIds,
                                               @Param("since") LocalDateTime since,
                                               @Param("until") LocalDateTime until);

    @Select("""
            <script>
            SELECT e.student_id AS sid, COUNT(*) AS cnt
            FROM t_evaluation e
            WHERE e.student_id IN (SELECT id FROM t_student WHERE class_id IN
            <foreach collection="classIds" item="c" open="(" separator="," close=")">#{c}</foreach>)
            <if test="since != null">AND e.eval_time &gt;= #{since}</if>
            <if test="until != null">AND e.eval_time &lt; #{until}</if>
            GROUP BY e.student_id
            </script>
            """)
    List<Map<String, Object>> evalsByStudent(@Param("classIds") List<Long> classIds,
                                             @Param("since") LocalDateTime since,
                                             @Param("until") LocalDateTime until);

    @Select("""
            <script>
            SELECT su.student_id AS sid, COUNT(*) AS cnt
            FROM t_activity_signup su
            WHERE su.student_id IN (SELECT id FROM t_student WHERE class_id IN
            <foreach collection="classIds" item="c" open="(" separator="," close=")">#{c}</foreach>)
            <if test="since != null">AND su.signup_time &gt;= #{since}</if>
            <if test="until != null">AND su.signup_time &lt; #{until}</if>
            GROUP BY su.student_id
            </script>
            """)
    List<Map<String, Object>> activitiesByStudent(@Param("classIds") List<Long> classIds,
                                                  @Param("since") LocalDateTime since,
                                                  @Param("until") LocalDateTime until);

    /** 荣誉计数不分师传/家传（第5条口径=该生名下记录数） */
    @Select("""
            <script>
            SELECT h.student_id AS sid, COUNT(*) AS cnt
            FROM t_honor h
            WHERE h.student_id IN (SELECT id FROM t_student WHERE class_id IN
            <foreach collection="classIds" item="c" open="(" separator="," close=")">#{c}</foreach>)
            <if test="since != null">AND h.create_time &gt;= #{since}</if>
            <if test="until != null">AND h.create_time &lt; #{until}</if>
            GROUP BY h.student_id
            </script>
            """)
    List<Map<String, Object>> honorsByStudent(@Param("classIds") List<Long> classIds,
                                              @Param("since") LocalDateTime since,
                                              @Param("until") LocalDateTime until);

    @Select("""
            <script>
            SELECT t.student_id AS sid, COUNT(*) AS cnt
            FROM t_talk t
            WHERE t.student_id IN (SELECT id FROM t_student WHERE class_id IN
            <foreach collection="classIds" item="c" open="(" separator="," close=")">#{c}</foreach>)
            <if test="since != null">AND t.create_time &gt;= #{since}</if>
            <if test="until != null">AND t.create_time &lt; #{until}</if>
            GROUP BY t.student_id
            </script>
            """)
    List<Map<String, Object>> talksByStudent(@Param("classIds") List<Long> classIds,
                                             @Param("since") LocalDateTime since,
                                             @Param("until") LocalDateTime until);

    /** 学生无账号，登录=绑定家长的成功登录次数（同一家长绑多生则各计一次） */
    @Select("""
            <script>
            SELECT pb.student_id AS sid, COUNT(*) AS cnt
            FROM t_audit_log al JOIN t_parent_binding pb ON al.user_id = pb.parent_user_id
            WHERE al.uri = '/api/auth/login' AND al.status = 200
              AND pb.student_id IN (SELECT id FROM t_student WHERE class_id IN
              <foreach collection="classIds" item="c" open="(" separator="," close=")">#{c}</foreach>)
              <if test="since != null">AND al.create_time &gt;= #{since}</if>
              <if test="until != null">AND al.create_time &lt; #{until}</if>
            GROUP BY pb.student_id
            </script>
            """)
    List<Map<String, Object>> loginsByStudent(@Param("classIds") List<Long> classIds,
                                              @Param("since") LocalDateTime since,
                                              @Param("until") LocalDateTime until);
}
