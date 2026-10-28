<template>
  <div class="app-page p-home">
    <van-pull-refresh v-model="refreshing" @refresh="reload" success-text="已刷新">
    <!-- 头区（第一版结构：照片带+问候+头像，C 藏蓝渐变） -->
    <div class="app-hero hero">
      <img class="hero-photo" :src="asset.url('campus-bg', '/campus-bg.jpg')" alt="石实实验学校">
      <div class="hero-top">
        <div class="hello">
          <p class="hi">{{ greeting }}，{{ auth.realName }}</p>
          <h1>石实SHINE</h1>
          <p class="sub">家校共育，见证孩子每一步成长</p>
        </div>
        <div class="hero-actions">
          <RouterLink to="/p/mine" class="avatar" aria-label="我的">{{ avatarChar }}</RouterLink>
        </div>
      </div>
    </div>

    <!-- 我的孩子（上浮卡，一号可绑多孩） -->
    <div class="app-card overlap tl tex-08 kids">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!children.length" class="empty">暂未绑定孩子，请联系学校管理员绑定</div>
      <RouterLink v-for="c in children" :key="c.studentId" class="kid" :to="`/p/child/${c.studentId}`">
        <div class="kid-avatar">{{ c.name?.charAt(0) ?? '学' }}</div>
        <div class="kid-info">
          <p class="kid-name">{{ c.name }}<span class="app-chip kid-rel">{{ c.relation }}</span></p>
          <p class="kid-meta">{{ c.className ?? '未分班' }}<template v-if="c.studentNo"> · 学号 {{ c.studentNo }}</template></p>
        </div>
        <van-icon name="arrow" class="kid-arrow" />
      </RouterLink>
    </div>

    <!-- 校园服务宫格（同教师端快捷功能形态）：批2 通知/内容上宫格、批5 家长版成长报告（去成绩板块）；
         批37 追加：通知公告/扬长课程升底部导航 Tab（此处移除） -->
    <div class="app-sec">校园服务</div>
    <div class="app-card tex-09 grid-card">
      <div class="grid">
        <button v-for="g in grids" :key="g.name" class="g-item" type="button"
          :class="{ off: !g.to }" @click="g.to ? $router.push(g.to) : showToast('该功能即将开放')">
          <span class="g-icon" :style="{ background: g.bg }"><van-icon :name="g.icon" /></span>
          <span>{{ g.name }}</span>
          <i v-if="!g.to" class="g-tip">即将开放</i>
        </button>
      </div>
    </div>

    <!-- 最新成长动态（feed 结构同教师端最近动态）：第一个孩子最近 5 条评价 -->
    <div class="app-sec">最新成长动态<RouterLink v-if="children.length" class="more" :to="`/p/child/${children[0].studentId}`">查看全部 ›</RouterLink></div>
    <div class="app-card tex-10 feed">
      <van-skeleton v-if="!loaded" :row="6" class="feed-skeleton" />
      <div v-else-if="!recent.length" class="feed-empty">还没有孩子的成长动态</div>
      <article v-for="(e, i) in recent" :key="i" class="eval">
        <div class="f-line1">
          <span class="f-title">{{ e.title }}</span>
          <b class="f-score">{{ e.score }}</b>
        </div>
        <p class="f-meta">{{ e.teacherName ?? '老师' }} · {{ fmtTime(e.evalTime) }}</p>
        <p v-if="e.remark" class="f-remark">{{ e.remark }}</p>
      </article>
    </div>

    <CampusSkyline />
    <p class="app-foot">石实实验学校 · 石实SHINE</p>
    </van-pull-refresh>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../../api/http'
import { useAuthStore } from '../../stores/auth'
import { useAssetStore } from '../../stores/asset'
import CampusSkyline from '../../components/CampusSkyline.vue'

const auth = useAuthStore()
const asset = useAssetStore()
const children = ref<any[]>([])
const recent = ref<any[]>([])
const loading = ref(true)
const loaded = ref(false)
const refreshing = ref(false)

const greeting = computed(() => {
  const h = new Date().getHours()
  return h < 6 ? '夜深了' : h < 12 ? '早上好' : h < 18 ? '下午好' : '晚上好'
})
const avatarChar = computed(() => auth.realName?.charAt(0) || '家')

/* 服务宫格（同教师端 HomeView 的 g-icon 彩色方底形态；to 为空=未开放置灰）。
   微光时刻/成长报告批2/批5 上宫格；批37：修改密码归「我的」页、成长档案上宫格（默认
   第一个孩子，多孩从上方孩子卡进）；批37 追加：通知公告/扬长课程升底部导航 Tab，宫格移除 */
