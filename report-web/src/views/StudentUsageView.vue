<template>
  <div class="app-page">
    <!-- 月份切换 + 全部（批73c 第5条：班主任看本班每生六项记录数） -->
    <div class="m-bar">
      <button class="app-chip m-btn" type="button" :disabled="all" @click="shift(-1)">‹ 上月</button>
      <b class="m-label">{{ all ? '全部' : month }}</b>
      <button class="app-chip m-btn" type="button" :disabled="all || atCurrent" @click="shift(1)">下月 ›</button>
      <button class="app-chip m-btn" :class="{ on: all }" type="button" @click="toggleAll">全时段</button>
    </div>

    <van-skeleton v-if="loading" :row="6" style="padding: 14px" />
    <template v-else>
      <div class="app-card head-card">
        <p class="scope">{{ scopeNames }}</p>
        <p class="sub">{{ all ? '全时段' : month }} · 每生各项记录数</p>
      </div>
      <div class="app-card">
        <el-table :data="rows" size="small">
          <el-table-column prop="studentNo" label="学号" min-width="96" fixed="left" />
          <el-table-column prop="name" label="姓名" min-width="64" fixed="left" />
          <el-table-column prop="moments" label="微光" align="center" min-width="52" />
          <el-table-column prop="evals" label="评价" align="center" min-width="52" />
          <el-table-column prop="activities" label="扬长" align="center" min-width="52" />
          <el-table-column prop="honors" label="荣誉" align="center" min-width="52" />
          <el-table-column prop="talks" label="谈心" align="center" min-width="52" />
          <el-table-column prop="logins" label="登录" align="center" min-width="52" />
        </el-table>
        <p v-if="!rows.length" class="empty">本班暂无在册学生</p>
      </div>
      <p class="app-foot">登录=该生绑定家长的登录次数；其余为系统内该生名下记录数</p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/http'

/* 批73c 第5条：/api/class-usage/students 学生维度×六项（仅班主任=挂名班） */
interface Row {
  studentNo: string; name: string
  moments: number; evals: number; activities: number
  honors: number; talks: number; logins: number
}

function ym(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
const month = ref(ym(new Date()))
const all = ref(false)
const atCurrent = computed(() => month.value === ym(new Date()))
const loading = ref(true)
const rows = ref<Row[]>([])
const scopeNames = ref('')

function shift(dir: number) {
  const [y, m] = month.value.split('-').map(Number)
  month.value = ym(new Date(y, m - 1 + dir, 1))
  load()
}
function toggleAll() {
  all.value = !all.value
  load()
}

async function load() {
  loading.value = true
  try {
    const d = await api<{ rows: Row[]; scope: { name: string }[] }>(
      `/api/class-usage/students?month=${all.value ? 'all' : month.value}`,
    )
    rows.value = d.rows ?? []
    scopeNames.value = (d.scope ?? []).map((c) => c.name).join('、')
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
.m-btn.on { background: var(--app-blue, #3b82f6); color: #fff; }
.m-label { font-size: 15px; font-variant-numeric: tabular-nums; }

.head-card { margin-bottom: 12px; }
.scope { margin: 0; font-size: 14px; font-weight: 700; color: var(--app-text-1); }
.sub { margin: 4px 0 0; font-size: 12px; color: var(--app-text-3); }
.empty { margin: 8px 0; text-align: center; font-size: 13px; color: var(--app-text-3); }
</style>
