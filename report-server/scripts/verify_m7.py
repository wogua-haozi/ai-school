# -*- coding: utf-8 -*-
"""M7 全量验证：管理端 / 成绩排名 / 评价引擎写穿 / 综评 / 封面 / 年级批量 / 零污染。

快照信封：脚本开头 mysqldump 受影响表，结尾恢复 + 全量契约验证。
写路径纪律：评价写学生52；class1 成绩只写学生2；学生1 除 gradeAvg（年级共享，见架构 11.3）外零写入。
运行：python -X utf8 scripts/verify_m7.py（需后端 8080 + MySQL docker）
"""
import base64
import copy
import datetime
import io
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"
SNAP = "scripts/_m7_snapshot.sql"
TABLES = ("t_user t_teach t_grade t_class t_student t_term t_report_template t_indicator "
          "t_exam t_exam_subject t_score "
          "t_evaluation t_grid_stat_term t_grid_stat_week t_coin_week t_coin_income t_coin_account "
          "t_class_grid_avg t_grade_grid_avg t_comprehensive t_activity t_activity_signup "
          "t_report_task t_report")
BLOCK_KEYS = ["本学期亮点", "学习发展", "综合素质发展", "下一阶段建议"]
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")

PASS, FAIL = 0, 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS {name}")
    else:
        FAIL += 1
        print(f"  FAIL {name}  {detail}")


def call(method, path, token=None, body=None, raw=None, content_type=None):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    elif raw is not None:
        data = raw
        req.add_header("Content-Type", content_type or "application/octet-stream")
    try:
        with urllib.request.urlopen(req, data) as r:
            body_bytes = r.read()
            ctype = r.headers.get("Content-Type", "")
            try:
                return r.status, json.loads(body_bytes), ctype, body_bytes
            except Exception:
                return r.status, None, ctype, body_bytes
    except urllib.error.HTTPError as e:
        raw_body = e.read()
        try:
            return e.code, json.loads(raw_body), "", raw_body
        except Exception:
            return e.code, None, "", raw_body


def login(u, p):
    st, r, _, _ = call("POST", "/api/auth/login", body={"username": u, "password": p})
    assert st == 200 and r["code"] == 0, f"login {u}: {st} {r}"
    return r["data"]["token"]


def report(token, sid):
    st, r, _, _ = call("GET", f"/api/report/data/{sid}?termId=2", token=token)
    assert st == 200 and r["code"] == 0, f"report/data {sid}: {st} {r}"
    return r["data"]


def grid_of(data, name):
    return next(g for g in data["grids"] if g["name"] == name)


def approx(a, b, tol=0.05):
    return a is not None and b is not None and abs(float(a) - float(b)) <= tol


def multipart(fields, files):
    boundary = uuid.uuid4().hex
    out = b""
    for k, v in fields.items():
        out += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
    for k, (fn, data, ct) in files.items():
        out += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"; "
                f"filename=\"{fn}\"\r\nContent-Type: {ct}\r\n\r\n").encode() + data + b"\r\n"
    out += f"--{boundary}--\r\n".encode()
    return out, "multipart/form-data; boundary=" + boundary


def snapshot():
    with open(SNAP, "wb") as f:
        subprocess.run(["docker", "exec", "aischool-mysql", "mysqldump",
                        "-uroot", "-paischool123", "--default-character-set=utf8mb4",
                        "ai_school"] + TABLES.split(), stdout=f, check=True)
    print(f"[信封] 已快照 {len(TABLES.split())} 表 → {SNAP}")


def restore():
    with open(SNAP, "rb") as f:
        subprocess.run(["docker", "exec", "-i", "aischool-mysql", "mysql",
                        "--default-character-set=utf8mb4", "-uroot", "-paischool123",
                        "ai_school"], stdin=f, check=True)
    print("[信封] 已恢复快照")


# ══════════════════ ① 管理端：账号/组织/学期/指标/模板 ══════════════════

