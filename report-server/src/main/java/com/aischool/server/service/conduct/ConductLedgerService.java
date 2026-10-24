package com.aischool.server.service.conduct;

import com.aischool.server.entity.ConductLog;
import com.aischool.server.entity.ConductRule;
import com.aischool.server.entity.Term;
import com.aischool.server.mapper.ConductAccountMapper;
import com.aischool.server.mapper.ConductLogMapper;
import com.aischool.server.mapper.ConductRuleMapper;
import com.aischool.server.service.coin.CoinLedgerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 操行分规则账本（批3）：事件驱动唯一入口。
 * 余额 = 基础分 + Σ事件增减（首笔事件时以规则表基础分初始化），每生每学期一行。
 */
@Service
@RequiredArgsConstructor
public class ConductLedgerService {

    private final ConductRuleMapper conductRuleMapper;
    private final ConductLogMapper conductLogMapper;
    private final ConductAccountMapper conductAccountMapper;
    private final CoinLedgerService coinLedgerService;

    /** 记事件增减并同步余额；学期落点与扬长币同口径（resolveTerm 共用） */
    public void apply(Long studentId, LocalDate date, String sourceType, Long sourceId,
                      BigDecimal delta, String reason, Long operatorId) {
        Term term = coinLedgerService.resolveTerm(date);
        ConductRule rule = getRule();

        ConductLog log = new ConductLog();
        log.setStudentId(studentId);
        log.setTermId(term.getId());
        log.setSourceType(sourceType);
        log.setSourceId(sourceId);
        log.setDelta(delta);
        log.setReason(reason);
        log.setOperatorId(operatorId);
        log.setCreateTime(LocalDateTime.now());
        conductLogMapper.insert(log);

        conductAccountMapper.upsertApply(studentId, term.getId(), rule.getBaseScore(), delta);
    }

    /** 撤销一条手动流水（批67 A1）：删流水并余额反向回补（upsertApply 传负 delta 即回退） */
    public void revokeManual(ConductLog log) {
        conductLogMapper.deleteById(log.getId());
        conductAccountMapper.upsertApply(log.getStudentId(), log.getTermId(),
                getRule().getBaseScore(), log.getDelta().negate());
    }

    /** 规则表单行；异常兜底返回默认 100/90/75/60（表有种子，理论不走到） */
    public ConductRule getRule() {
        ConductRule rule = conductRuleMapper.selectOne(new LambdaQueryWrapper<ConductRule>().last("LIMIT 1"));
        if (rule == null) {
            rule = new ConductRule();
            rule.setBaseScore(BigDecimal.valueOf(100));
            rule.setGradeAMin(BigDecimal.valueOf(90));
            rule.setGradeBMin(BigDecimal.valueOf(75));
            rule.setGradeCMin(BigDecimal.valueOf(60));
        }
        return rule;
    }

    /** 余额 → 等级：≥A线下限为 A，依此类推，低于 C 线为 D */
    public String gradeOf(BigDecimal balance, ConductRule rule) {
        if (balance == null) {
            balance = rule.getBaseScore();
        }
        if (balance.compareTo(rule.getGradeAMin()) >= 0) return "A";
        if (balance.compareTo(rule.getGradeBMin()) >= 0) return "B";
        if (balance.compareTo(rule.getGradeCMin()) >= 0) return "C";
        return "D";
    }

    /** 学期落点（与扬长币同口径） */
    public Long resolveTermId(LocalDate date) {
        return coinLedgerService.resolveTerm(date).getId();
    }

    /** 某生某学期流水，新到旧 */
    public List<ConductLog> logsOf(Long studentId, Long termId) {
        return conductLogMapper.selectList(new LambdaQueryWrapper<ConductLog>()
                .eq(ConductLog::getStudentId, studentId)
                .eq(ConductLog::getTermId, termId)
                .orderByDesc(ConductLog::getId));
    }
}
