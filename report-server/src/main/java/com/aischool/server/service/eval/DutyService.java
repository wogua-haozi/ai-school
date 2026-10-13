package com.aischool.server.service.eval;

import com.aischool.server.entity.DutySchedule;
import com.aischool.server.mapper.DutyScheduleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 值日排班（批6 漏项C1；批56③ 口径收口）：
 * 当日值日老师可记班级记分（任意班，CivilityService.checkClassScoreAllowed）；
 * 素养评价已放开为全体教职工，原 duty_check 开关随之退役（管理端仅保留排班表维护）。
 */
@Service
@RequiredArgsConstructor
public class DutyService {

    private final DutyScheduleMapper dutyScheduleMapper;

    public boolean isOnDuty(Long teacherId, LocalDate date) {
        return dutyScheduleMapper.selectCount(new LambdaQueryWrapper<DutySchedule>()
                .eq(DutySchedule::getDutyDate, date)
                .eq(DutySchedule::getTeacherId, teacherId)) > 0;
    }
}
