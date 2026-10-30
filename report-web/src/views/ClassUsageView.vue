<template>
  <div class="app-page">
    <!-- 月份切换 + 全部（批73c 第4条：班主任/级长看老师对本班学生的填写情况） -->
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
        <p class="sub">{{ all ? '全时段' : month }} · 老师对本班学生的填写情况</p>
      </div>
      <div class="app-card">
        <el-table :data="rows" size="small" :show-header="!!rows.length">
          <el-table-column prop="name" label="老师" min-width="80" fixed="left" />
          <el-table-column label="角色" min-width="72">
            <template #default="{ row }">{{ roleLabel(row.role) }}</template>
          </el-table-column>
          <el-table-column prop="moments" label="微光" align="center" min-width="52" />
          <el-table-column prop="evals" label="评价" align="center" min-width="52" />
          <el-table-column prop="scores" label="成绩" align="center" min-width="52" />
          <el-table-column prop="activities" label="扬长" align="center" min-width="52" />
          <el-table-column prop="talks" label="谈心" align="center" min-width="52" />
          <el-table-column prop="honors" label="荣誉" align="center" min-width="52" />
          <el-table-column prop="total" label="合计" align="center" min-width="56" />
        </el-table>
        <p v-if="!rows.length" class="empty">该时段还没有老师对本班学生的填写记录</p>
      </div>
      <p class="app-foot">口径：只统计所辖班级学生名下记录；扬长按课程创建者归属、荣誉按成功上传归属</p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/http'

/* 批73c 第4条：/api/class-usage/teachers 老师维度×六板块（范围=挂名班 ∪ 级长/级主任绑定年级班） */
interface Row {
  teacherId: number; name: string; role: string
  moments: number; evals: number; scores: number
  activities: number; talks: number; honors: number; total: number
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

function roleLabel(r: string) {
  return ({ ADMIN: '管理员', SECRETARY: '书记', LEADER: '领导', DIRECTOR: '主任', GRADE_LEADER: '级长',
    HEAD_TEACHER: '班主任', TEACHER: '任课教师' } as Record<string, string>)[r] ?? r
}

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
      `/api/class-usage/teachers?month=${all.value ? 'all' : month.value}`,
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
