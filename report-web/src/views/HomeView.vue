<template>
  <div class="app-page home">
    <van-pull-refresh v-model="refreshing" @refresh="reload" success-text="已刷新">
    <!-- 头区（图1）：顶部校园照片带 + 问候 + 铃铛/头像，渐变下垫虚化校园底图 -->
    <div class="app-hero hero">
      <img class="hero-photo" :src="asset.url('campus-bg', '/campus-bg.jpg')" alt="石实实验学校">
      <div class="hero-top">
        <div class="hello">
          <p class="hi">{{ greeting }}，{{ auth.realName }}</p>
          <h1>石实SHINE</h1>
          <p class="sub">学生的每一步成长都值得被看见</p>
        </div>
        <div class="hero-actions">
          <RouterLink to="/notify" class="hero-btn" aria-label="消息通知">
            <van-icon name="bell" />
            <i v-if="notifyStore.unread" class="dot"></i>
          </RouterLink>
          <RouterLink to="/mine" class="avatar" aria-label="我的">{{ avatarChar }}</RouterLink>
        </div>
      </div>
    </div>

    <!-- 统计卡（图1：上浮叠在头区渐变上） -->
    <div class="app-card overlap tl tex-28 stats">
      <div class="stat"><b>{{ summary.studentCount ?? '—' }}</b><span>在册学生</span></div>
      <div class="stat"><b>{{ summary.reportCount ?? '—' }}</b><span>学期报告</span></div>
      <div class="stat"><b :class="{ hot: running > 0 }">{{ running }}</b><span>进行中任务</span></div>
    </div>

    <!-- 快捷功能宫格（批37 IA 重组：5 分区替代扁平宫格，功能全量可直达） -->
    <template v-for="(sec, si) in sections" :key="sec.label">
      <div class="app-sec">{{ sec.label }}</div>
      <!-- 批52③ 37 槽轮换池：本页基准 27、静态位 2（统计卡/动态卡），宫格分区轮换续在 31 起 -->
      <div class="app-card grid" :class="texAt(30 + si)">
        <button v-for="g in sec.items" :key="g.to" class="g-item" type="button" @click="$router.push(g.to)">
          <span class="g-icon" :style="{ background: g.bg }"><van-icon :name="g.icon" /></span>
          <span>{{ g.name }}</span>
        </button>
      </div>
    </template>

    <!-- 最近动态（图2 卡片样式缩略，查看全部 → 成长记录流） -->
    <div class="app-sec">最近动态<RouterLink class="more" to="/feed">查看全部 ›</RouterLink></div>
    <div class="app-card tex-30 feed">
      <van-skeleton v-if="!loaded" :row="6" class="feed-skeleton" />
      <div v-else-if="!feed.length" class="feed-empty">还没有动态，去记一条学生表现吧</div>
      <div v-for="(f, i) in feed" :key="i" class="feed-item"
        @click="f.studentId && $router.push(`/student/${f.studentId}`)">
        <div class="f-line1">
          <span class="f-title">{{ f.studentName || f.studentNames || '校园活动' }}<template v-if="f.type === '评价' || f.type === '微光'"> · {{ f.title }}</template></span>
          <span class="f-time">{{ relTime(f.time) }}</span>
        </div>
        <p class="f-content">{{ f.content || f.title }}</p>
        <div class="f-line3">
          <span class="app-chip" :class="chipClass(f.type)">{{ chipLabel(f) }}</span>
          <span v-if="f.teacherName" class="f-from">来自：{{ f.teacherName }}</span>
        </div>
      </div>
    </div>

    <CampusSkyline />
    <p class="app-foot">石实实验学校 · 石实SHINE</p>
    </van-pull-refresh>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import CampusSkyline from '../components/CampusSkyline.vue'
import { useAiTasksStore } from '../stores/aiTasks'
import { useNotifyStore } from '../stores/notify'
import { useAssetStore } from '../stores/asset'
import { api } from '../api/http'
import { relTime } from '../utils/fmt'
import { texAt } from '../utils/tex'

const auth = useAuthStore()
const asset = useAssetStore()
const aiTasks = useAiTasksStore()
const notifyStore = useNotifyStore()
const running = computed(() => aiTasks.runningCount)

interface FeedItem {
  type: string; title?: string; content?: string; teacherName?: string; time?: string
  studentId?: number; studentName?: string; className?: string; typeLabel?: string
  momentId?: number; photoUrl?: string; studentNames?: string
}

const summary = ref<{ studentCount: number; reportCount: number; termName: string }>({} as never)
const feed = ref<FeedItem[]>([])
const refreshing = ref(false)
const loaded = ref(false)

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '上午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const avatarChar = computed(() => auth.realName?.charAt(0) || '师')

