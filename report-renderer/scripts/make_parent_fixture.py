# -*- coding: utf-8 -*-
"""批63 家长版渲染检查 fixture：golden（教师版零漂移基准，勿改）+ examScores 根键派生 parent_check.json。
渲染命令：java -cp "target/report-renderer-0.1.0.jar:target/lib/*" com.aischool.render.RenderPdf target/parent_check.json target/parent_check.pdf parent
断言：PDF 含「期末成绩」页与 A/B/D 三档等级。"""
import json
import pathlib

root = pathlib.Path(__file__).resolve().parents[1]
d = json.load(open(root / 'src/main/resources/golden_student.json', encoding='utf-8'))
d['examScores'] = {'examName': '期末考试', 'subjects': [
    {'name': '语文', 'score': 95, 'fullScore': 100, 'level': 'A'},
    {'name': '数学', 'score': 82, 'fullScore': 100, 'level': 'B'},
    {'name': '英语', 'score': 58, 'fullScore': 100, 'level': 'D'},
]}
out = root / 'target/parent_check.json'
out.parent.mkdir(exist_ok=True)
json.dump(d, open(out, 'w', encoding='utf-8'), ensure_ascii=False)
print(f'fixture ok -> {out}')
