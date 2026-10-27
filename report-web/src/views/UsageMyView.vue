<template>
  <div class="app-page">
    <!-- 月份切换（批70 教师自查：默认当月，可回看历史月） -->
    <div class="m-bar">
      <button class="app-chip m-btn" type="button" @click="shift(-1)">‹ 上月</button>
      <b class="m-label">{{ month }}</b>
      <button class="app-chip m-btn" type="button" :disabled="atCurrent" @click="shift(1)">下月 ›</button>
    </div>

    <van-skeleton v-if="loading" :row="6" style="padding: 14px" />
    <template v-else-if="me">
      <div class="app-card my-head">
        <p class="my-name">{{ me.name }}<span class="app-chip role">{{ roleLabel(me.role) }}</span></p>
        <p class="my-sub">{{ month }} 各板块操作次数</p>
      </div>
      <div class="app-card">
        <div class="u-grid">
          <div class="u"><b>{{ me.moments }}</b><span>微光时刻</span></div>
          <div class="u"><b>{{ me.evals }}</b><span>学生评价</span></div>
          <div class="u"><b>{{ me.activities }}</b><span>扬长课程</span></div>
          <div class="u"><b>{{ me.honors }}</b><span>荣誉证书</span></div>
          <div class="u"><b>{{ me.talks }}</b><span>导师谈心</span></div>
          <div class="u"><b>{{ me.logins }}</b><span>登录次数</span></div>
          <div class="u"><b>{{ me.reports }}</b><span>报告生成</span></div>
          <div class="u"><b>{{ me.scores }}</b><span>成绩录入</span></div>
          <div class="u"><b>{{ shortTokens(me.tokens) }}</b><span>AI tokens</span></div>
        </div>
      </div>
      <p class="app-foot">统计口径：自然月内落库记录（荣誉按成功上传）</p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/http'

/* 批70 教师月度操作自查：/api/leader/teacher-usage/my 仅返回本人行 */
interface UsageRow {
  teacherId: number; name: string; role: string
  reports: number; tokens: number; evals: number
  scores: number; moments: number; logins: number
  activities: number; honors: number; talks: number
  lastLogin: string | null
}

function ym(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
const month = ref(ym(new Date()))
const me = ref<UsageRow | null>(null)
const loading = ref(true)
const atCurrent = computed(() => month.value === ym(new Date()))

function roleLabel(r: string) {
  return ({ ADMIN: '管理员', SECRETARY: '书记', LEADER: '领导', DIRECTOR: '主任', GRADE_LEADER: '级长',
    HEAD_TEACHER: '班主任', TEACHER: '任课教师' } as Record<string, string>)[r] ?? r
}
function shortTokens(v: number) {
  return v >= 10000 ? (v / 10000).toFixed(1) + '万' : String(v)
}

function shift(dir: number) {
  const [y, m] = month.value.split('-').map(Number)
  const d = new Date(y, m - 1 + dir, 1)
  month.value = ym(d)
  load()
}

async function load() {
  loading.value = true
  try {
    const d = await api<{ rows: UsageRow[] }>(`/api/leader/teacher-usage/my?month=${month.value}`)
    me.value = d.rows?.[0] ?? null
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.m-bar { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 14px 0 4px; }
.m-btn { border: none; cursor: pointer; padding: 5px 14px; }
.m-btn:disabled { opacity: .4; }
.m-label { font-size: 15px; font-variant-numeric: tabular-nums; }

.my-head { margin-bottom: 12px; }
.my-name { margin: 0; font-size: 16px; font-weight: 700; color: var(--app-text-1); }
.my-name .role { margin-left: 8px; }
.my-sub { margin: 4px 0 0; font-size: 12px; color: var(--app-text-3); }

.u-grid { display: grid; grid-template-columns: repeat(3, 1fr); }
.u { display: flex; flex-direction: column; align-items: center; gap: 2px;
  padding: 12px 0; border-top: 1px solid var(--app-card-border); }
.u:nth-child(-n+3) { border-top: none; }
.u:nth-child(3n+2) { border-left: 1px solid var(--app-card-border); border-right: 1px solid var(--app-card-border); }
.u b { font-size: 18px; color: var(--app-text-1); font-variant-numeric: tabular-nums; }
.u span { font-size: 11px; color: var(--app-text-3); }
</style>
