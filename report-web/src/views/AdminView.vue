<template>
  <div class="page" :class="{ 'admin-desktop': desktop }">
    <!-- 桌面 ≥1024：侧栏形态（c/admin-desktop.html 设计稿；App 壳此形态下不渲染返回导航条） -->
    <template v-if="desktop">
      <aside class="aside">
        <div class="logo">
          <img src="/badge.png" alt="校徽">
          <div class="lb">石实<em>SHINE</em><small>成长平台 · 管理端</small></div>
        </div>
        <nav class="nav">
          <template v-for="g in groups" :key="g.label">
            <div class="grp">{{ g.label }}</div>
            <button v-for="t in g.items" :key="t.name" class="nv" type="button"
              :class="{ on: tab === t.name }" @click="tab = t.name">
              <el-icon><component :is="t.icon" /></el-icon>{{ t.label }}
              <i v-if="t.name === 'roleRequest' && pendingCount > 0" class="badge">{{ pendingCount > 99 ? '99+' : pendingCount }}</i>
              <i v-else-if="t.name === 'health' && healthDanger > 0" class="badge">{{ healthDanger }}</i>
              <i v-else-if="t.name === 'feedback' && feedbackPending > 0" class="badge">{{ feedbackPending > 99 ? '99+' : feedbackPending }}</i>
            </button>
          </template>
        </nav>
        <div class="ft">石实SHINE · v2.0</div>
      </aside>
      <div class="main">
        <header class="top">
          <span class="crumb">首页 / <b>{{ current.label }}</b></span>
          <div v-if="online.users !== null" class="chips">
            <span class="chip">在线 <b>{{ online.onlineCount }}</b></span>
            <span class="chip act" @click="showDaily = true">24h 活跃 <b>{{ online.dailyCount }}</b> 人</span>
            <el-tooltip v-if="online.users.length" placement="bottom">
              <template #content>
                <div v-for="u in online.users" :key="u.userId">{{ u.realName }}（{{ u.username }}）· {{ ago(u.lastActiveMs) }}</div>
              </template>
              <span class="who">在线名单</span>
            </el-tooltip>
          </div>
          <div class="user">
            <div class="uav">{{ auth.realName?.charAt(0) || '管' }}</div>
            <div><span>{{ auth.realName }}</span><small>{{ roleLabel }}</small></div>
          </div>
        </header>
        <main class="body">
          <div class="pt"><div class="mei"></div><h1>{{ current.label }}</h1></div>
          <component :is="current.comp" @handled="loadPendingCount" />
        </main>
      </div>
    </template>

    <!-- 窄屏 <1024（批37）：两级形态——分组落地宫格 → 组内页签（替代 24 连 tabs） -->
    <template v-else>
      <motion.h2 class="page-title" :initial="{ opacity: 0, x: -16 }" :animate="{ opacity: 1, x: 0 }"
        :transition="{ type: 'spring', stiffness: 400, damping: 32 }"><el-icon><Setting /></el-icon>系统管理</motion.h2>
      <div v-if="online.users !== null" class="online-bar">
        <span class="dot"></span>
        当前在线 <b>{{ online.onlineCount }}</b> 人
        <span class="sep">·</span>
        24 小时活跃 <button class="lnk" type="button" @click="showDaily = true"><b>{{ online.dailyCount }}</b> 人</button>
        <el-tooltip v-if="online.users.length" placement="bottom">
          <template #content>
            <div v-for="u in online.users" :key="u.userId">{{ u.realName }}（{{ u.username }}）· {{ ago(u.lastActiveMs) }}</div>
          </template>
          <span class="who">在线名单</span>
        </el-tooltip>
      </div>
      <!-- 第一级：7 分组宫格（角标=组内待办合计） -->
      <div v-if="!openGroup" class="grp-grid">
        <button v-for="g in groups" :key="g.key" class="grp-card" type="button" @click="enterGroup(g.key)">
          <i v-if="groupBadge(g)" class="n-badge">{{ groupBadge(g) > 99 ? '99+' : groupBadge(g) }}</i>
          <el-icon><component :is="g.icon" /></el-icon>
          <b>{{ g.label }}</b>
          <span>{{ g.items.slice(0, 2).map((t) => t.label).join(' · ') }} 等 {{ g.items.length }} 项</span>
        </button>
      </div>

      <!-- 第二级：返回行 + 本组页签（审批/反馈/体检计数标签与事件由 paneLabel/tabEvents 收拢） -->
      <template v-else>
        <div class="grp-back">
          <button type="button" @click="openKey = null"><el-icon><ArrowLeftBold /></el-icon>全部分类</button>
          <span>{{ openGroup.label }}</span>
        </div>
        <el-tabs v-model="tab">
          <el-tab-pane v-for="t in openGroup.items" :key="t.name" :label="paneLabel(t)" :name="t.name">
            <component :is="t.comp" v-bind="tabEvents(t.name)" />
          </el-tab-pane>
        </el-tabs>
      </template>
    </template>

    <!-- 批56增补：近 24h 登录名单（点统计条「24h 活跃」展开；两种形态共用） -->
    <el-dialog v-model="showDaily" :title="`近 24 小时登录（${online.dailyCount} 人）`" width="min(560px, 92vw)">
      <div class="login-list">
        <div v-for="u in online.dailyUsers" :key="u.username" class="login-row">
          <div class="uav">{{ (u.realName || '?').charAt(0) }}</div>
          <div class="mid">
            <b>{{ u.realName }}</b>
            <small>{{ u.username }} · {{ roleCn(u.role) }}</small>
          </div>
          <div class="rgt">
            <b>{{ fmtLast(u.lastAt) }}</b>
            <small>登录 {{ u.tries }} 次</small>
          </div>
        </div>
        <p v-if="!online.dailyUsers.length" class="empty">近 24 小时暂无登录记录</p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, markRaw, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { motion } from 'motion-v'