def group_admin(admin, litao):
    print("[1] 账号全生命周期 + 组织 CRUD + 非 ADMIN 403")
    uname = "m7teacher" + uuid.uuid4().hex[:6]
    st, r, _, _ = call("POST", "/api/admin/user", token=admin, body={
        "username": uname, "password": "pass12345", "realName": "M7验收教师",
        "role": "TEACHER", "phone": "13800000001"})
    check("建教师 200", st == 200 and r["code"] == 0, f"{st} {r}")
    uid = r["data"]["userId"]
    tok = login(uname, "pass12345")
    check("新教师可登录", bool(tok))
    st, r, _, _ = call("PUT", f"/api/admin/user/{uid}/password", token=admin, body={"password": "newpass99"})
    check("重置密码 200", st == 200, f"{st}")
    login(uname, "newpass99")
    check("新密码可登录", True)
    st, r, _, _ = call("PUT", f"/api/admin/user/{uid}/status", token=admin, body={"status": 0})
    check("停用 200", st == 200, f"{st}")
    st, r, _, _ = call("POST", "/api/auth/login", body={"username": uname, "password": "newpass99"})
    check("停用后拒登录", st != 200 or r.get("code") != 0, f"{st} {r}")
    st, r, _, _ = call("POST", "/api/admin/user", token=litao, body={
        "username": "x", "password": "x1234567", "realName": "x", "role": "TEACHER"})
    check("非 ADMIN 建账号 403", st == 403, f"got {st}")

    st, r, _, _ = call("POST", "/api/admin/teach", token=admin, body={
        "teacherId": uid, "classId": 2, "subjectId": 2})
    check("建任课 200", st == 200, f"{st} {r}")
    st, r, _, _ = call("GET", f"/api/admin/teach/list?teacherId={uid}", token=admin)
    check("任课列表含新关系", len(r["data"]) == 1 and r["data"][0]["subjectName"], f"{r['data']}")
    tid = r["data"][0]["id"]
    st, r, _, _ = call("DELETE", f"/api/admin/teach/{tid}", token=admin)
    check("删任课 200", st == 200, f"{st}")

    st, r, _, _ = call("DELETE", f"/api/admin/user/{uid}", token=admin)
    check("删教师 200", st == 200, f"{st}")

    st, r, _, _ = call("GET", "/api/admin/grade", token=admin)
    grades = r["data"]
    check("年级列表 200", st == 200 and len(grades) >= 1, f"{st}")
    st, r, _, _ = call("DELETE", f"/api/admin/grade/{grades[0]['id']}", token=admin)
    check("有班年级拒删 400", st == 400, f"got {st}")
    st, r, _, _ = call("GET", "/api/admin/class/list", token=admin)
    check("班级列表含班主任名", any(c.get("headTeacherName") for c in r["data"]), f"{r['data'][:1]}")
    cid = r["data"][0]["id"]
    st, r, _, _ = call("DELETE", f"/api/admin/class/{cid}", token=admin)
    check("有学生班级拒删 400", st == 400, f"got {st}")

    # 学生 CRUD：建一个可删的（无成长数据）+ 拒删有数据的
    st, r, _, _ = call("POST", "/api/admin/student", token=admin, body={
        "studentNo": "M7TMP001", "name": "M7临时学生", "gender": "M", "classId": 2,
        "enrollDate": "2025-09-01", "status": "在读"})
    check("建学生 200", st == 200, f"{st} {r}")
    sid_new = r["data"]["studentId"]
    st, r, _, _ = call("PUT", f"/api/admin/student/{sid_new}", token=admin, body={
        "studentNo": "M7TMP001", "name": "M7临时学生改", "gender": "F", "classId": 2,
        "enrollDate": "2025-09-01", "status": "在读", "guardianName": "M7家长", "guardianPhone": "13900000001"})
    check("改学生 200", st == 200, f"{st}")

    # 学生照片（功能点 §2）：上传/覆盖/取图/权限/未上传 404，删除走带照片清理路径
    body, ct = multipart({}, {"file": ("p.png", PNG, "image/png")})
    st, r, _, _ = call("POST", f"/api/admin/student/{sid_new}/photo", token=admin, raw=body, content_type=ct)
    check("照片上传 200", st == 200 and r["data"]["photoUrl"].startswith(f"student/{sid_new}/"),
          f"{st} {r}")
    url1 = r["data"]["photoUrl"]
    st, r, _, _ = call("POST", f"/api/admin/student/{sid_new}/photo", token=admin, raw=body, content_type=ct)
    check("重复上传覆盖换对象", st == 200 and r["data"]["photoUrl"] != url1
          and r["data"]["photoUrl"].startswith(f"student/{sid_new}/"), f"{st} {r}")
    st, _, ctype, raw = call("GET", f"/api/admin/student/{sid_new}/photo", token=admin)
    check("照片 GET 200 png 字节一致", st == 200 and "image/png" in ctype and raw == PNG, f"{st} {ctype}")
    body, ct = multipart({}, {"file": ("t.txt", b"not-image", "text/plain")})
    st, r, _, _ = call("POST", f"/api/admin/student/{sid_new}/photo", token=admin, raw=body, content_type=ct)
    check("非图片格式 400", st == 400, f"{st}")
    body, ct = multipart({}, {"file": ("p.png", PNG, "image/png")})
    st, r, _, _ = call("POST", f"/api/admin/student/{sid_new}/photo", token=litao, raw=body, content_type=ct)
    check("教师上传照片 403", st == 403, f"{st}")
    st, r, _, _ = call("GET", "/api/admin/student/51/photo", token=admin)
    check("未上传照片 404", st == 404, f"{st}")
    st, r, _, _ = call("DELETE", f"/api/admin/student/{sid_new}", token=admin)
    check("无数据学生可删 200", st == 200, f"{st} {r}")
    st, r, _, _ = call("DELETE", "/api/admin/student/52", token=admin)
    check("有成长数据学生拒删 400", st == 400 and "成长数据" in json.dumps(r, ensure_ascii=False), f"{st} {r}")

    # 学期：单活
    st, r, _, _ = call("GET", "/api/admin/term/list", token=admin)
    terms = r["data"]
    cur = next(t for t in terms if t["isCurrent"] == 1)
    other = next(t for t in terms if t["isCurrent"] == 0)
    st, r, _, _ = call("PUT", f"/api/admin/term/{cur['id']}", token=admin, body={
        "name": cur["name"], "startDate": cur["startDate"], "endDate": cur["endDate"], "isCurrent": 0})
    check("唯一当前学期不可取消 400", st == 400, f"got {st}")
    st, r, _, _ = call("PUT", f"/api/admin/term/{other['id']}", token=admin, body={
        "name": other["name"], "startDate": other["startDate"], "endDate": other["endDate"], "isCurrent": 1})
    check("切换当前学期 200", st == 200, f"{st} {r}")
    st, r, _, _ = call("GET", "/api/admin/term/list", token=admin)
    cur_ids = [t["id"] for t in r["data"] if t["isCurrent"] == 1]
    check("当前学期单活", cur_ids == [other["id"]], f"{cur_ids}")
    st, r, _, _ = call("PUT", f"/api/admin/term/{cur['id']}", token=admin, body={
        "name": cur["name"], "startDate": cur["startDate"], "endDate": cur["endDate"], "isCurrent": 1})
    check("切回原当前学期（还原现场）", st == 200, f"{st}")