/* 宫格分区（批40e 按校方 18 项清单重排）：
   学生模块 1~7（老师可记录）→「成长记录」；8~13（自动生成/查询）→「成长档案」；
   教师模块 14~18 →「教师发展」；保留区（校方未列入但已拍板）：成长激励/校园事务/我的成长/学校管理。
   「班主任寄语/成长总结/综合素质」三入口共用 /final-eval（?tab= 预选段）；
   「教师档案/教师风采/教师足迹」三入口共用 /my-growth（?tab= 预选）；
   批32 口径：请假入口=班主任/生活老师/级长/学成中心主任/领导/管理员；
   批63 成绩口径：班主任/科任开只读入口（无排名列无汇总，服务端置 null 兜底）；级长可录本年级、领导/教学处只读 */
const sections = computed(() => {
  const leave = ['HEAD_TEACHER', 'GRADE_LEADER', 'DIRECTOR', 'DORM', 'LEADER', 'ADMIN', 'SECRETARY'].includes(auth.role)
  const score = auth.scoreAccess
  return [
    { label: '成长记录', items: [
      { name: '微光时刻', icon: 'photograph', to: '/moment/new', bg: '#F97316' },
      ...(score ? [{ name: '成绩管理', icon: 'bar-chart-o', to: '/scores', bg: '#3E7BFA' }] : []),
      { name: '素养评价', icon: 'edit', to: '/evaluate', bg: '#10B981' },
      { name: '导师谈心', icon: 'chat-o', to: '/talk', bg: '#7C4DD8' },
      { name: '扬长课程', icon: 'flag-o', to: '/activity', bg: '#F43F5E' },
      { name: '荣誉证书', icon: 'medal-o', to: '/honor', bg: '#EAB308' },
      ...(leave ? [{ name: '学生请假', icon: 'clock-o', to: '/leave', bg: '#DC2626' }] : []),
    ] },
    { label: '成长档案', items: [
      { name: '班主任寄语', icon: 'comment-o', to: '/final-eval?tab=comment', bg: '#F59E0B' },
      { name: '成长总结', icon: 'notes-o', to: '/final-eval?tab=summary', bg: '#EA580C' },
      { name: '综合素质', icon: 'star-o', to: '/final-eval?tab=comp', bg: '#0EA5E9' },
      { name: '成长时间轴', icon: 'clock-o', to: '/timeline', bg: '#6366F1' },
      { name: '成长报告', icon: 'orders-o', to: '/reports', bg: '#14B8A6' },
      { name: '宿舍查询', icon: 'wap-home-o', to: '/dorm', bg: '#65A30D' },
    ] },
    { label: '教师发展', items: [
      { name: '行政办公', icon: 'todo-list-o', to: '/oa', bg: '#7C3AED' },
      { name: '报修', icon: 'brush-o', to: '/repair', bg: '#0891B2' },
      { name: '教师足迹', icon: 'location-o', to: '/my-growth?tab=footprint', bg: '#0891B2' },
      { name: '教师风采', icon: 'flower-o', to: '/my-growth?tab=honor', bg: '#DB2777' },
      { name: '教师档案', icon: 'manager-o', to: '/my-growth?tab=profile', bg: '#0F766E' },
    ] },
    { label: '成长激励', items: [
      { name: '成长银行', icon: 'gold-coin-o', to: '/bank', bg: '#D97706' },
      { name: '文明班', icon: 'bookmark-o', to: '/civility', bg: '#B45309' },
      { name: '德育规范', icon: 'notes-o', to: '/conduct-rules', bg: '#059669' },
    ] },
    { label: '校园事务', items: [
      { name: '通知公告', icon: 'volume-o', to: '/notices', bg: '#CA8A04' },
      { name: '场地预约', icon: 'location-o', to: '/venue', bg: '#1E40AF' },
    ] },
    { label: '我的成长', items: [
      { name: '我的成长', icon: 'award-o', to: '/my-growth', bg: '#0F766E' },
      ...(['LEADER', 'SECRETARY', 'DIRECTOR'].includes(auth.role) ? [{ name: '领导驾驶舱', icon: 'chart-trending-o', to: '/l/home', bg: '#1F2A44' }] : []),
    ] },
    /* 批37 追加：管理员直达分区——校方自助上传的内容发布/素材库一步可达
       （?tab= 直达且自动展开所属分组）；系统管理从「校园事务」挪入 */
    ...(auth.role === 'ADMIN' ? [{ label: '学校管理', items: [
      { name: '内容发布', icon: 'newspaper-o', to: '/admin?tab=content', bg: '#A8232B' },
      { name: '素材库', icon: 'photo-o', to: '/admin?tab=asset', bg: '#64748B' },
      { name: '系统管理', icon: 'setting-o', to: '/admin', bg: '#475569' },
    ] }] : []),
  ]
})