import { Aim, AlarmClock, ArrowLeftBold, Calendar, ChatDotRound, ChatLineRound, Coin, DataLine, Document, FirstAidKit, FolderOpened, Goods as GoodsIcon, Histogram, Iphone, Medal, OfficeBuilding, Picture, Postcard, Promotion, School, Setting, Stamp, Sunset, Tickets, Tools, TrendCharts, Upload, User, Avatar } from '@element-plus/icons-vue'
import { api } from '../api/http'
import { useAuthStore } from '../stores/auth'
import TeacherTab from '../components/admin/TeacherTab.vue'
import ParentTab from '../components/admin/ParentTab.vue'
import RoleRequestTab from '../components/admin/RoleRequestTab.vue'
import FeedbackTab from '../components/admin/FeedbackTab.vue'
import HealthTab from '../components/admin/HealthTab.vue'
import ContentTab from '../components/admin/ContentTab.vue'
import TeacherProfileTab from '../components/admin/TeacherProfileTab.vue'
import OrgTab from '../components/admin/OrgTab.vue'
import StudentTab from '../components/admin/StudentTab.vue'
import TermTab from '../components/admin/TermTab.vue'
import ExamTab from '../components/admin/ExamTab.vue'
import ScoreTab from '../components/admin/ScoreTab.vue'
import DutyTab from '../components/admin/DutyTab.vue'
import CivilityTab from '../components/admin/CivilityTab.vue'
import IndicatorTab from '../components/admin/IndicatorTab.vue'
import ShopTab from '../components/admin/ShopTab.vue'
import TemplateTab from '../components/admin/TemplateTab.vue'
import AuditTab from '../components/admin/AuditTab.vue'
import AiSettingsTab from '../components/admin/AiSettingsTab.vue'
import SysParamTab from '../components/admin/SysParamTab.vue'
import AssetTab from '../components/admin/AssetTab.vue'
import AppReleaseTab from '../components/admin/AppReleaseTab.vue'
import OaTab from '../components/admin/OaTab.vue'
import GoodsTab from '../components/admin/GoodsTab.vue'
import RepairTab from '../components/admin/RepairTab.vue'
import VenueTab from '../components/admin/VenueTab.vue'
import TalkTab from '../components/admin/TalkTab.vue'
import FootprintTab from '../components/admin/FootprintTab.vue'
import SchoolYearTab from '../components/admin/SchoolYearTab.vue'
import ArchiveTab from '../components/admin/ArchiveTab.vue'
import NotifyTab from '../components/admin/NotifyTab.vue'

const auth = useAuthStore()

/* 支持 ?tab= 直达指定页签（首页快捷功能「教师档案」入口用）；已打开时 query 变化也跟随 */
const route = useRoute()
const tab = ref((route.query.tab as string) || 'teacher')
watch(() => route.query.tab, (t) => {
  if (typeof t === 'string' && t !== tab.value) {
    tab.value = t
    const g = groupOf(t)
    if (g && openKey.value !== g.key) openKey.value = g.key   // 窄屏直达时展开所属分组
  }
})

