<template>
  <div class="app-page l-home">
    <van-pull-refresh v-model="refreshing" @refresh="reload" success-text="已刷新">
    <!-- 头区（第一版结构：照片带+问候，C 藏蓝渐变；领导端无「我的」页，右上放退出） -->
    <div class="app-hero hero">
      <img class="hero-photo" :src="asset.url('campus-bg', '/campus-bg.jpg')" alt="石实实验学校">
      <div class="hero-top">
        <div class="hello">
          <p class="hi">{{ greeting }}，{{ auth.realName }}</p>
          <h1>数智驾驶舱</h1>
          <p class="sub">全校数据 · 只读总览</p>
        </div>
        <div class="hero-actions">
          <!-- 批3.5：驾驶舱降为教师首页下级页，加返回（无历史时兜底回首页） -->
          <button class="hero-btn" type="button" aria-label="返回" @click="hasHistory ? $router.back() : $router.replace('/')">
            <van-icon name="arrow-left" />
          </button>
          <button class="hero-btn" type="button" aria-label="退出登录" @click="onLogout">
            <van-icon name="revoke" />
          </button>
        </div>
      </div>
    </div>

    <!-- 全校规模（上浮统计卡，同教师端首页统计卡结构） -->
    <div class="app-card overlap tl tex-30 stats">
      <div class="stat"><b>{{ ov.studentCount ?? '—' }}</b><span>在读学生</span></div>
      <div class="stat"><b>{{ ov.teacherCount ?? '—' }}</b><span>教职工</span></div>
      <div class="stat"><b>{{ ov.classCount ?? '—' }}</b><span>班级</span></div>
    </div>

    <!-- 快捷功能宫格（同教师端形态）：成绩查询 LEADER 只读复用；教师使用情况批2；成绩汇总批3 -->
    <div class="app-sec">快捷功能</div>
    <div class="app-card tex-31 grid-card">
      <div class="grid">
        <button v-for="g in grids" :key="g.name" class="g-item" type="button"
          :class="{ off: !g.to }" @click="g.to ? $router.push(g.to) : showToast('该功能即将开放')">
          <span class="g-icon" :style="{ background: g.bg }"><van-icon :name="g.icon" /></span>
          <span>{{ g.name }}</span>
          <i v-if="g.name === '待我审批' && ov.pendingApprovals > 0" class="g-badge">
            {{ ov.pendingApprovals > 99 ? '99+' : ov.pendingApprovals }}</i>
          <i v-if="!g.to" class="g-tip">即将开放</i>
        </button>
      </div>
    </div>

    <!-- 今日运营（第二组统计，重点格金红） -->
    <div class="app-sec">今日运营</div>
    <div class="app-card tex-32 stats">
      <div class="stat hot"><b>{{ ov.todayEvalCount ?? 0 }}</b><span>今日评价</span></div>
      <div class="stat"><b>{{ ov.onlineCount ?? 0 }}</b><span>当前在线</span></div>
      <div class="stat"><b>{{ ov.dailyActive ?? 0 }}</b><span>24h 活跃</span></div>
    </div>

    <!-- AI 使用情况摘要（按师明细在「教师使用情况」页） -->
    <div class="app-sec">AI 使用情况</div>
    <div class="app-card tex-33 stats">
      <div class="stat"><b>{{ aiTasks }}</b><span>近 30 天生成（次）</span></div>
      <div class="stat"><b>{{ aiTokens }}</b><span>近 30 天 tokens</span></div>
    </div>

    <CampusSkyline />
    <p class="app-foot">石实实验学校 · 石实SHINE</p>
    </van-pull-refresh>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../../api/http'
import { useAuthStore } from '../../stores/auth'
import { useAssetStore } from '../../stores/asset'
import CampusSkyline from '../../components/CampusSkyline.vue'

const auth = useAuthStore()
const asset = useAssetStore()
const router = useRouter()
const ov = ref<any>({})
const usage = ref<any[]>([])
const refreshing = ref(false)

const greeting = computed(() => {
  const h = new Date().getHours()
  return h < 6 ? '夜深了' : h < 12 ? '早上好' : h < 18 ? '下午好' : '晚上好'
})

/* 近 30 天任务次数与 tokens 总量（byDay: day/tasks/promptTokens/completionTokens） */
const aiTasks = computed(() => usage.value.reduce((s, r) => s + Number(r.tasks ?? 0), 0))

/** 直达/刷新进入（无历史）时返回按钮兜底回教师首页 */
const hasHistory = computed(() => window.history.state?.back != null)
const aiTokens = computed(() => usage.value.reduce(
  (s, r) => s + Number(r.promptTokens ?? 0) + Number(r.completionTokens ?? 0), 0))

