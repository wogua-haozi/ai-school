import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

/**
 * App 化后的路由分三形态（meta.layout）：
 *  - tab：底部 Tab 一级页（首页/班级/通知/我的），App.vue 挂底部 Tabbar
 *  - keepTab：从宫格进入的功能主页（成长记录流等），保留底部 Tabbar 但无激活态
 *  - sub：二级页，App.vue 挂「返回+标题」导航条（meta.title 为空则只有滚动容器，
 *    页面自带 hero，如学生详情）
 */
const router = createRouter({
  // hash 模式：安卓 WebView 无服务端回退路由，深链/重启必须靠 # 路径（Web 端同样可用）
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue'), meta: { layout: 'bare' } },
    // 首登强制改密页（管理员设密/重置/批量导入初始密码后；改完才放行业务）
    { path: '/change-password', component: () => import('../views/ChangePasswordView.vue'), meta: { layout: 'bare' } },
    // 家长自助注册（批8.6）：学号+邀请码绑定，免登录
    { path: '/p/register', component: () => import('../views/ParentRegisterView.vue'), meta: { layout: 'bare' } },

    { path: '/t/register', component: () => import('../views/TeacherRegisterView.vue'), meta: { layout: 'bare' } },
    // 底部 Tab 一级页
    { path: '/', component: () => import('../views/HomeView.vue'), meta: { layout: 'tab', tab: 'home' } },
    { path: '/class', component: () => import('../views/ClassView.vue'), meta: { layout: 'tab', tab: 'class' } },
    { path: '/notice', component: () => import('../views/NoticeView.vue'), meta: { layout: 'tab', tab: 'notice' } },
    { path: '/mine', component: () => import('../views/MineView.vue'), meta: { layout: 'tab', tab: 'mine' } },
    // 功能主页（保留 Tabbar）
    { path: '/feed', component: () => import('../views/FeedView.vue'), meta: { layout: 'keepTab' } },
    // 二级页（meta.title 驱动 App 壳顶部「返回+标题」导航条；学生详情自带 hero 故无 title）
    { path: '/student/:id', component: () => import('../views/StudentDetailView.vue'), meta: { layout: 'sub' } },
    { path: '/moment', component: () => import('../views/MomentListView.vue'), meta: { layout: 'sub', title: '微光瞬间' } },
    { path: '/moment/new', component: () => import('../views/MomentCaptureView.vue'), meta: { layout: 'sub', title: '微光时刻' } },
    // 功能页：挂 App 壳（内容精改按页推进）；admin 保留石实红（管理端体系）
    { path: '/reports', component: () => import('../views/ReportsView.vue'), meta: { layout: 'sub', title: '成长报告' } },
    { path: '/reports/:id/preview', component: () => import('../views/PreviewView.vue'), meta: { layout: 'sub', title: '报告预览' } },
    // 批37 IA 重组：期末评语三合一（寄语/总结/综合素质同链）；旧三路由 redirect 兼容（通知深链+老师肌肉记忆）
    { path: '/final-eval', component: () => import('../views/FinalEvalView.vue'), meta: { layout: 'sub', title: '期末评语' } },
    { path: '/comments', redirect: () => ({ path: '/final-eval', query: { tab: 'comment' } }) },
    { path: '/summary', redirect: () => ({ path: '/final-eval', query: { tab: 'summary' } }) },
    { path: '/activity', component: () => import('../views/ActivityView.vue'), meta: { layout: 'sub', title: '扬长课程' } },
    { path: '/honor', component: () => import('../views/HonorView.vue'), meta: { layout: 'sub', title: '荣誉证书' } },
    { path: '/timeline', component: () => import('../views/TimelineView.vue'), meta: { layout: 'sub', title: '学生时间轴' } },
    { path: '/scores', component: () => import('../views/ScoreView.vue'), meta: { layout: 'sub', title: '成绩管理' } },
    { path: '/evaluate', component: () => import('../views/EvaluateView.vue'), meta: { layout: 'sub', title: '素养评价' } },
    { path: '/comprehensive', redirect: () => ({ path: '/final-eval', query: { tab: 'comp' } }) },
    { path: '/bank', component: () => import('../views/TeacherBankView.vue'), meta: { layout: 'sub', title: '成长银行' } },
    { path: '/conduct-rules', component: () => import('../views/ConductRulesView.vue'), meta: { layout: 'sub', title: '德育规范' } },
    // 行政办公（批9）：公章三级审批+物资申领（OA 引擎）；批10 加教师请假
    { path: '/oa', component: () => import('../views/OaView.vue'), meta: { layout: 'sub', title: '行政办公' } },
    // 场地预约（批53③ 重构）：立即预约列表 + 会议总览；详情页半小时格连选发起申请
    { path: '/venue', component: () => import('../views/VenueView.vue'), meta: { layout: 'sub', title: '场地预约' } },
    { path: '/venue/:id', component: () => import('../views/VenueDetailView.vue'), meta: { layout: 'sub', title: '场地详情' } },
    // 报修（批10）：文字+拍照凭证，工单直达管理端
    { path: '/repair', component: () => import('../views/RepairView.vue'), meta: { layout: 'sub', title: '报修' } },
    // 导师谈心（批11）：教师对可见班级学生，管理端全量
    { path: '/talk', component: () => import('../views/TalkView.vue'), meta: { layout: 'sub', title: '导师谈心' } },
    // 学生请假（批32）：教师代录（家长微信/电话告知），按时长分级审批（级长/学成中心主任），任课教师不可见
    { path: '/leave', component: () => import('../views/StudentLeaveView.vue'), meta: { layout: 'sub', title: '学生请假' } },
    // 宿管查询（批27）：全体教师按宿舍楼/房/床查学生
    { path: '/dorm', component: () => import('../views/DormView.vue'), meta: { layout: 'sub', title: '宿舍查询' } },
    // 意见反馈（批8.5）：教师/家长共用组件，双路由分流（家长被锁 /p/*）
    { path: '/feedback', component: () => import('../views/FeedbackView.vue'), meta: { layout: 'sub', title: '意见反馈' } },
    // 消息通知（批29，批37 由「通知中心」正名）：审批/请假/注册待办与结果，点击直达处理页；同款双路由分流
    { path: '/notify', component: () => import('../views/NotificationView.vue'), meta: { layout: 'sub', title: '消息通知' } },
    // 通知公告（批37 教师端入口）：家长版组件双轨复用（API/详情深链按角色分流），全校范围
    { path: '/notices', component: () => import('../views/parent/ContentListView.vue'), props: { type: 'NOTICE' }, meta: { layout: 'sub', title: '通知公告' } },
    { path: '/notices/:id', component: () => import('../views/parent/ContentDetailView.vue'), meta: { layout: 'sub', title: '通知详情' } },
    // App 内使用帮助（批37 生态补缺）：FAQ 按角色分流（教师/家长双路由共用组件）
    { path: '/help', component: () => import('../views/HelpView.vue'), meta: { layout: 'sub', title: '使用帮助' } },
    // 文明班评比 B 案（批30）：教师打分+自动汇总排名（细则见 /conduct-rules A 案静态页）
    { path: '/civility', component: () => import('../views/CivilityView.vue'), meta: { layout: 'sub', title: '文明班评比' } },
    // 批37 IA 重组：我的成长三合一（教师档案/风采/足迹）；旧三路由 redirect 兼容
    { path: '/my-growth', component: () => import('../views/MyGrowthView.vue'), meta: { layout: 'sub', title: '我的成长' } },
    { path: '/profile', redirect: () => ({ path: '/my-growth', query: { tab: 'profile' } }) },
    { path: '/teacher-honor', redirect: () => ({ path: '/my-growth', query: { tab: 'honor' } }) },
    { path: '/teacher-honor/new', component: () => import('../views/TeacherHonorCaptureView.vue'), meta: { layout: 'sub', title: '记录成就' } },
    { path: '/footprint', redirect: () => ({ path: '/my-growth', query: { tab: 'footprint' } }) },
    // 教师月度操作自查（批70）：本人各板块操作次数，自然月切换（家长/门卫角色后端 403）
    { path: '/usage-my', component: () => import('../views/UsageMyView.vue'), meta: { layout: 'sub', title: '我的操作统计' } },
    { path: '/admin', component: () => import('../views/AdminView.vue'), meta: { layout: 'sub', title: '系统管理', admin: true } },
    // 家长端（PARENT 分流，方案C 新中式风格 --shine-*）：孩子卡+评价动态；报告批5 家长版再开
    { path: '/p/home', component: () => import('../views/parent/ParentHomeView.vue'), meta: { layout: 'ptab', tab: 'phome' } },
    { path: '/p/child/:id', component: () => import('../views/parent/ParentChildView.vue'), meta: { layout: 'psub', title: '孩子成长' } },
    { path: '/p/mine', component: () => import('../views/parent/ParentMineView.vue'), meta: { layout: 'ptab', tab: 'pmine' } },
    // 内容（批2；批37 升底部 Tab 一级入口；批40a 内容型扬长课程下线，仅存通知公告）
    { path: '/p/notices', component: () => import('../views/parent/ContentListView.vue'), props: { type: 'NOTICE' }, meta: { layout: 'ptab', tab: 'pnotice' } },
    { path: '/p/content/:id', component: () => import('../views/parent/ContentDetailView.vue'), meta: { layout: 'psub', title: '内容详情' } },
    // 家长微光时刻（批2-3 方案A）：拍照仅进孩子成长档案（家长+班主任可见）
    { path: '/p/moments', component: () => import('../views/parent/ParentMomentView.vue'), meta: { layout: 'psub', title: '微光时刻' } },
    // 扬长课程（批72d 家长只读）：课程列表+封面，视频为外链点击跳转（平台播放器承接）
    { path: '/p/activity', component: () => import('../views/parent/ParentActivityView.vue'), meta: { layout: 'psub', title: '扬长课程' } },
    // 成长档案（批14 生命周期）：在校全期事件流+总览
    { path: '/p/archive/:id', component: () => import('../views/parent/ParentArchiveView.vue'), meta: { layout: 'psub', title: '成长档案' } },
    { path: '/p/honor', component: () => import('../views/parent/ParentHonorView.vue'), meta: { layout: 'psub', title: '荣誉证书' } },
    // 成绩查询（批63）：分数+等级（无排名），等级按满分比例现算
    { path: '/p/scores', component: () => import('../views/parent/ParentScoreView.vue'), meta: { layout: 'psub', title: '成绩查询' } },
    // 成长报告（批5 家长版）：同任务双渲染的去成绩版 PDF，仅 parent_file_url 可达
    { path: '/p/report', component: () => import('../views/parent/ParentReportView.vue'), meta: { layout: 'psub', title: '成长报告' } },
    // 意见反馈（批8.5）：家长入口
    { path: '/p/feedback', component: () => import('../views/FeedbackView.vue'), meta: { layout: 'psub', title: '意见反馈' } },
    // 学生请假（批32）：家长只读（微信/电话联系班主任办理，App 收通知+查记录）
    { path: '/p/leave', component: () => import('../views/parent/ParentLeaveView.vue'), meta: { layout: 'psub', title: '请假记录' } },
    // 消息通知（批29）：家长入口（审批结果/系统消息）
    { path: '/p/notify', component: () => import('../views/NotificationView.vue'), meta: { layout: 'psub', title: '消息通知' } },
    // 使用帮助（批37）：家长入口
    { path: '/p/help', component: () => import('../views/HelpView.vue'), meta: { layout: 'psub', title: '使用帮助' } },
    // 门卫端（批27 GUARD 分流）：仅此一页——当日有效请假核验+离校/返校登记
    { path: '/g/home', component: () => import('../views/GuardHomeView.vue'), meta: { layout: 'gshell' } },
    // 领导端（LEADER 分流）：全校只读驾驶舱；成绩明细复用 /scores（LEADER 只读）；
    // 教师使用情况（批2）：六类行为按师聚合，psub 壳（第一版 sub-nav 形态+C 令牌）
    { path: '/l/home', component: () => import('../views/leader/LeaderHomeView.vue'), meta: { layout: 'lhome' } },
    { path: '/l/teachers', component: () => import('../views/leader/LeaderTeachersView.vue'), meta: { layout: 'psub', title: '教师使用情况' } },
    // 全校成绩汇总（批4）：总分/单科排名+各班统计+导出（方案A 领导全可见）
    { path: '/l/scores', component: () => import('../views/leader/LeaderScoreSummaryView.vue'), meta: { layout: 'psub', title: '成绩汇总排名' } },
    // 账号审批（批2-5）：管理员/领导账号双人审批，领导 App 端入口
    { path: '/l/approvals', component: () => import('../views/leader/LeaderApprovalsView.vue'), meta: { layout: 'psub', title: '待我审批' } },
    // 原「批量任务」页（/）已并入通知页
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.path !== '/login' && to.path !== '/change-password' && to.path !== '/p/register'
    && to.path !== '/t/register' && !auth.token) return '/login'
  // 首登强制改密：未改密前一切页面都拦到改密页（改密页/登录页除外）
  if (auth.mustChangePwd && to.path !== '/change-password' && to.path !== '/login') {
    return '/change-password'
  }
  // 角色分流：PARENT 锁 /p/*；GUARD 锁 /g/*（门卫仅开放请假核验，9-26 甲方口径）；
  // 教师/管理员/领导走现状路由——批3.5 领导教师化：领导不再锁 /l/*，教师功能全量可用，/l/* 仅领导可进
  const role = auth.role
  // /p/register、/t/register 免登开放（未登录 role 为空，不能被角色锁拦回）
  const free = to.path === '/login' || to.path === '/change-password'
    || to.path === '/p/register' || to.path === '/t/register'
  if (role === 'PARENT' && !free && !to.path.startsWith('/p/')) return '/p/home'
  if (role !== 'PARENT' && !free && to.path.startsWith('/p/')) return '/'
  if (role === 'GUARD' && !free && !to.path.startsWith('/g/')) return '/g/home'
  if (role !== 'GUARD' && !free && to.path.startsWith('/g/')) return '/'
  // 批49 开放汇总入口；批63 收口为排名可见角色；批64 领导层（书记/领导/主任）全量进驾驶舱
  if (!['LEADER', 'SECRETARY', 'DIRECTOR'].includes(role) && to.path.startsWith('/l/')
    && !(to.path === '/l/scores' && auth.canSeeRank)) return '/'
})

export default router