const grids = computed(() => [
  { name: '微光时刻', icon: 'photograph', to: '/p/moments', bg: '#F97316' },
  { name: '成长档案', icon: 'orders-o', to: children.value.length ? `/p/archive/${children.value[0].studentId}` : '', bg: '#1F2A44' },
  { name: '成绩查询', icon: 'bar-chart-o', to: '/p/scores', bg: '#3E7BFA' },
  { name: '荣誉证书', icon: 'medal-o', to: '/p/honor', bg: '#EAB308' },
  { name: '成长报告', icon: 'description', to: '/p/report', bg: '#14B8A6' },
  { name: '请假记录', icon: 'clock-o', to: '/p/leave', bg: '#DC2626' },
  // 批72d：家长只读扬长课程（视频为外链跳转观看）
  { name: '扬长课程', icon: 'flag-o', to: '/p/activity', bg: '#7C3AED' },
])

function fmtTime(t?: string) {
  if (!t) return ''
  return t.slice(0, 16).replace('T', ' ')
}

async function loadAll() {
  try {
    children.value = await api<any[]>('/api/parent/children')
    recent.value = children.value.length
      ? await api<any[]>(`/api/parent/children/${children.value[0].studentId}/evaluations?limit=5`)
      : []
  } finally {
    loading.value = false
    loaded.value = true
  }
}

/** 下拉刷新：重拉孩子列表+最新动态 */
async function reload() {
  try { await loadAll() } finally { refreshing.value = false }
}

onMounted(loadAll)
</script>

<style scoped>
/* C 风格页面（方案C 新中式）：结构复用第一版全局类（.app-page/.app-hero/.app-card/.app-sec），
   仅覆盖头区渐变/点缀色为 C 令牌；令牌见 style.css §11 */
.hero-top { display: flex; align-items: flex-start; justify-content: space-between; }
.hello .hi { margin: 6px 0 2px; font-size: 13px; color: rgba(255,255,255,.75); }
.hello h1 { margin: 0; font-size: 24px; font-weight: 800; letter-spacing: 2px; }
.hello .sub { margin: 4px 0 0; font-size: 12px; color: rgba(255,255,255,.65); letter-spacing: 1px; }
.hero-actions { display: flex; align-items: center; gap: 12px; }
.avatar { display: flex; align-items: center; justify-content: center; width: 38px; height: 38px;
  border-radius: 50%; background: rgba(255,255,255,.92); color: var(--shine-navy);
  font-weight: 700; text-decoration: none; box-shadow: 0 2px 6px rgba(10,22,60,.25); }

/* 孩子卡 */
.empty { padding: 18px 0; text-align: center; color: var(--app-text-2); font-size: 13px; }
.kid { display: flex; align-items: center; gap: 12px; padding: 12px 2px; text-decoration: none; }
.kid + .kid { border-top: 1px solid var(--app-card-border); }
.kid-avatar { flex: none; width: 46px; height: 46px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: var(--shine-navy); color: var(--shine-gold); font-size: 19px; font-weight: 700; }
.kid-info { flex: 1; min-width: 0; }
.kid-name { margin: 0; font-size: 15px; font-weight: 600; color: var(--app-text-1); }
.kid-rel { margin-left: 8px; background: var(--shine-red-soft); color: var(--shine-red); }
.kid-meta { margin: 4px 0 0; font-size: 12px; color: var(--app-text-2); }
.kid-arrow { color: var(--app-text-3); }

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

/* 成长动态（feed 结构同教师端最近动态） */
.feed { padding: 4px 14px; }
.feed-skeleton { padding: 14px 0; }
.feed-empty { padding: 26px 0; text-align: center; color: var(--app-text-3); font-size: 13px; }
.eval { padding: 12px 0; }
.eval + .eval { border-top: 1px solid var(--app-card-border); }
.f-line1 { display: flex; align-items: baseline; gap: 8px; }
.f-title { flex: 1; font-size: 14px; font-weight: 600; color: var(--app-text-1);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.f-score { font-size: 13px; color: var(--shine-red); }
.f-meta { margin: 5px 0 0; font-size: 11px; color: var(--app-text-3); }
.f-remark { margin: 6px 0 0; font-size: 13px; line-height: 1.5; color: var(--app-text-2);
  background: var(--shine-bg); border-radius: 8px; padding: 8px 10px; }

/* C 覆盖：头区藏蓝渐变+金线收边（第一版 hero 结构不变，仅换配色令牌） */
.p-home .app-hero { background: var(--shine-gradient); border-bottom: 2px solid var(--shine-gold); }
.p-home .app-hero::after {   /* 光斑换金（C 点缀） */
  background: radial-gradient(closest-side, rgba(201,162,39,.22), rgba(201,162,39,0));
}
.p-home .app-sec::before { background: var(--shine-red); }
</style>
