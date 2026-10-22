<template>
  <div class="app-page t-teachers">
    <!-- 时间档（近7天/近30天/本月/近90天）：本月=当月1日起 -->
    <div class="ranges">
      <button v-for="r in ranges" :key="r.days" class="app-chip range" type="button"
        :class="{ on: r.days === days }" @click="switchDays(r.days)">{{ r.label }}</button>
    </div>

    <div class="app-sec">教职工 · {{ rows.length }} 人（按总活跃排序）</div>

    <van-skeleton v-if="loading" :row="9" style="padding: 14px" />
    <div v-else-if="!rows.length" class="app-card empty">暂无在职教职工</div>

    <!-- 每师一卡（tex 纹理轮换，第一版卡片语言）：六类使用行为 -->
    <div v-for="(t, i) in rows" :key="t.teacherId" class="app-card usage" :class="tex(i)">
      <div class="t-head">
        <span class="t-avatar">{{ t.name?.charAt(0) ?? '?' }}</span>
        <div class="t-info">
          <p class="t-name">{{ t.name }}<span class="app-chip role">{{ roleLabel(t.role) }}</span></p>
          <p class="t-last">{{ t.lastLogin ? `最近登录 ${fmtTime(t.lastLogin)}` : '期间未登录' }}</p>
        </div>
      </div>
      <div class="u-grid">
        <div class="u"><b>{{ t.reports }}</b><span>报告生成</span></div>
        <div class="u"><b>{{ t.evals }}</b><span>素养评价</span></div>
        <div class="u"><b>{{ t.scores }}</b><span>成绩录入</span></div>
        <div class="u"><b>{{ t.moments }}</b><span>微光发布</span></div>
        <div class="u"><b>{{ shortTokens(t.tokens) }}</b><span>AI tokens</span></div>
        <div class="u"><b>{{ t.logins }}</b><span>登录次数</span></div>
      </div>
    </div>

    <p class="app-foot">石实实验学校 · 石实SHINE</p>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../../api/http'
import { texAt } from '../../utils/tex'

interface UsageRow {
  teacherId: number; name: string; role: string
  reports: number; tokens: number; evals: number
  scores: number; moments: number; logins: number
  lastLogin: string | null
}

const rows = ref<UsageRow[]>([])
const loading = ref(true)
const days = ref(30)

/* 本月=当月1日至今的天数差（+1 含当天） */
function monthDays(): number {
  const now = new Date()
  const first = new Date(now.getFullYear(), now.getMonth(), 1)
  return Math.floor((now.getTime() - first.getTime()) / 86400000) + 1
}
const ranges = [
  { label: '近7天', days: 7 },
  { label: '近30天', days: 30 },
  { label: '本月', days: monthDays() },
  { label: '近90天', days: 90 },
]

// 批52③ 37 槽轮换池：本页基准 1（无静态位，轮换从基准起）
const tex = (i: number) => texAt(1 + i)
function roleLabel(r: string) {
  return ({ ADMIN: '管理员', SECRETARY: '书记', LEADER: '领导', DIRECTOR: '主任', GRADE_LEADER: '级长',
    HEAD_TEACHER: '班主任', TEACHER: '任课教师' } as Record<string, string>)[r] ?? r
}
function fmtTime(t: string) {
  return t.slice(0, 16).replace('T', ' ')
}
function shortTokens(v: number) {
  return v >= 10000 ? (v / 10000).toFixed(1) + '万' : String(v)
}

async function load() {
  loading.value = true
  try {
    const d = await api<{ rows: UsageRow[] }>(`/api/leader/teacher-usage?days=${days.value}`)
    rows.value = d.rows ?? []
  } finally {
    loading.value = false
  }
}
function switchDays(d: number) {
  if (d === days.value) return
  days.value = d
  load()
}

onMounted(load)
</script>

<style scoped>
/* C 风格页面：结构复用第一版全局类（.app-page/.app-card/.app-sec/.app-chip），点缀色 C 令牌 */
.ranges { display: flex; gap: 8px; padding: 14px 2px 0; }
.range { border: none; cursor: pointer; padding: 5px 14px; }
.range.on { background: var(--shine-red-soft); color: var(--shine-red); font-weight: 600; }

.empty { text-align: center; color: var(--app-text-2); font-size: 13px; padding: 26px 0; }

.usage { margin-bottom: 12px; }
.t-head { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.t-avatar { flex: none; width: 42px; height: 42px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: var(--shine-navy); color: var(--shine-gold); font-size: 17px; font-weight: 700; }
.t-info { flex: 1; min-width: 0; }
.t-name { margin: 0; font-size: 15px; font-weight: 600; color: var(--app-text-1); }
.t-name .role { margin-left: 8px; }
.t-last { margin: 3px 0 0; font-size: 11px; color: var(--app-text-3); }

.u-grid { display: grid; grid-template-columns: repeat(3, 1fr); }
.u { display: flex; flex-direction: column; align-items: center; gap: 2px;
  padding: 10px 0; border-top: 1px solid var(--app-card-border); }
.u:nth-child(-n+3) { border-top: none; }
.u:nth-child(3n+2) { border-left: 1px solid var(--app-card-border); border-right: 1px solid var(--app-card-border); }
.u b { font-size: 18px; color: var(--app-text-1); font-variant-numeric: tabular-nums; }
.u span { font-size: 11px; color: var(--app-text-3); }
</style>
