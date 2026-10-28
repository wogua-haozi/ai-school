package com.aischool.server.service.excel;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.Student;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 扬长课程参与 Excel（批72）：模板=单班名册预填+签到/奖项/表现/扬长币列；读回固定列位，POI 直读直写 */
@Service
public class ExcelActivityHelper {

    /** 一行参与（rowNum=Excel 行号报错用；签到/扬长币留原文，合法性由 Controller 判） */
    public record SignupRow(int rowNum, String studentNo, String name, String checkin,
                            String award, String performance, String coin) {}

    /** 读首个工作表：学号/姓名/签到/奖项/表现/扬长币 固定列位；六列全空行跳过 */
    public List<SignupRow> read(InputStream in) {
        List<SignupRow> rows = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
            DataFormatter fmt = new DataFormatter();
            var sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue; // 表头
                }
                String no = cell(fmt, row, 0);
                String name = cell(fmt, row, 1);
                String checkin = cell(fmt, row, 2);
                String award = cell(fmt, row, 3);
                String performance = cell(fmt, row, 4);
                String coin = cell(fmt, row, 5);
                if (no.isBlank() && name.isBlank() && checkin.isBlank() && award.isBlank()
                        && performance.isBlank() && coin.isBlank()) {
                    continue;
                }
                rows.add(new SignupRow(row.getRowNum() + 1, no, name, checkin, award, performance, coin));
            }
        } catch (Exception e) {
            throw new BizException(400, "Excel 解析失败（需 .xlsx）: " + e.getMessage());
        }
        return rows;
    }

    private String cell(DataFormatter fmt, Row row, int i) {
        var c = row.getCell(i);
        return c == null ? "" : fmt.formatCellValue(c).trim();
    }

    /** 生成导入模板：表头=学号/姓名/签到/奖项/表现/扬长币，该班名册预填，其余列留空 */
    public byte[] template(List<Student> roster) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("参与导入");
            var head = sheet.createRow(0);
            String[] headers = {"学号", "姓名", "签到", "奖项", "表现", "扬长币"};
            for (int c = 0; c < headers.length; c++) {
                head.createCell(c).setCellValue(headers[c]);
            }
            int i = 1;
            for (Student st : roster) {
                var row = sheet.createRow(i++);
                row.createCell(0).setCellValue(st.getStudentNo());
                row.createCell(1).setCellValue(st.getName());
            }
            for (int c = 0; c < headers.length; c++) {
                sheet.setColumnWidth(c, 16 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "模板生成失败: " + e.getMessage());
        }
    }
}
