package com.aischool.server.service.excel;

import com.aischool.server.common.BizException;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 走班 Excel：sheet1 学生分班（走班名称/学号/姓名）+ sheet2 老师分工（年级/学科/走班名称/教师），POI 直读直写 */
@Service
public class ExcelWalkHelper {

    /** 学生分班行 */
    public record StudentRow(int rowNum, String walkName, String studentNo, String name) {}

    /** 老师分工行（定义走班：年级+学科+名称+教师） */
    public record TeacherRow(int rowNum, String gradeName, String subjectName, String walkName, String teacher) {}

    public record Parsed(List<StudentRow> students, List<TeacherRow> teachers) {}

    /** 读两个工作表：跳过表头；三/四列全空的行跳过（sheet 缺一即报错） */
    public Parsed read(InputStream in) {
        List<StudentRow> students = new ArrayList<>();
        List<TeacherRow> teachers = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
            DataFormatter fmt = new DataFormatter();
            if (wb.getNumberOfSheets() < 2) {
                throw new BizException(400, "Excel 需含两个工作表：学生分班、老师分工");
            }
            for (Row row : wb.getSheetAt(0)) {
                if (row.getRowNum() == 0) {
                    continue; // 表头
                }
                String walk = cell(fmt, row, 0);
                String no = cell(fmt, row, 1);
                String name = cell(fmt, row, 2);
                if (walk.isBlank() && no.isBlank() && name.isBlank()) {
                    continue;
                }
                students.add(new StudentRow(row.getRowNum() + 1, walk, no, name));
            }
            for (Row row : wb.getSheetAt(1)) {
                if (row.getRowNum() == 0) {
                    continue; // 表头
                }
                String grade = cell(fmt, row, 0);
                String subject = cell(fmt, row, 1);
                String walk = cell(fmt, row, 2);
                String teacher = cell(fmt, row, 3);
                if (grade.isBlank() && subject.isBlank() && walk.isBlank() && teacher.isBlank()) {
                    continue;
                }
                teachers.add(new TeacherRow(row.getRowNum() + 1, grade, subject, walk, teacher));
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "Excel 解析失败（需 .xlsx）: " + e.getMessage());
        }
        return new Parsed(students, teachers);
    }

    private String cell(DataFormatter fmt, Row row, int i) {
        var c = row.getCell(i);
        return c == null ? "" : fmt.formatCellValue(c).trim();
    }

    /** 生成导入模板：两个工作表仅表头（不放示例行，避免演示数据被误导入） */
    public byte[] template() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var s1 = wb.createSheet("学生分班");
            String[] h1 = {"走班名称(必填)", "学号(必填)", "姓名(必填)"};
            var head1 = s1.createRow(0);
            for (int i = 0; i < h1.length; i++) {
                head1.createCell(i).setCellValue(h1[i]);
                s1.setColumnWidth(i, 24 * 256);
            }
            var s2 = wb.createSheet("老师分工");
            String[] h2 = {"年级名称(必填)", "学科(必填)", "走班名称(必填)", "教师(登录账号或姓名)(必填)"};
            var head2 = s2.createRow(0);
            for (int i = 0; i < h2.length; i++) {
                head2.createCell(i).setCellValue(h2[i]);
                s2.setColumnWidth(i, 26 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "生成模板失败: " + e.getMessage());
        }
    }
}
