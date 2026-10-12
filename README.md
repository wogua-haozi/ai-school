# 石实SHINE · 中学素质成长平台

围绕「**采集数据 → 形成成长档案 → AI 分析 → 教师干预 → 生成成长报告**」建设的中学素质成长平台（生产运行于一所 4082 名学生的学校）。
教师日常记录过程性评价、成绩按校方权限口径由管理员/书记/级长管理，系统自动聚合九维综合素质、成长时间轴、活动、荣誉与综评，AI 生成学业分析与班主任寄语，最终一键渲染 **53 页 1:1 复刻纸质模板**的《学生成长报告册》PDF（支持单生 / 班级 / 全年级批量；学期期中/期末 + 学年 + 在校四期，期中口径锁定学期前半）。

## 功能亮点

- **报告生成**：Playwright 无头渲染 1:1 复刻纸质模板（`学生成长报告册.pdf` 为复刻基准），中文完美嵌入；单份 30 秒内出稿（实测空闲约 16 秒）、全年级 905 份批量零失败（生产实测），批量并发可配、单份优先级插队；聚合数据与设计基线「零漂移」契约验证；学期报告分**期末/期中**两期分别存档（期中数据窗口锁学期前半，补录不渗入），另有学年/在校报告与家长版
- **AI 草稿**：OpenAI 兼容协议接入大模型（DeepSeek/通义/智谱等均可），生成班主任寄语与成长总结草稿；多模态模型可自动识别荣誉证书图片字段；**未配置 key 时自动降级**为规则模板，硬数字始终由规则引擎计算；管理端「AI 设置」页签自助开通/换 key（保存即生效 + 测试连接验证，key 掩码不留痕）；图像 AI 按学生真实数据（九维最强项/成长等级/扬长课程类别）垫图生成 **IP 成长画像**（三级降级：无底图占位框 / 无 key 底图原图 / 有 key AI 图，均不影响报告生成）
- **AI 后台任务队列**：寄语/总结提交后切页面、关浏览器照跑不中断，回来自动恢复；支持整班批量生成，顶部「生成中心」统一查看进行中/已完成任务；多教师共用队列，并发贴合模型供应商限额
- **微光信箱**：教师手机随手拍学生闪光瞬间（照片 + 场景标签 + 圈选学生）→ 班级动态轮播 / 学生「闪光时刻」→ 注入 AI 寄语与总结上下文 → 汇入报告 PDF「成长掠影」版块（无微光学生页数不变，契约不动）
- **教师档案**：工号 / 职称 / 教龄 / 任教学科 / 照片等本人维护（照片存 MinIO，工号全校唯一双保险），管理端教师管理可查看全员档案
- **双端形态**：桌面 Element Plus（报告工作台 + 系统管理七分组）+ 移动 Vant 4 教师工作台（首页宫格按校方 18 项功能清单分三大主区 + 保留区 / 班级 / 生成中心 / 我的 + 中央快捷记录），同一工程同一鉴权，桌面/平板/手机三档适配；App 直接加载服务器前端——**前端更新只需重建 web 容器，老师无需重装 APK**；寄语/评价备注内置语音输入按钮（原生插件，浏览器形态自动隐藏）
- **核心素养评价**：六大核心素养（人文底蕴/科学精神/学会学习/健康生活/责任担当/实践创新）+ 三个校本格（学业扬长/艺术特长/体育锻炼），9 格 24 个二级指标；日常评价、成长报告、报告册九宫格、成长银行联动共用同一套维度；录错全链可撤（冲销与写入严格对称，负流水留痕）
- **OA 审批引擎**：一张表撑多类审批流——公章（固定三级）/ 物资申领 / 场地申请（级数可配 1-3）/ 采购申请（固定五级：部门负责人 → 库存确认 → 主管校领导 → 招采中心 → 采购验收，第五级验收人跟单据走——默认申请人、提交时可改选，任一人验收即过级）；教师请假 2026-10 按校方线下流程要求下线（发起入口与 API 双层拦截，历史单据保留可查）；任意级可配多人**或签**（任一人通过即过级）+ 终态抄送；物资两段式核销（审批通过不扣库存，招采核销出库才扣）；防卡死三件套（配齐校验/删号过滤/管理员代审）；新类型零 DDL 落地
- **家校闭环**：家长凭「学号+邀请码」自助注册绑定（App 内家长端三页签：首页/通知公告/我的，宫格直达成长档案/报告/荣誉/微光/请假记录）；学生请假老师代录、按时长三级折算（≤3 天级长/级部主任一级 / 3-7 天加学成中心 / 7-30 天加书记终审；生活老师限时段登记）→ 门卫端核验放行 + 离校返校登记全链路留痕
- **通知触达**：App 内通知中心（审批待办/请假提醒/系统通知统一送达、点击直达）+ 企业微信群机器人推送 + 服务器分钟级探活告警 + 每周数据体检周报
- **文明班评比**：班级总分自动汇总——检查日基础分（有评价或班级记分记录的日子每天 120）+ 班级整体加减分（卫生检查/全班获奖，对班不对人）；学生日常评价只标记检查日、分数不计入班级总分；历史旧打分口径按「切换日」分段加总不混算；每周自动评选各年级金银铜文明班
- **学校自治**：教师自助注册（审批开通）、班主任工作台（名单导入/本班任课/一键交接班）、学年一键滚动（升年级+毕业归档+文件打包导出）、宿舍楼栋查询、成长银行扬长币兑换「待领取→核销」流程+班级获奖按班发币（8/5/2 档整批可撤销）、谈心随访到期提醒、物资申领两段式核销（招采核销出库才扣库存）、任课表 Excel 追加/覆盖导入、年级级长绑定、生活老师名单导入、**素材库自助换图**（App 首页/登录页/纹理 + 报告 PDF 图库 + IP 画像底图共 17 素材位，上传即生效不用发版）、**报告常用文案表单直改**（校名/简介/格言/办学理念）、请假审批档位上限可调（系统参数）
- **运维与扩容**：新生 Excel 导入、整班调班（升年级/分班）、整班毕业/转出一键流转（历史数据与报告保留可查）；成绩/寄语导出 xlsx；AI 用量统计（按日/按教师 tokens）；Flyway 迁移、写操作审计、登录防爆破、日志轮转、备份脚本；Swagger UI 交互式接口文档
- **RBAC 权限**：11 角色一处收口（`DataScopeService.visibleClassIds`）——管理员/主任/生活老师/书记全校，校领导按分管**学段**，级长按绑定年级，班主任本班，任课教师/招采任课班，家长仅绑定孩子，门卫仅请假核验（18 项越权用例全过）；成绩管理按批49 校方口径收口——普通教师/班主任前后端零入口，管理/书记/级长（绑年级）可建可录，校级领导与 `SCORE_ALL` 授权账号只读
- **一键容器化**：根目录 `docker compose up -d --build` 拉起全栈（MySQL/Redis/MinIO/后端/前端），首启自动导表+种子数据

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + TypeScript + Element Plus（桌面）+ Vant 4（移动）+ Pinia + motion-v |
| 后端 | Spring Boot 3 + MyBatis-Plus + MySQL 8 + Redis |
| 渲染 | Playwright (Chromium) + Thymeleaf + ECharts + Noto CJK 中文字体 |
| 对象存储 | MinIO（报告 PDF / 微光与证书照片 / 档案照片） |
| 部署 | Docker Compose 五服务 + Nginx 反代 |