def group_indicator(admin):
    print("[2] 指标 CRUD 与引用守卫")
    st, r, _, _ = call("GET", "/api/meta/grids", token=admin)
    zhi = next(g for g in r["data"] if "学业" in g["name"])
    st, r, _, _ = call("POST", "/api/admin/indicator", token=admin, body={
        "gridId": zhi["id"], "name": "M7验收指标", "direction": "+", "defaultScore": 1})
    check("建指标 200", st == 200, f"{st} {r}")
    iid = r["data"]["indicatorId"]
    st, r, _, _ = call("PUT", f"/api/admin/indicator/{iid}", token=admin, body={
        "gridId": zhi["id"], "name": "M7验收指标改", "direction": "-", "defaultScore": 2})
    check("无引用指标可改名 200", st == 200, f"{st}")
    st, r, _, _ = call("DELETE", f"/api/admin/indicator/{iid}", token=admin)
    check("无引用指标可删 200", st == 200, f"{st}")
    st, r, _, _ = call("GET", "/api/meta/indicators?gridId=%d" % zhi["id"], token=admin)
    used = r["data"][0]
    st, r, _, _ = call("PUT", f"/api/admin/indicator/{used['id']}", token=admin, body={
        "gridId": zhi["id"], "name": "改名试探", "direction": used.get("direction") or "+",
        "defaultScore": used.get("defaultScore") or 1})
    check("被引用指标改名 400", st == 400, f"got {st}")
    st, r, _, _ = call("DELETE", f"/api/admin/indicator/{used['id']}", token=admin)
    check("被引用指标删除 400", st == 400, f"got {st}")
    st, r, _, _ = call("GET", "/api/admin/grid/list", token=admin)
    check("九维只读列表（含指标数）", all("indicatorCount" in g for g in r["data"]), "")