/* 桌面 ≥1024 切侧栏形态（与 style.css 三档断点一致；App 壳同条件隐藏返回条） */
const desktop = ref(window.matchMedia('(min-width: 1024px)').matches)
const mq = window.matchMedia('(min-width: 1024px)')
const onMq = (e: MediaQueryListEvent) => { desktop.value = e.matches }

/* 侧栏导航：12 个页签分四组；comp=markRaw 防组件被响应式代理 */
const TABS: Record<string, { label: string; comp: any; icon: any }> = {
  teacher: { label: '教师与任课', comp: markRaw(TeacherTab), icon: User },
  parent: { label: '家长账号', comp: markRaw(ParentTab), icon: Iphone },
  roleRequest: { label: '账号审批', comp: markRaw(RoleRequestTab), icon: Stamp },
  feedback: { label: '意见反馈', comp: markRaw(FeedbackTab), icon: ChatDotRound },
  health: { label: '数据体检', comp: markRaw(HealthTab), icon: FirstAidKit },
  archive: { label: '文件归档', comp: markRaw(ArchiveTab), icon: FolderOpened },
  notify: { label: '群机器人与告警', comp: markRaw(NotifyTab), icon: Promotion },
  teacherProfile: { label: '教师档案', comp: markRaw(TeacherProfileTab), icon: Postcard },
  content: { label: '内容发布', comp: markRaw(ContentTab), icon: Promotion },
  appRelease: { label: '版本更新', comp: markRaw(AppReleaseTab), icon: Upload },
  org: { label: '年级与班级', comp: markRaw(OrgTab), icon: School },
  student: { label: '学生', comp: markRaw(StudentTab), icon: Avatar },
  term: { label: '学期', comp: markRaw(TermTab), icon: Calendar },
  schoolYear: { label: '学年滚动', comp: markRaw(SchoolYearTab), icon: Sunset },
  exam: { label: '考试管理', comp: markRaw(ExamTab), icon: TrendCharts },
  score: { label: '成绩管理', comp: markRaw(ScoreTab), icon: Histogram },
  duty: { label: '值班排班', comp: markRaw(DutyTab), icon: AlarmClock },
  civility: { label: '文明班评比', comp: markRaw(CivilityTab), icon: Medal },
  indicator: { label: '育人指标', comp: markRaw(IndicatorTab), icon: Aim },
  shop: { label: '成长银行', comp: markRaw(ShopTab), icon: Coin },
  template: { label: '报告模板', comp: markRaw(TemplateTab), icon: Document },
  audit: { label: '审计日志', comp: markRaw(AuditTab), icon: Tickets },
  aiUsage: { label: 'AI 设置', comp: markRaw(AiSettingsTab), icon: DataLine },
  sysParam: { label: '系统参数', comp: markRaw(SysParamTab), icon: Setting },
  asset: { label: '素材库', comp: markRaw(AssetTab), icon: Picture },
  oa: { label: 'OA 审批', comp: markRaw(OaTab), icon: Stamp },
  goods: { label: '物资管理', comp: markRaw(GoodsTab), icon: GoodsIcon },
  repair: { label: '报修处理', comp: markRaw(RepairTab), icon: Tools },
  venue: { label: '场地管理', comp: markRaw(VenueTab), icon: OfficeBuilding },
  talk: { label: '导师谈心', comp: markRaw(TalkTab), icon: ChatLineRound },
  footprint: { label: '教师足迹', comp: markRaw(FootprintTab), icon: Medal },
}
/* 批37 IA 重组：30 页签按职能分 7 组（桌面侧栏=7 段；App 窄屏=两级「分组落地→组内页签」）；
   原「基础数据/系统运维」两个杂物间拆散归位：物资/场地/归档→总务后勤，谈心/足迹→德育激励等 */
const groups = [
  { key: 'people', label: '人员账号', icon: User, items: ['teacher', 'parent', 'roleRequest', 'teacherProfile'] },
  { key: 'edu', label: '教学教务', icon: School, items: ['org', 'student', 'term', 'schoolYear', 'exam', 'score', 'duty', 'template'] },
  { key: 'moral', label: '德育激励', icon: Medal, items: ['civility', 'indicator', 'shop', 'talk', 'footprint'] },
  { key: 'logi', label: '总务后勤', icon: OfficeBuilding, items: ['goods', 'repair', 'venue', 'archive'] },
  { key: 'appr', label: '审批与反馈', icon: Stamp, items: ['oa', 'feedback'] },
  { key: 'pub', label: '内容发布', icon: Promotion, items: ['content', 'appRelease', 'asset'] },
  { key: 'ops', label: '系统运维', icon: Setting, items: ['health', 'notify', 'audit', 'aiUsage', 'sysParam'] },
].map((g) => ({ ...g, items: g.items.map((k) => ({ name: k, ...TABS[k] })) }))