## 快速开始

前置：已装 Docker（建议主机 ≥ 8GB 内存；后端镜像含 Chromium 与中文字体约 5.5GB）。

```bash
docker compose up -d --build
```

- 平台入口：http://localhost/（前端 80，后端 API 8080 由 Nginx 反代）
- 首次构建会从阿里云拉取 Maven/npm 依赖，耗时较长属预期；再次构建走缓存
- 首次启动自动建库（schema + 种子数据），空库即得可演示数据

### 启用 AI（可选）

```bash
cp docker-compose.override.yml.example docker-compose.override.yml
# 编辑 override 填入你的 API key，然后：
docker compose up -d report-server
```

不配置则 AI 功能自动降级（寄语走规则模板、证书手动录入），其余功能不受影响。详见《docs/接口文档.md》AI 接入与模型切换指南。

### 本机开发（改代码热调，不用重建镜像）

```bash
docker compose up -d mysql redis minio   # 只起基础设施（数据在卷里，与全栈共用）
cd report-renderer && mvn package -q     # 渲染核心先打一次包（后端渲染进程用它的 target/）
cd ../report-server && mvn spring-boot:run   # 后端 8080（连接本机 3306/6379/9000，走 application.yml 默认值）
cd ../report-web && npm install && npm run dev  # 前端 5173（vite 代理 /api → 8080）
```

回归验证（需后端 + 前端 dev 均在跑）：`bash report-server/scripts/run_regression.sh`（本机指定 Python 解释器：`PY=<路径> bash ...`）。
配置无写死路径——仓库可在任意目录克隆，各模块按相对路径互相定位。

## 演示账号

| 账号 | 密码 | 角色 |
|---|---|---|
| admin | admin123 | 学校管理员（全量） |
| litao | aischool123 | 班主任·初一(1)班（50 人，契约基线，请勿改数据；含教师档案演示数据） |
| zhaolaoshi | aischool123 | 班主任·初一(2)班（2 人，演示随意用） |
| wanglaoshi | aischool123 | 任课教师·初一(2)班 |

默认演示学期：termId=2（2026 春季）。MySQL root/aischool123，MinIO aischool/aischool123。

## 目录结构