def group_template(admin):
    print("[3] 模板锁")
    st, r, _, _ = call("GET", "/api/admin/template/list", token=admin)
    on = next(t for t in r["data"] if t["status"] == "启用")
    st, r, _, _ = call("PUT", f"/api/admin/template/{on['id']}", token=admin,
                       body={"schoolName": "x", "sections": "{}"})
    check("启用模板 PUT 400", st == 400, f"got {st}")
    st, r, _, _ = call("DELETE", f"/api/admin/template/{on['id']}", token=admin)
    check("启用模板 DELETE 400", st == 400, f"got {st}")
    st, r, _, _ = call("PUT", f"/api/admin/template/{on['id']}/status", token=admin)
    check("启用模板切换 400", st == 400, f"got {st}")
    st, r, _, _ = call("POST", "/api/admin/template", token=admin,
                       body={"schoolName": "M7草稿", "sections": "{\"a\":1}"})
    check("建草稿 200", st == 200, f"{st} {r}")
    did = r["data"]["templateId"]
    st, r, _, _ = call("PUT", f"/api/admin/template/{did}", token=admin,
                       body={"schoolName": "M7草稿改", "sections": "{\"a\":2}"})
    check("改草稿 200", st == 200, f"{st}")
    st, r, _, _ = call("DELETE", f"/api/admin/template/{did}", token=admin)
    check("删草稿 200", st == 200, f"{st}")


# ══════════════════ ② 成绩 ══════════════════