const openKey = ref<string | null>(null)
/** 窄屏两级形态当前组；桌面端不用（侧栏全量展示） */
const openGroup = computed(() => groups.find((g) => g.key === openKey.value) ?? null)
function groupOf(name: string) { return groups.find((g) => g.items.some((t) => t.name === name)) }
function enterGroup(key: string) {
  openKey.value = key
  const g = groups.find((x) => x.key === key)!
  if (!g.items.some((t) => t.name === tab.value)) tab.value = g.items[0].name
}
/* ?tab= 直达时自动展开所属分组（如教师端「我的成长」跳 /admin?tab=teacherProfile） */
if (route.query.tab) { const g0 = groupOf(route.query.tab as string); if (g0) openKey.value = g0.key }
/* 落地页角标：组内待办合计（账号审批/意见反馈/数据体检） */
function groupBadge(g: { items: { name: string }[] }) {
  let n = 0
  if (g.items.some((t) => t.name === 'roleRequest')) n += pendingCount.value
  if (g.items.some((t) => t.name === 'feedback')) n += feedbackPending.value
  if (g.items.some((t) => t.name === 'health')) n += healthDanger.value
  return n
}
/* 组内页签事件透传（v-bind 展开 onXxx 即监听器；处理完即时刷新徽标） */
function tabEvents(name: string) {
  if (name === 'roleRequest') return { onHandled: loadPendingCount }
  if (name === 'feedback') return { onHandled: loadFeedbackPending }
  if (name === 'health') return { onScanned: onHealthScanned }
  return {}
}
/* 计数页签标签（原三处动态 label 收拢到一处） */
function paneLabel(t: { name: string; label: string }) {
  if (t.name === 'roleRequest' && pendingCount.value) return `账号审批(${pendingCount.value})`
  if (t.name === 'feedback' && feedbackPending.value) return `意见反馈(${feedbackPending.value})`
  if (t.name === 'health' && healthDanger.value) return `数据体检(${healthDanger.value})`
  return t.label
}

const current = computed(() => TABS[tab.value] ?? TABS.teacher)

const roleLabel = computed(() =>
  auth.role === 'ADMIN' ? '管理员' : auth.role === 'LEADER' ? '领导' : '教师')

/* 待审批徽标（批2-5）：与在线统计同轮询刷新；RoleRequestTab 处理完即时回调 */
const pendingCount = ref(0)
async function loadPendingCount() {
  try {
    const d = await api<{ pending: number }>('/api/role-request/count')
    pendingCount.value = d.pending
  } catch { /* 静默（非审批角色不显示） */ }
}

/* 意见反馈待处理数（批8.5 徽标；FeedbackTab 处理完即时回调） */
const feedbackPending = ref(0)
async function loadFeedbackPending() {
  try {
    const d = await api<{ pending: number }>('/api/admin/feedback/count')
    feedbackPending.value = d.pending
  } catch { /* 静默 */ }
}

/* 数据体检高危组数（批8.5 徽标；HealthTab 扫描完即时回调） */
const healthDanger = ref(0)
function onHealthScanned(danger: number) {
  healthDanger.value = danger
}

/* 在线统计：桌面顶栏 chips / 窄屏统计条共用；30 秒轮询，失败静默。
   批56增补：dailyUsers=近 24h 登录名单（审计表口径），点「24h 活跃」弹窗查看 */
const online = reactive<{ onlineCount: number; dailyCount: number; users: any[] | null; dailyUsers: any[] }>({
  onlineCount: 0, dailyCount: 0, users: null, dailyUsers: [],
})
const showDaily = ref(false)
let onlineTimer: number | undefined

async function loadOnline() {
  try {
    const d = await api<{ onlineCount: number; dailyCount: number; onlineUsers: any[]; dailyUsers: any[] }>('/api/admin/online')
    online.onlineCount = d.onlineCount
    online.dailyCount = d.dailyCount
    online.users = d.onlineUsers
    online.dailyUsers = d.dailyUsers ?? []
  } catch { /* 静默 */ }
}