/* 快捷宫格（同教师端 HomeView 的 g-icon 彩色方底形态；to 为空=未开放置灰）。
   待我审批（批2-5）：管理员/领导账号双人审批，有数时图标角标 */
/* 批37：「成绩查询」更名「成绩明细（本班）」（与成绩汇总排名区分语义，两项相邻排）；
   修改密码移除——批3.5 领导教师化后走教师「我的」页（/mine）统一改密 */
const grids = [
  { name: '成绩明细（本班）', icon: 'bar-chart-o', to: '/scores', bg: '#A8232B' },
  { name: '成绩汇总排名', icon: 'chart-trending-o', to: '/l/scores', bg: '#8B5CF6' },
  { name: '教师使用情况', icon: 'friends-o', to: '/l/teachers', bg: '#0EA5E9' },
  { name: '待我审批', icon: 'todo-list-o', to: '/l/approvals', bg: '#A8232B' },
]

async function onLogout() {
  try {
    await showConfirmDialog({ title: '退出登录', message: '确定要退出当前账号吗？' })
  } catch { return /* 取消 */ }
  auth.logout()
  router.replace('/login')
}

async function loadAll() {
  ov.value = await api<any>('/api/leader/overview')
  const d = await api<any>('/api/leader/ai-usage?days=30')
  usage.value = d.byDay ?? []
}

/** 下拉刷新：重拉总览+用量 */
async function reload() {
  try { await loadAll() } finally { refreshing.value = false }
}

onMounted(loadAll)
</script>

<style scoped>
/* C 风格页面（方案C 新中式）：结构复用第一版全局类，仅覆盖头区渐变/点缀色为 C 令牌 */
.hero-top { display: flex; align-items: flex-start; justify-content: space-between; }
.hello .hi { margin: 6px 0 2px; font-size: 13px; color: rgba(255,255,255,.75); }
.hello h1 { margin: 0; font-size: 24px; font-weight: 800; letter-spacing: 2px; }
.hello .sub { margin: 4px 0 0; font-size: 12px; color: rgba(255,255,255,.65); letter-spacing: 1px; }
.hero-actions { display: flex; align-items: center; gap: 12px; }
.hero-btn { display: flex; align-items: center; justify-content: center;
  width: 38px; height: 38px; border-radius: 50%; background: rgba(255,255,255,.16); color: #fff;
  border: none; cursor: pointer; box-shadow: 0 2px 6px rgba(10,22,60,.25); }
.hero-btn .van-icon { font-size: 19px; }

/* 统计卡（同教师端首页 stats 结构） */
.stats { display: flex; padding: 14px 0; }
.stat { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; }
.stat + .stat { border-left: 1px solid var(--app-card-border); }
.stat b { font-size: 22px; color: var(--app-text-1); font-variant-numeric: tabular-nums; }
.stat b.hot, .stat.hot b { color: var(--shine-red); }
.stat span { font-size: 11px; color: var(--app-text-3); }

/* 宫格（同教师端 HomeView） */
.grid-card { padding: 12px 6px; }
.grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 4px 0; }
.g-item { position: relative; display: flex; flex-direction: column; align-items: center; gap: 7px;
  padding: 8px 2px; background: none; border: none; color: var(--app-text-1);
  font-size: 12px; cursor: pointer; -webkit-tap-highlight-color: transparent; }
.g-item:active { opacity: .7; }
.g-item.off { opacity: .5; }
.g-icon { display: flex; align-items: center; justify-content: center; width: 44px; height: 44px;
  border-radius: 14px; box-shadow: 0 3px 8px rgba(31, 42, 68, .14); }
.g-icon .van-icon { font-size: 22px; color: #fff; }
.g-tip { position: absolute; top: 4px; right: 6px; font-style: normal; font-size: 9px; line-height: 1;
  color: var(--shine-gold); background: #fff; border: 1px solid var(--shine-gold-soft);
  border-radius: 6px; padding: 2px 4px; }
/* 待我审批角标（批2-5）：图标右上红底白字计数 */
.g-badge { position: absolute; top: 2px; right: 2px; font-style: normal; font-size: 9px; line-height: 1;
  color: #fff; background: var(--shine-red); border-radius: 8px; padding: 3px 5px; font-weight: 700; }

.ai-note { margin: -4px 2px 0; font-size: 11px; color: var(--app-text-3); text-align: center; }

/* C 覆盖：头区藏蓝渐变+金线收边（第一版 hero 结构不变，仅换配色令牌） */
.l-home .app-hero { background: var(--shine-gradient); border-bottom: 2px solid var(--shine-gold); }
.l-home .app-hero::after {
  background: radial-gradient(closest-side, rgba(201,162,39,.22), rgba(201,162,39,0));
}
.l-home .app-sec::before { background: var(--shine-red); }
</style>