function chipClass(type: string) {
  return { 评价: 'c-eval', 荣誉: 'c-honor', 寄语: 'c-comment', 活动: 'c-act', 微光: 'c-moment' }[type] ?? ''
}
function chipLabel(f: FeedItem) {
  if (f.type === '荣誉') return '荣誉时刻'
  if (f.type === '活动') return f.typeLabel || '校园活动'
  if (f.type === '寄语') return '班主任寄语'
  if (f.type === '微光') return '微光时刻'
  return '日常表现'
}

async function loadAll() {
  await Promise.allSettled([
    api<{ studentCount: number; reportCount: number; termName: string }>('/api/feed/home-summary')
      .then((d) => (summary.value = d)).catch(() => {}),
    api<FeedItem[]>('/api/feed?limit=5').then((d) => (feed.value = d)).catch(() => {}),
  ])
  loaded.value = true
}

/** 下拉刷新：重拉首页数据（统计卡 + 最近动态） */
async function reload() {
  try { await loadAll() } finally { refreshing.value = false }
}

onMounted(loadAll)
</script>

<style scoped>
.hero-top { display: flex; align-items: flex-start; justify-content: space-between; }
.hello .hi { margin: 6px 0 2px; font-size: 13px; color: rgba(255,255,255,.75); }
.hello h1 { margin: 0; font-size: 24px; font-weight: 800; letter-spacing: 2px; }
.hello .sub { margin: 4px 0 0; font-size: 12px; color: rgba(255,255,255,.65); letter-spacing: 1px; }
.hero-actions { display: flex; align-items: center; gap: 12px; }
.hero-btn { position: relative; display: flex; align-items: center; justify-content: center;
  width: 38px; height: 38px; border-radius: 50%; background: rgba(255,255,255,.16); color: #fff;
  box-shadow: 0 2px 6px rgba(10,22,60,.25); }   /* 压在光斑上时保住边缘清晰 */
.hero-btn .van-icon { font-size: 19px; }
.hero-btn .dot { position: absolute; top: 6px; right: 7px; width: 8px; height: 8px; border-radius: 50%;
  background: #F87171; border: 2px solid rgba(255,255,255,.55); }
.avatar { display: flex; align-items: center; justify-content: center; width: 38px; height: 38px;
  border-radius: 50%; background: rgba(255,255,255,.92); color: var(--app-blue-deep);
  font-weight: 700; text-decoration: none; box-shadow: 0 2px 6px rgba(10,22,60,.25); }

/* 统计卡 */
.stats { display: flex; padding: 14px 0; }
.stat { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; }
.stat + .stat { border-left: 1px solid var(--app-card-border); }
.stat b { font-size: 22px; color: var(--app-text-1); }
.stat b.hot { color: var(--app-blue); }
.stat span { font-size: 11px; color: var(--app-text-3); }

/* 宫格：手机4列、平板5列（≥600px 限宽卡内自动换行） */
.grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 4px 0; padding: 12px 6px; }
@media (min-width: 600px) { .grid { grid-template-columns: repeat(5, 1fr); } }
.g-item { display: flex; flex-direction: column; align-items: center; gap: 7px;
  padding: 8px 2px; background: none; border: none; color: var(--app-text-1);
  font-size: 12px; cursor: pointer; -webkit-tap-highlight-color: transparent; }
.g-item:active { opacity: .7; }
.g-icon { display: flex; align-items: center; justify-content: center; width: 44px; height: 44px;
  border-radius: 14px; box-shadow: 0 3px 8px rgba(23,43,99,.14); }
.g-icon .van-icon { font-size: 22px; color: #fff; }

/* 最近动态 */
.feed { padding: 4px 14px; }
.feed-skeleton { padding: 14px 0; }
.feed-empty { padding: 26px 0; text-align: center; color: var(--app-text-3); font-size: 13px; }
.feed-item { padding: 12px 0; }
.feed-item + .feed-item { border-top: 1px solid var(--app-card-border); }
.f-line1 { display: flex; align-items: baseline; gap: 8px; }
.f-title { flex: 1; font-size: 14px; font-weight: 600; color: var(--app-text-1);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.f-time { font-size: 11px; color: var(--app-text-3); }
.f-content { margin: 5px 0 6px; font-size: 13px; line-height: 1.5; color: var(--app-text-2);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.f-line3 { display: flex; align-items: center; gap: 10px; }
.f-from { font-size: 11px; color: var(--app-text-3); }
.c-eval { background: rgba(168,35,43,.08); color: #A8232B; }
.c-honor { background: #FBF3DF; color: #B07A1C; }
.c-comment { background: #E8F6EF; color: #0D9467; }
.c-act { background: #F3EAFE; color: #7C4DD8; }
.c-moment { background: #FDEEE2; color: #EA580C; }
</style>