```
├─ Dockerfile / docker-compose.yml / nginx.conf   # 一键容器化（apt/npm 国内镜像源）
├─ report-web/      # Vue3 前端（桌面 EP + 移动 Vant 双形态，含 e2e 冒烟与 qa 脚本）
├─ report-server/   # Spring Boot 后端（Flyway 迁移 + 审计 + 防爆破）
├─ report-renderer/ # Playwright 渲染核心（53 页报告，golden 生成器=换校单一事实源）
├─ deploy/          # schema.sql / seed.sql / backup.sh
├─ docs/            # 接口文档
├─ .github/         # CI（推送验证后端打包 + 前端构建）
└─ 功能点.md        # 需求原文（第二部分为本项目）
```

## 文档索引

- **docs/接口文档.md** —— REST API 全量说明 + AI 接入与模型切换指南
- **功能点.md** —— 需求原文（第一部分为另一项目，第二部分为本项目 16 个功能点）
- 面向学校的使用手册（含验收演示账号）随交付单独发放，不入公开仓库

## 质量验证

- 服务端回归 10 脚本约 160 断言（契约零漂移 / RBAC 18 / AI 11 / 重试 / 并发 / M6 / M7 89）+ 容器自检脚本
- 前端 E2E 冒烟 108 断言全绿（桌面 1440 + 手机 390 双档：首登改密全流程/批量生成/预览下载/荣誉证书/时间轴/综合素质等）；移动端工作台另有 qa_*.mjs 质检脚本组
- 空库首启自动种子；批量 PDF 中文渲染无方块（无掠影 52 页 / 含微光掠影 53 页）
- 容器化实测：五容器 healthy、nginx:80 端到端、容器内渲染 PDF 中文无方块（2026-08-22）
- k6 稳定性压测框架（`report-server/scripts/perf/`）：登录/通知/请假/报告等核心链路四档（smoke/load/soak/report），thresholds 卡 p95 与错误率，附 docker stats 资源采样；只压本地栈
- 推送到 main 自动跑 CI（后端 mvn package + 前端 npm build）

## 开发与维护须知

1. **E2E 回归必须关闭 AI**（把 override 改名移开后 `docker compose up -d report-server`）：真实大模型延迟 9~61 秒波动，会击穿 E2E 等待并消耗额度；跑完恢复 override 即可。
2. **全新空库跑 m6 E2E 前需先造数据**：seed 只建账号不建活动/荣誉，`verify_m6_web` 依赖已存在的活动与已确认荣誉。
3. `verify_m7_web` 已于 2026-09-08 按 App 化新壳（hash 路由）整族重写并全绿；历史版本的时序抖动经验（复跑即绿）仍适用。
4. **渲染器改动**：`cd report-renderer && mvn package -DskipTests` 即生效（每次渲染 fork 新 JVM 读最新 classpath），无需重启后端；不要 `mvn clean` 后不起服务就期望能渲染（渲染 classpath 在 target/ 下）。
5. **契约基线不入库**（`report-renderer/target/report.pdf` 是本机产物）：新机器或模板改动后刷新基线再跑契约——
   ```bash
   cd report-server && PYTHONIOENCODING=utf-8 python scripts/verify_contract.py   # 渲染学生1 出 target/contract-check/agg.pdf
   cp report-server/target/contract-check/agg.pdf ../report-renderer/target/report.pdf
   # 重跑 verify_contract.py 应双 PASS（① 聚合≡golden ② 53 页标题序列≡基线）
   ```
6. **换校 SOP**（半天级完成一校换装，历史已验证东华→石实）：改 `report-renderer/scripts/expand_golden.py` 的 school 块（intro/九维理念六条/motto，长度同量级防溢出）→ 重跑 expand_golden.py 与 `report-server/scripts/seed_db.py` → 导库（`docker exec -i aischool-mysql mysql --default-character-set=utf8mb4 -uroot -paischool123 ai_school < deploy/seed.sql`）→ 替换 4 张静态图（`img_photo1/2.jpg` 3:2、`img_logo.png` 492×424 椭圆、`img_principal.png` 1414×2000）→ 前端 `public/` 资产与文案 → 按上条刷新契约基线。
7. `tools/m2-repo` 不入库（本机构建加速缓存，gitignore 只留 `.gitkeep`）：克隆后首次 `docker compose build` 由阿里云镜像全量拉取 Maven 依赖，耗时较长属预期。
8. Docker Desktop 偶发整体崩溃（三容器同灭）时后端报 500——先 `docker ps` 看容器，等 healthy 即自愈。
9. MinIO 中报告随重新生成累积（每次生成插入新记录），磁盘配额需关注；`deploy/backup.sh` 建议挂 cron 每日备份。
10. 公网上线检查单（改 JWT 密钥/三件默认密码/关 Swagger/CORS 白名单/演示账号改密等）由维护方内部资料承接。