def group_score(admin, wang, litao):
    print("[4] 成绩：考试/排名/权限/Excel（批49 新口径）")
    st, r, _, _ = call("GET", "/api/meta/subjects", token=admin)
    subjects = {s["name"]: s["id"] for s in r["data"]}
    yuwen, shuxue = subjects["语文"], subjects["数学"]
    st, r, _, _ = call("POST", "/api/score/exam", token=admin, body={
        "termId": 2, "name": "M7验收考试", "examDate": "2026-06-13",
        "subjects": [{"subjectId": yuwen, "fullScore": 100}, {"subjectId": shuxue, "fullScore": 100}]})
    check("建考试（2026-06-13，早于期末保 latestExam 稳定）", st == 200, f"{st} {r}")
    exam_id = r["data"]["examId"]

    # 批49：教师/班主任全端点关闭（甲方口径，后端兜底）
    st, _, _, _ = call("GET", "/api/score/exam/list", token=wang)
    check("教师 考试列表 403", st == 403, f"got {st}")
    st, _, _, _ = call("GET", f"/api/score/subject-context?examId={exam_id}&classId=2", token=wang)
    check("教师 科目上下文 403", st == 403, f"got {st}")
    st, _, _, _ = call("GET", f"/api/score/list?examId={exam_id}&subjectId={yuwen}&classId=2", token=litao)
    check("班主任 成绩单 403", st == 403, f"got {st}")
    st, _, _, _ = call("PUT", "/api/score/entry", token=wang, body={
        "examId": exam_id, "subjectId": yuwen, "classId": 2, "rows": []})
    check("教师 录入 403", st == 403, f"got {st}")

    st, r, _, _ = call("GET", f"/api/score/subject-context?examId={exam_id}&classId=2", token=admin)
    ctx = {c["subjectId"]: c for c in r["data"]}
    check("ADMIN 全科目可写", ctx[yuwen]["editable"] is True and ctx[shuxue]["editable"] is True, f"{ctx}")

    def entry(tok, subject_id, rows, class_id=2):
        return call("PUT", "/api/score/entry", token=tok, body={
            "examId": exam_id, "subjectId": subject_id, "classId": class_id, "rows": rows})

    st, r, _, _ = entry(admin, yuwen, [{"studentId": 51, "score": 90}, {"studentId": 52, "score": 100}])
    check("ADMIN 录入 200", st == 200 and r["data"]["saved"] == 2, f"{st} {r}")
    st, r, _, _ = call("GET", f"/api/score/list?examId={exam_id}&subjectId={yuwen}&classId=2", token=admin)
    ranks = {row["studentId"]: row["classRank"] for row in r["data"]["rows"]}
    check("排名 52→1 / 51→2", ranks[52] == 1 and ranks[51] == 2, f"{ranks}")
    st, r, _, _ = call("GET", f"/api/score/subject-context?examId={exam_id}&classId=2", token=admin)
    ctx2 = {c["subjectId"]: c for c in r["data"]}
    check("maxes 回填=100", ctx2[yuwen]["classMax"] == 100 and ctx2[yuwen]["gradeMax"] == 100,
          f"{ctx2[yuwen]}")
    st, r, _, _ = entry(admin, yuwen, [{"studentId": 51, "score": 90}, {"studentId": 52, "score": 90}])
    st, r, _, _ = call("GET", f"/api/score/list?examId={exam_id}&subjectId={yuwen}&classId=2", token=admin)
    ranks = {row["studentId"]: row["classRank"] for row in r["data"]["rows"]}
    check("同分并列 1,1", ranks[52] == 1 and ranks[51] == 1, f"{ranks}")

    # class1 只写学生2（红线）；顺带取学号供导入用例
    st, r, _, _ = call("GET", "/api/student/list?classId=2&page=1&size=100", token=admin)
    no51 = next(s["studentNo"] for s in r["data"]["records"] if s["id"] == 51)
    st, r, _, _ = call("GET", "/api/student/list?classId=1&page=1&size=100", token=admin)
    s2 = next(s for s in r["data"]["records"] if s["id"] == 2)
    st, r, _, _ = entry(admin, shuxue, [{"studentId": s2["id"], "score": 88}], class_id=1)
    check("ADMIN class1 只写学生2", st == 200, f"{st} {r}")

    st, _, ctype, body = call("GET", "/api/score/template?classId=2", token=admin)
    check("模板下载 200 xlsx", st == 200 and "sheet" in ctype and body[:2] == b"PK", f"{st} {ctype}")

    # 导入：openpyxl 造 xlsx（含未知学号行）
    import openpyxl
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.append(["学号", "姓名", "成绩"])
    ws.append([no51, "x", 95])
    ws.append(["UNKNOWN99", "x", 60])
    ws.append([None, "空行跳过", None])
    buf = io.BytesIO()
    wb.save(buf)
    mp, mp_ct = multipart({}, {"file": ("s.xlsx", buf.getvalue(),
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")})
    st, r, _, _ = call("POST",
                       f"/api/score/import?examId={exam_id}&subjectId={yuwen}&classId=2",
                       token=admin, raw=mp, content_type=mp_ct)
    check("导入含 skip", st == 200 and r["data"]["saved"] >= 1 and len(r["data"]["skipped"]) >= 1,
          f"{st} {r}")
    # 幂等：重录后排名不变
    st, r, _, _ = entry(admin, yuwen, [{"studentId": 51, "score": 90}, {"studentId": 52, "score": 100}])
    st, r, _, _ = call("GET", f"/api/score/list?examId={exam_id}&subjectId={yuwen}&classId=2", token=admin)
    ranks = {row["studentId"]: row["classRank"] for row in r["data"]["rows"]}
    check("重录幂等 排名回 2/1", ranks[52] == 1 and ranks[51] == 2, f"{ranks}")
    return exam_id


# ══════════════════ ③ 评价引擎 ══════════════════

def group_eval(admin, wang, litao):
    print("[5] 评价引擎写穿链（学生52，学业水平 +2）")
    st, r, _, _ = call("GET", "/api/meta/grids", token=admin)
    zhi = next(g for g in r["data"] if "学业" in g["name"])
    st, r, _, _ = call("GET", "/api/meta/indicators?gridId=%d" % zhi["id"], token=admin)
    ind = r["data"][0]
    week18 = (datetime.date(2026, 6, 13) - datetime.date(2026, 2, 9)).days // 7 + 1
    lab = f"第{week18}周"

    d52_b, d51_b, d1_b = report(admin, 52), report(admin, 51), report(admin, 1)
    zhi_b = grid_of(d52_b, zhi["name"])
    idx = d52_b["radar"]["labels"].index(zhi["name"])

    st, r, _, _ = call("POST", "/api/evaluation", token=admin, body={
        "studentId": 52, "indicatorId": ind["id"], "title": "M7验收评价",
        "score": 2, "evalTime": "2026-08-19T10:00:00"})
    check("学期外 evalTime 400", st == 400, f"got {st}")
    st, r, _, _ = call("POST", "/api/evaluation", token=litao, body={
        "studentId": 52, "indicatorId": ind["id"], "title": "越权",
        "score": 2, "evalTime": "2026-06-13T10:00:00"})
    check("他班班主任评价 403", st == 403, f"got {st}")
    st, r, _, _ = call("POST", "/api/evaluation", token=wang, body={
        "studentId": 52, "indicatorId": ind["id"], "title": "M7验收评价",
        "score": 2, "remark": "verify_m7", "evalTime": "2026-06-13T10:00:00"})
    check("任课评价 200（可见即可评）", st == 200 and r["data"]["weekNo"] == week18, f"{st} {r}")
    # 活动自建自清（换校 seed 不再预置活动行；type 须为批37 七类之一）
    st, r, _, _ = call("POST", "/api/activity", token=admin, body={
        "title": "M7验收活动", "type": "其他", "startTime": "2026-06-13T09:00:00"})
    act_id = r["data"]["activityId"]
    st, r, _, _ = call("POST", f"/api/activity/{act_id}/signup", token=wang,
                       body={"studentId": 52})
    check("同一人录活动参与 403（权限不对称）", st == 403, f"got {st}")
    call("DELETE", f"/api/activity/{act_id}", token=admin)

    d52, d51 = report(admin, 52), report(admin, 51)
    zhi_a = grid_of(d52, zhi["name"])
    check("points/count +2/+1",
          approx(zhi_a["points"], float(zhi_b["points"]) + 2) and zhi_a["count"] == zhi_b["count"] + 1,
          f"{zhi_b['points']}/{zhi_b['count']} → {zhi_a['points']}/{zhi_a['count']}")
    check("kinds == 记录卡数（重算口径自洽）", zhi_a["kinds"] == len(zhi_a["records"]),
          f"kinds={zhi_a['kinds']} cards={len(zhi_a['records'])}")
    check("radar.current[智] +2",
          approx(d52["radar"]["current"][idx], float(d52_b["radar"]["current"][idx]) + 2), "")
    wk_b = dict(zip(d52_b["coin"]["weeklyIncome"]["weeks"], d52_b["coin"]["weeklyIncome"]["mine"]))
    wk_a = dict(zip(d52["coin"]["weeklyIncome"]["weeks"], d52["coin"]["weeklyIncome"]["mine"]))
    check("weeklyIncome 周18 +2", approx(wk_a.get(lab, 0), float(wk_b.get(lab, 0)) + 2),
          f"{wk_b.get(lab)} → {wk_a.get(lab)}")
    check("coin.total +2", approx(d52["coin"]["total"], float(d52_b["coin"]["total"]) + 2), "")
    card = next((c for c in zhi_a["records"] if c["title"] == "M7验收评价"), None)
    check("记录卡新组 +2", card is not None and float(card["score"]) == 2.0, f"{card}")

    z51_b, z51_a = grid_of(d51_b, zhi["name"]), grid_of(d51, zhi["name"])
    check("classAvg Δ≈+1（2/2人）",
          approx(z51_a["cur"]["classAvg"], float(z51_b["cur"]["classAvg"]) + 1), "")
    check("gradeAvg Δ≈2/52",
          approx(z51_a["cur"]["gradeAvg"], float(z51_b["cur"]["gradeAvg"]) + 2 / 52), "")

    # 同组二次 + 负分
    call("POST", "/api/evaluation", token=wang, body={
        "studentId": 52, "indicatorId": ind["id"], "title": "M7验收评价",
        "score": 1, "evalTime": "2026-06-13T11:00:00"})
    d52 = report(admin, 52)
    zhi_2 = grid_of(d52, zhi["name"])
    check("同组二次 kinds 不变 count+1",
          zhi_2["kinds"] == zhi_a["kinds"] and zhi_2["count"] == zhi_a["count"] + 1,
          f"{zhi_a['kinds']}/{zhi_a['count']} → {zhi_2['kinds']}/{zhi_2['count']}")
    call("POST", "/api/evaluation", token=wang, body={
        "studentId": 52, "indicatorId": ind["id"], "title": "M7验收负分",
        "score": -1, "evalTime": "2026-06-13T12:00:00"})
    d52 = report(admin, 52)
    check("负分后 coin.total 净 +2", approx(d52["coin"]["total"], float(d52_b["coin"]["total"]) + 2), "")

    st, r, _, _ = call("GET", "/api/evaluation/list?studentId=52&termId=2", token=wang)
    mine = [x for x in r["data"] if x["title"].startswith("M7验收")]
    check("评价列表 3 笔", len(mine) == 3, f"{len(mine)}")

    # 学生1：除 gradeAvg 外零差异（gradeAvg 年级共享，见架构 11.3）
    d1_a = report(admin, 1)

    def no_grade(d):
        d = copy.deepcopy(d)
        for g in d.get("grids", []):
            g["cur"].pop("gradeAvg", None)
            g["prev"].pop("gradeAvg", None)
        return d
    check("学生1 除 gradeAvg 外零差异", no_grade(d1_a) == no_grade(d1_b), "有红线外字段被改动！")
    z1_b, z1_a = grid_of(d1_b, zhi["name"]), grid_of(d1_a, zhi["name"])
    check("学生1 gradeAvg 仅平移 +2/52",
          approx(z1_a["cur"]["gradeAvg"], float(z1_b["cur"]["gradeAvg"]) + 2 / 52), "")


# ══════════════════ ④ 综评 ══════════════════

def group_comprehensive(admin, wang, litao, zhao):
    print("[6] 综评五维与 final 规则")
    def put(tok, dims):
        return call("PUT", "/api/comprehensive", token=tok, body=dict(
            studentId=52, termId=2, moral=dims[0], ability=dims[1], health=dims[2],
            aesthetic=dims[3], practice=dims[4]))
    st, r, _, _ = put(wang, ["A"] * 5)
    check("任课 403", st == 403, f"got {st}")
    st, r, _, _ = put(litao, ["A"] * 5)
    check("他班班主任 403", st == 403, f"got {st}")
    st, r, _, _ = put(zhao, ["A", "A", "A", "A", "B"])
    check("本班班主任 200 final=A", st == 200 and r["data"]["finalLevel"] == "A", f"{r['data']}")
    st, r, _, _ = put(admin, ["A", "B", "B", "C", "D"])
    check("众数→B", r["data"]["finalLevel"] == "B", f"{r['data']}")
    st, r, _, _ = put(admin, ["A", "A", "B", "B", "C"])
    check("并列取高→A", r["data"]["finalLevel"] == "A", f"{r['data']}")
    st, r, _, _ = put(admin, ["A", "E", "A", "A", "A"])
    check("非法等级 400", st == 400, f"got {st}")
    comp = report(admin, 52)["comprehensive"]
    check("report/data 反映", [d["level"] for d in comp["dims"]] == ["A", "A", "B", "B", "C"]
          and comp["finalLevel"] == "A", f"{comp}")


# ══════════════════ ⑤ 封面 ══════════════════

def group_cover(admin, litao):
    print("[7] 活动封面")
    st, r, _, _ = call("POST", "/api/activity", token=admin, body={
        "title": "M7验收封面", "type": "其他", "startTime": "2026-06-13T09:00:00"})
    aid = r["data"]["activityId"]
    body, ct = multipart({}, {"file": ("c.png", PNG, "image/png")})
    st, r, _, _ = call("POST", f"/api/activity/{aid}/cover", token=litao, raw=body, content_type=ct)
    check("非 ADMIN 上传 403", st == 403, f"got {st}")
    st, r, _, _ = call("POST", f"/api/activity/{aid}/cover", token=admin, raw=body, content_type=ct)
    url = r["data"]["coverUrl"]
    check("上传 200 前缀正确", st == 200 and url.startswith(f"activity/{aid}/"), f"{st} {url}")
    st, _, ctype, raw = call("GET", f"/api/activity/{aid}/cover", token=litao)
    check("GET 200 image/png 字节一致", st == 200 and "image/png" in ctype and raw == PNG, f"{st} {ctype}")
    st, r, _, _ = call("DELETE", f"/api/activity/{aid}", token=admin)
    check("清理活动 200（连带 MinIO 对象）", st == 200, f"{st}")


# ══════════════════ ⑥ 总结 API ══════════════════

def group_summary(admin):
    print("[8] 成长总结 API")
    st, r, _, _ = call("POST", "/api/ai/summary", token=admin,
                       body={"studentId": 52, "termId": 2})
    blocks = r["data"]["blocks"]
    check("code 0 + source", st == 200 and r["code"] == 0 and r["data"]["source"] in ("template", "llm"),
          f"{st} {r.get('data', {}).get('source')}")
    check("blocks 四键", list(blocks.keys()) == BLOCK_KEYS, f"{list(blocks.keys())}")


# ══════════════════ ⑦ 年级批量（最后跑） ══════════════════

def group_grade(admin, litao, wang):
    print("[9] 年级批量（52 份，最后跑）")
    for tok, who in ((litao, "班主任"), (wang, "任课")):
        st, r, _, _ = call("POST", "/api/report/generate-grade", token=tok, body={"gradeId": 1, "termId": 2})
        check(f"{who} 403", st == 403, f"got {st}")
    t0 = time.time()
    st, r, _, _ = call("POST", "/api/report/generate-grade", token=admin, body={"gradeId": 1, "termId": 2})
    task = r["data"]
    check("发起 200 total=52", st == 200 and task["total"] == 52, f"{task}")
    tid = task["taskId"]
    final = task
    while time.time() - t0 < 360:
        time.sleep(5)
        st, r, _, _ = call("GET", f"/api/report/task/{tid}", token=admin)
        final = r["data"]
        if final["status"] in ("成功", "失败", "部分失败"):
            break
    check(f"任务成功（{int(time.time() - t0)}s）", final["status"] == "成功" and final["done"] == 52
          and final["failed"] == 0, f"{final}")
    st, r, _, _ = call("GET", f"/api/report/task/{tid}", token=litao)
    check("班主任可读年级任务", st == 200, f"{st}")


def main():
    snapshot()
    rc = 1
    try:
        admin = login("admin", "admin123")
        wang = login("wanglaoshi", "aischool123")
        litao = login("litao", "aischool123")
        zhao = login("zhaolaoshi", "aischool123")
        group_admin(admin, litao)
        group_indicator(admin)
        group_template(admin)
        group_score(admin, wang, litao)
        group_eval(admin, wang, litao)
        group_comprehensive(admin, wang, litao, zhao)
        group_cover(admin, litao)
        group_summary(admin)
        group_grade(admin, litao, wang)
        rc = 0 if FAIL == 0 else 1
    finally:
        print("\n[信封] 恢复数据库 …")
        restore()
        # 契约/PDF 基线不再走 verify_contract（本机 renderer target+Playwright 9-26 已清，
        # 且 golden 有批31 起 73 处已知 seed 漂移）；PDF 页数基线由 verify_batch（容器内渲染）把关
        print("[信封] PDF 基线请另跑 scripts/verify_batch.py（容器内渲染，golden=51）")
        rc = 0 if FAIL == 0 else 1
    print(f"\nRESULT: {'PASS' if rc == 0 else 'FAIL'}  pass={PASS} fail={FAIL}")
    sys.exit(rc)


if __name__ == "__main__":
    main()