/** 角色中文（登录名单用；没映射到的原样展示） */
function roleCn(role: string) {
  const m: Record<string, string> = {
    ADMIN: '管理员', LEADER: '领导', SECRETARY: '书记', GRADE_LEADER: '级长',
    HEAD_TEACHER: '班主任', TEACHER: '教师', DIRECTOR: '学成中心主任',
    DORM: '生活老师', GUARD: '门卫', PROCUREMENT: '招采', PARENT: '家长',
  }
  return m[role] || role || '—'
}

/** "2026-10-13T21:52:29" → "10-13 21:52" */
function fmtLast(iso: string) {
  return typeof iso === 'string' && iso.length >= 16 ? iso.slice(5, 16).replace('T', ' ') : '—'
}

function ago(ms: number) {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000))
  return s < 60 ? `${s} 秒前活跃` : `${Math.round(s / 60)} 分钟前活跃`
}

onMounted(() => {
  loadOnline()
  loadPendingCount()
  loadFeedbackPending()
  onlineTimer = window.setInterval(() => { loadOnline(); loadPendingCount() }, 30_000)
  mq.addEventListener('change', onMq)
})
onUnmounted(() => {
  window.clearInterval(onlineTimer)
  mq.removeEventListener('change', onMq)
})
</script>

<style scoped>
.online-bar { display: flex; align-items: center; gap: 8px; margin: 0 0 14px; font-size: 13px; color: var(--el-text-color-secondary); }
.online-bar b { color: var(--el-color-primary); font-size: 15px; }
.online-bar .sep { color: var(--el-border-color-darker); }
.online-bar .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--el-color-success); animation: online-pulse 2s infinite; }
.who { cursor: help; text-decoration: underline dotted; font-size: 12px; color: var(--el-text-color-secondary); }
.online-bar .lnk { border: none; background: none; padding: 0; font: inherit; color: inherit;
  cursor: pointer; text-decoration: underline dotted; }

