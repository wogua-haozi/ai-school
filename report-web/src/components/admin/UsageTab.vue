<template>
  <div>
    <h4>教师使用情况（微光/素养评价/扬长课程/荣誉证书/导师谈心/报告/成绩/登录/AI；零活跃也在列，便于督促）</h4>
    <div class="bar">
      <el-date-picker v-model="month" type="month" value-format="YYYY-MM" placeholder="按自然月"
        clearable style="width: 130px" @change="onMonthChange" />
      <el-radio-group v-model="days" @change="onDaysChange">
        <el-radio-button v-for="r in RANGES" :key="r.days" :value="r.days">{{ r.label }}</el-radio-button>
      </el-radio-group>
      <el-input v-model="kw" clearable placeholder="按姓名过滤" style="width: 160px" />
      <el-button type="primary" @click="doExport">导出 Excel</el-button>
      <span class="hint">共 {{ shown.length }} 人 · 按总活跃降序</span>
    </div>
    <el-table :data="paged" size="small">
      <el-table-column prop="name" label="姓名" width="110" />
      <el-table-column label="角色" width="90">
        <template #default="{ row }">
          <el-tag size="small">{{ ROLE_CN[row.role] ?? row.role }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="moments" label="微光时刻" width="90" sortable />
      <el-table-column prop="evals" label="学生评价" width="90" sortable />
      <el-table-column prop="activities" label="扬长课程" width="90" sortable />
      <el-table-column prop="honors" label="荣誉证书" width="90" sortable />
      <el-table-column prop="talks" label="导师谈心" width="90" sortable />
      <el-table-column prop="logins" label="登录" width="70" sortable />
      <el-table-column label="最近登录" width="140">
        <template #default="{ row }">{{ row.lastLogin ? fmtTime(row.lastLogin) : '—' }}</template>
      </el-table-column>
      <el-table-column prop="reports" label="报告生成" width="90" />
      <el-table-column prop="scores" label="成绩录入" width="90" />
      <el-table-column label="AI tokens" width="100">
        <template #default="{ row }">{{ shortTokens(row.tokens) }}</template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="shown.length > size" layout="prev, pager, next, total" :total="shown.length"
      :page-size="size" v-model:current-page="page" style="margin-top: 10px" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, fetchBlob } from '../../api/http'
import { saveFile } from '../../api/nativeShare'

/* 批64 管理端「教师使用情况」：与领导端 /l/teachers 同源 /api/leader/teacher-usage（口径=在职教职工，演示号天然排除）
   批70：自然月口径 + 扬长课程/荣誉证书/导师谈心三列 + 导出（选月=月度统计，选区间=近 N 天） */
const RANGES = [
  { label: '近7天', days: 7 },
  { label: '近30天', days: 30 },
  { label: '近90天', days: 90 },
]
const ROLE_CN: Record<string, string> = { ADMIN: '管理员', SECRETARY: '书记', LEADER: '领导', DIRECTOR: '主任',
  GRADE_LEADER: '级长', HEAD_TEACHER: '班主任', TEACHER: '任课教师' }

interface UsageRow {
  teacherId: number; name: string; role: string
  reports: number; tokens: number; evals: number
  scores: number; moments: number; logins: number
  activities: number; honors: number; talks: number
  lastLogin: string | null
}

const rows = ref<UsageRow[]>([])
const now = new Date()
const month = ref<string>(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`) // 默认当月（月度统计主诉求）
const days = ref(30)
const kw = ref('')
const page = ref(1)
const size = 50

const shown = computed(() =>
  kw.value ? rows.value.filter((r) => r.name.includes(kw.value.trim())) : rows.value)
const paged = computed(() => shown.value.slice((page.value - 1) * size, page.value * size))

function fmtTime(t: string) {
  return t.slice(0, 16).replace('T', ' ')
}
function shortTokens(v: number) {
  return v >= 10000 ? (v / 10000).toFixed(1) + '万' : String(v)
}

function qs() {
  return month.value ? `month=${month.value}` : `days=${days.value}`
}

async function load() {
  const d = await api<{ rows: UsageRow[] }>(`/api/leader/teacher-usage?${qs()}`)
  rows.value = d.rows ?? []
  page.value = 1
}

/* 两种口径互斥：选月清区间，选区间清月 */
function onMonthChange() { load() }
function onDaysChange() { month.value = ''; load() }

async function doExport() {
  const blob = await fetchBlob(`/api/leader/teacher-usage/export?${qs()}`)
  const label = month.value || `近${days.value}天`
  await saveFile(blob, `教师操作统计_${label}.xlsx`)
}

onMounted(load)
</script>

<style scoped>
.bar { display: flex; align-items: center; margin-bottom: 10px; flex-wrap: wrap; gap: 8px; }
.hint { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
