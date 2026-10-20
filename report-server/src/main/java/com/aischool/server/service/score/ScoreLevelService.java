package com.aischool.server.service.score;

import com.aischool.server.entity.SysConfig;
import com.aischool.server.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 批63 成绩等级：按满分比例现算 A/B/C/D，不入库不上传（家长与只读入口只见分数+等级，无排名）。
 * 阈值 t_sys_config：score_grade_a/b/c（百分比，默认 85/70/60，低于 c 线=D），上线前与校方确认。
 */
@Service
@RequiredArgsConstructor
public class ScoreLevelService {

    private static final BigDecimal DEFAULT_A = new BigDecimal("85");
    private static final BigDecimal DEFAULT_B = new BigDecimal("70");
    private static final BigDecimal DEFAULT_C = new BigDecimal("60");

    private final SysConfigMapper sysConfigMapper;

    /** 分数→等级（score/fullScore 缺失或满分非正返回 null） */
    public String levelOf(BigDecimal score, BigDecimal fullScore) {
        if (score == null || fullScore == null || fullScore.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal pct = score.multiply(BigDecimal.valueOf(100))
                .divide(fullScore, 4, RoundingMode.HALF_UP);
        if (pct.compareTo(threshold("score_grade_a", DEFAULT_A)) >= 0) {
            return "A";
        }
        if (pct.compareTo(threshold("score_grade_b", DEFAULT_B)) >= 0) {
            return "B";
        }
        if (pct.compareTo(threshold("score_grade_c", DEFAULT_C)) >= 0) {
            return "C";
        }
        return "D";
    }

    /** 阈值读取：缺失/脏值回落默认线 */
    private BigDecimal threshold(String key, BigDecimal def) {
        SysConfig c = sysConfigMapper.selectById(key);
        if (c == null || c.getCfgValue() == null || c.getCfgValue().isBlank()) {
            return def;
        }
        try {
            BigDecimal v = new BigDecimal(c.getCfgValue().trim());
            return v.compareTo(BigDecimal.ZERO) > 0 && v.compareTo(BigDecimal.valueOf(100)) <= 0 ? v : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 换算口径说明（家长端脚注，随 t_sys_config 配置实时生效，防前端写死漂移） */
    public String note() {
        return String.format("等级按满分比例换算：A≥%s%%、B≥%s%%、C≥%s%%、低于%s%%为D",
                threshold("score_grade_a", DEFAULT_A).stripTrailingZeros().toPlainString(),
                threshold("score_grade_b", DEFAULT_B).stripTrailingZeros().toPlainString(),
                threshold("score_grade_c", DEFAULT_C).stripTrailingZeros().toPlainString(),
                threshold("score_grade_c", DEFAULT_C).stripTrailingZeros().toPlainString());
    }
}