/* 批56增补：24h 登录名单弹窗（桌面 chip 可点 + 名单行） */
.chip.act { cursor: pointer; }
.chip.act:hover { background: #EDF1F8; }
.login-list { max-height: 56vh; overflow-y: auto; }
.login-row { display: flex; align-items: center; gap: 10px; padding: 9px 2px; border-bottom: 1px solid #F0F2F6; }
.login-row:last-of-type { border-bottom: none; }
.login-row .mid { flex: 1; min-width: 0; }
.login-row .mid b { font-size: 13.5px; color: var(--shine-navy, #1B2A4A); }
.login-row .mid small { display: block; font-size: 11px; color: #8A93A6; margin-top: 1px; }
.login-row .rgt { text-align: right; }
.login-row .rgt b { font-size: 12.5px; color: var(--shine-navy, #1B2A4A); font-weight: 600; }
.login-row .rgt small { display: block; font-size: 11px; color: #8A93A6; }
.login-list .empty { text-align: center; color: #8A93A6; font-size: 13px; padding: 24px 0; }
@keyframes online-pulse {
  0% { box-shadow: 0 0 0 0 rgba(103, 194, 58, 0.45); }
  70% { box-shadow: 0 0 0 7px rgba(103, 194, 58, 0); }
  100% { box-shadow: 0 0 0 0 rgba(103, 194, 58, 0); }
}
:deep(.el-tabs__header) { margin: 0 0 16px; }
:deep(.el-tabs__nav-wrap::after) { height: 1px; background: var(--el-border-color-lighter); }
:deep(.el-tabs__item) { font-size: 15px; }

/* ────────── 桌面侧栏形态（≥1024；配色=§11 C 令牌，白底现代网格+红 active 金侧边） ────────── */
.admin-desktop { display: flex; max-width: none; padding: 0; height: 100%; background: #FBFBFD; }

.aside { flex: none; width: 222px; background: var(--shine-card); border-right: 1px solid #EDEFF4;
  display: flex; flex-direction: column; }
.logo { display: flex; align-items: center; gap: 10px; padding: 20px 20px 18px; }
.logo img { width: 36px; height: 36px; object-fit: contain; }
.lb { font-size: 17px; font-weight: 800; color: var(--shine-navy); letter-spacing: 1px; }
.lb em { font-style: normal; color: var(--shine-red); }
.lb small { display: block; font-size: 9px; color: #8A93A6; letter-spacing: 2.5px; font-weight: 500; margin-top: 1px; }
.nav { padding: 6px 12px; flex: 1; overflow-y: auto; }
.grp { padding: 12px 12px 6px; font-size: 10.5px; color: #A0A7B8; letter-spacing: 2px; font-weight: 700; }
.nv { display: flex; align-items: center; gap: 10px; width: 100%; padding: 10.5px 12px; border: none;
  border-radius: 9px; background: none; color: var(--shine-navy-soft); font-size: 13px; font-weight: 600;
  margin-bottom: 3px; position: relative; cursor: pointer; text-align: left; }
.nv .el-icon { font-size: 17px; }
.nv:hover { background: #F6F7FA; }
.nv.on { background: var(--shine-red-soft); color: var(--shine-red); }
.nv.on::before { content: ""; position: absolute; left: -12px; top: 8px; bottom: 8px; width: 3px;
  background: linear-gradient(180deg, var(--shine-red), var(--shine-gold)); border-radius: 0 3px 3px 0; }
.badge { margin-left: auto; font-style: normal; font-size: 10px; font-weight: 700; line-height: 1;
  color: #fff; background: var(--shine-red); border-radius: 8px; padding: 3px 6px; }
.ft { padding: 14px 20px; border-top: 1px solid #EDEFF4; font-size: 10.5px; color: #8A93A6; letter-spacing: 1px; }

.main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.top { flex: none; height: 62px; background: var(--shine-card); border-bottom: 1px solid #EDEFF4;
  display: flex; align-items: center; padding: 0 26px; gap: 14px; }
.crumb { font-size: 13px; color: #8A93A6; }
.crumb b { color: var(--shine-navy); font-weight: 700; }
.chips { margin-left: auto; display: flex; gap: 9px; align-items: center; }
.chip { background: #F4F6FA; border-radius: 8px; padding: 6px 12px; font-size: 12px; color: var(--shine-navy-soft); }
.chip b { font-weight: 800; color: var(--shine-navy); }
.user { display: flex; align-items: center; gap: 9px; margin-left: 6px; }
.uav { width: 32px; height: 32px; border-radius: 50%; background: var(--shine-navy); color: #E8CE8C;
  display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 700; }
.user span { font-size: 12.5px; color: var(--shine-navy); font-weight: 700; }
.user small { display: block; font-size: 10px; color: #8A93A6; font-weight: 400; }

.body { flex: 1; overflow-y: auto; padding: 22px 26px 32px; }
.pt { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
.pt .mei { width: 22px; height: 9px;
  background: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='22' height='9'%3E%3Cpath d='M1 1h7v7M21 1h-7v7' fill='none' stroke='%23C9A227' stroke-width='1.4'/%3E%3C/svg%3E") no-repeat; }
.pt h1 { margin: 0; font-family: 'Noto Serif SC', 'Source Han Serif SC', 'STZhongsong', 'SimSun', serif;
  font-size: 21px; color: var(--shine-navy); font-weight: 700; letter-spacing: 2px; }

/* ────────── 窄屏分组落地页（批37 两级形态第一级） ────────── */
.grp-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
@media (min-width: 600px) { .grp-grid { grid-template-columns: repeat(3, 1fr); } }
.grp-card { position: relative; display: flex; flex-direction: column; align-items: flex-start; gap: 4px;
  padding: 14px; border: 1px solid #EDEFF4; border-radius: 14px; background: var(--shine-card, #fff);
  cursor: pointer; text-align: left; }
.grp-card:active { opacity: .75; }
.grp-card .el-icon { font-size: 22px; color: var(--app-blue, #3E7BFA); }
.grp-card b { font-size: 14px; color: var(--shine-navy, #1B2A4A); }
.grp-card span { font-size: 11px; color: #8A93A6; line-height: 1.4; }
.n-badge { position: absolute; top: 10px; right: 10px; font-style: normal; min-width: 16px; height: 16px;
  padding: 0 4px; border-radius: 8px; background: var(--shine-red, #EF4444); color: #fff;
  font-size: 10px; line-height: 16px; text-align: center; }
/* 第二级返回行 */
.grp-back { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.grp-back button { display: flex; align-items: center; gap: 4px; border: none; background: none;
  color: var(--app-blue, #3E7BFA); font-size: 13px; font-weight: 600; cursor: pointer; padding: 4px 0; }
.grp-back span { font-size: 12px; color: #8A93A6; }
</style>
