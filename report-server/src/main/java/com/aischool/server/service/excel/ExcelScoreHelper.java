package com.aischool.server.service.excel;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.Student;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 成绩 Excel：单班多科一表（学号/姓名/各科目列，批63 按考试设定科目生成），POI 直读直写 */
@Service
public class ExcelScoreHelper {

    /** 一行导入：scores 按科目名带分（该科留空不入 map=不动该科） */
    public record ScoreRow(String studentNo, String name, Map<String, BigDecimal> scores) {}

    /** 读首个工作表：学号/姓名按列位，科目列按表头名匹配（批63）；学号与各科成绩均空的行跳过；
     *  表头不认识的列忽略（等级等多余列）；成绩兼容数值/文本单元格 */
    public List<ScoreRow> read(InputStream in, List<String> subjects) {
        List<ScoreRow> rows = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
            DataFormatter fmt = new DataFormatter();
            var sheet = wb.getSheetAt(0);
            Row head = sheet.getRow(0);
            Map<Integer, String> subjectCols = new LinkedHashMap<>(); // 列位 → 科目名
            if (head != null) {
                for (int c = 2; c <= head.getLastCellNum(); c++) {
                    String h = cell(fmt, head, c);
                    if (subjects.contains(h)) {
                        subjectCols.put(c, h);
                    }
                }
            }
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue; // 表头
                }
                String no = cell(fmt, row, 0);
                String name = cell(fmt, row, 1);
                Map<String, BigDecimal> scores = new LinkedHashMap<>();
                for (var en : subjectCols.entrySet()) {
                    String v = cell(fmt, row, en.getKey());
                    if (v.isBlank()) {
                        continue; // 留空 = 不动该科
                    }
                    try {
                        scores.put(en.getValue(), new BigDecimal(v.trim()));
                    } catch (NumberFormatException e) {
                        throw new BizException(400, "第" + (row.getRowNum() + 1) + "行["
                                + en.getValue() + "]成绩不是数字: " + v);
                    }
                }
                if (no.isBlank() && scores.isEmpty()) {
                    continue;
                }
                rows.add(new ScoreRow(no.trim(), name.trim(), scores));
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "Excel 解析失败（需 .xlsx）: " + e.getMessage());
        }
        return rows;
    }

    private String cell(DataFormatter fmt, Row row, int i) {
        var c = row.getCell(i);
        return c == null ? "" : fmt.formatCellValue(c).trim();
    }

    /** 通用表格导出：表头 + 行数据（Number 按数值写，其余按文本写；null 留空） */
    public byte[] export(String sheetName, String[] headers, List<Object[]> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet(sheetName);
            var head = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                head.createCell(c).setCellValue(headers[c]);
            }
            int i = 1;
            for (Object[] row : rows) {
                var r = sheet.createRow(i++);
                for (int c = 0; c < row.length; c++) {
                    Object v = row[c];
                    if (v instanceof Number n) {
                        r.createCell(c).setCellValue(n.doubleValue());
                    } else if (v != null) {
                        r.createCell(c).setCellValue(String.valueOf(v));
                    }
                }
            }
            for (int c = 0; c < headers.length; c++) {
                sheet.setColumnWidth(c, 16 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "导出失败: " + e.getMessage());
        }
    }

    /** 生成导入模板：表头=学号/姓名/考试各科目（批63 按设定科目），名册预填，成绩留空 */
    public byte[] template(List<Student> roster, List<String> subjects) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("成绩导入");
            var head = sheet.createRow(0);
            head.createCell(0).setCellValue("学号");
            head.createCell(1).setCellValue("姓名");
            for (int c = 0; c < subjects.size(); c++) {
                head.createCell(c + 2).setCellValue(subjects.get(c));
            }
            int i = 1;
            for (Student st : roster) {
                var row = sheet.createRow(i++);
                row.createCell(0).setCellValue(st.getStudentNo());
                row.createCell(1).setCellValue(st.getName());
            }
            for (int col = 0; col < 2 + subjects.size(); col++) {
                sheet.setColumnWidth(col, 16 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "模板生成失败: " + e.getMessage());
        }
    }
}
