<template>
  <div class="page">
    <motion.h2 class="page-title" :initial="{ opacity: 0, x: -16 }" :animate="{ opacity: 1, x: 0 }"
      :transition="{ type: 'spring', stiffness: 400, damping: 32 }"><el-icon><Document /></el-icon>报告列表</motion.h2>
    <div class="toolbar">
      <el-radio-group v-if="auth.role === 'ADMIN'" v-model="scope" @change="onScopeChange">
        <el-radio-button value="class">班级</el-radio-button>
        <el-radio-button value="grade">全年级</el-radio-button>
      </el-radio-group>
      <el-select v-if="scope === 'grade'" v-model="gradeId" placeholder="年级" style="min-width: 140px" @change="reload">
        <el-option v-for="g in grades" :key="g.id" :label="g.name" :value="g.id" />
      </el-select>
      <el-select v-else v-model="classId" placeholder="班级" style="min-width: 140px" @change="reload">
        <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select v-model="termId" placeholder="学期" style="min-width: 160px" @change="onTermChange">
        <el-option v-for="t in terms" :key="t.id" :label="t.name" :value="t.id" />
      </el-select>
      <el-select v-model="reportType" style="min-width: 120px" @change="reload">
        <el-option label="学期报告" value="TERM" />
        <el-option label="学年报告" value="YEAR" />
        <el-option label="在校报告" value="SCHOOL" />
      </el-select>
      <el-select v-if="reportType === 'TERM'" v-model="reportPeriod" style="min-width: 100px" @change="reload">
        <el-option label="期末" value="FINAL" />
        <el-option label="期中" value="MID" />
      </el-select>
      <el-button v-if="scope === 'grade'" type="primary" :loading="batching" :disabled="!gradeId || !termId" @click="startGrade">
        批量生成全年级
      </el-button>
      <el-button v-else type="primary" :loading="batching" :disabled="!classId || !termId" @click="startBatch">
        批量生成全班
      </el-button>
      <el-link v-if="scope === 'class'" type="primary" @click="$router.push('/')">看任务进度 →</el-link>
    </div>

    <el-table :data="rows" size="small" border>
      <el-table-column v-if="scope === 'grade'" prop="className" label="班级" width="110" />
      <el-table-column prop="studentNo" label="学号" width="90" />
      <el-table-column prop="name" label="学生" min-width="90" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="tagType(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="error" label="错误" min-width="120" show-overflow-tooltip />
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button
            v-if="row.status !== '成功'"
            size="small" type="primary"
            :loading="generating === row.studentId"
            :disabled="generating !== null && generating !== row.studentId"
            @click="generateOne(row)"
          >{{ row.status === '失败' ? '重新生成' : '生成' }}</el-button>
          <el-button v-else size="small" type="primary"
            :loading="generating === row.studentId"
            :disabled="generating !== null && generating !== row.studentId"
            @click="regenerate(row)">重新生成</el-button>
          <el-button v-if="row.status === '成功'" size="small" type="primary" @click="preview(row)">预览</el-button>
          <el-button v-if="row.status === '成功'" size="small" @click="download(row)">下载</el-button>
          <!-- 批65：删误生成报告（生成教师本人或管理员；删记录+教师版/家长版双 PDF） -->
          <el-button v-if="row.reportId && row.canDelete" link type="danger" size="small"
            :loading="deleting === row.reportId" @click="delReport(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { motion } from 'motion-v'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fetchBlob } from '../api/http'
import { saveFile } from '../api/nativeShare'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const classes = ref<{ id: number; name: string }[]>([])
const grades = ref<{ id: number; name: string }[]>([])
const terms = ref<{ id: number; name: string; startDate?: string; endDate?: string }[]>([])
const classId = ref<number>()
const gradeId = ref<number>()
const termId = ref<number>()
const reportType = ref<'TERM' | 'YEAR' | 'SCHOOL'>('TERM')
const reportPeriod = ref<'FINAL' | 'MID'>('FINAL')
const scope = ref<'class' | 'grade'>('class')
const rows = ref<{ className?: string; studentId: number; studentNo: string; name: string; status: string; error?: string; reportId?: number; canDelete?: boolean }[]>([])
const generating = ref<number | null>(null)
const deleting = ref<number | null>(null)
const batching = ref(false)

/** 期次默认预判（批42）：已结束的学期=期末；进行中=今天在学期前半→期中，后半→期末 */
function predictPeriod() {
  const t = terms.value.find((x) => x.id === termId.value)
  if (!t?.startDate || !t.endDate) return 'FINAL'
  const now = new Date()
  const mid = new Date(new Date(t.startDate).getTime()
    + (new Date(t.endDate).getTime() - new Date(t.startDate).getTime()) / 2)
  return now <= mid ? 'MID' : 'FINAL'
}

function tagType(s: string) {
  return s === '成功' ? 'success' : s === '失败' ? 'danger' : s === '未生成' ? 'info' : 'warning'
}

async function init() {
  classes.value = await api('/api/meta/my-classes')
  terms.value = await api('/api/meta/terms')
  termId.value = terms.value.find((t: any) => t.isCurrent === 1)?.id ?? terms.value[0]?.id
  reportPeriod.value = predictPeriod()
  if (auth.role === 'ADMIN') {
    grades.value = await api('/api/meta/grades')
    gradeId.value = grades.value[0]?.id
  }
  if (classes.value.length) {
    classId.value = classes.value[0].id
    await reload()
  }
}

function onScopeChange() {
  reload()
}

function onTermChange() {
  reportPeriod.value = predictPeriod()
  reload()
}

async function reload() {
  if (scope.value === 'grade') {
    if (!gradeId.value || !termId.value) return
    const cls = classes.value.filter((c) => c.gradeId === gradeId.value)
    const parts = await Promise.all(cls.map(async (c) => {
      const stu = await api<{ records: { id: number; studentNo: string; name: string }[] }>(
        `/api/student/list?classId=${c.id}&page=1&size=100`,
      )
      const reports = await api<{ studentId: number; status: string; error?: string; reportId?: number; canDelete?: boolean }[]>(
        `/api/report/list?classId=${c.id}&termId=${termId.value}&scopeType=${reportType.value}`,
      )
      const byStu = new Map(reports.map((r) => [`${r.studentId}:${(r as { period?: string }).period ?? 'FINAL'}`, r]))
      return stu.records.map((s) => {
        const r = byStu.get(`${s.id}:${reportPeriod.value}`)
        return { className: c.name, studentId: s.id, studentNo: s.studentNo, name: s.name, status: r?.status ?? '未生成', error: r?.error, reportId: r?.reportId, canDelete: r?.canDelete }
      })
    }))
    rows.value = parts.flat()
    return
  }
  if (!classId.value || !termId.value) return
  const stu = await api<{ records: { id: number; studentNo: string; name: string }[] }>(
    `/api/student/list?classId=${classId.value}&page=1&size=100`,
  )
  const reports = await api<{ studentId: number; status: string; error?: string; reportId?: number; canDelete?: boolean }[]>(
    `/api/report/list?classId=${classId.value}&termId=${termId.value}&scopeType=${reportType.value}`,
  )
  const byStu = new Map(reports.map((r) => [`${r.studentId}:${(r as { period?: string }).period ?? 'FINAL'}`, r]))
  rows.value = stu.records.map((s) => {
    const r = byStu.get(`${s.id}:${reportPeriod.value}`)
    return { studentId: s.id, studentNo: s.studentNo, name: s.name, status: r?.status ?? '未生成', error: r?.error, reportId: r?.reportId, canDelete: r?.canDelete }
  })
}

/** 成功行重新生成：确认后按最新数据再出一份（后端 INSERT 新报告行，旧版本保留，家长端/列表恒显最新） */
async function regenerate(row: { studentId: number; name: string }) {
  const label = reportType.value === 'TERM' ? `${termNameOf()}${reportPeriod.value === 'MID' ? '期中' : '期末'}` : termNameOf()
  await ElMessageBox.confirm(
    `按最新数据重新生成 ${row.name} 的${label}报告？新增的奖项、微光、成绩都会刷新进新版本，历史版本仍保留可追溯，家长端显示新版。`,
    '重新生成', { type: 'warning' })
  await generateOne(row)
}

function termNameOf() {
  return terms.value.find((t) => t.id === termId.value)?.name ?? ''
}

/** 单份生成：提交后轮询任务到终态（验收② 30s 内出 PDF） */
async function generateOne(row: { studentId: number; name: string }) {
  if (!termId.value) return
  generating.value = row.studentId
  try {
    const t = await api<{ taskId: number }>('/api/report/generate', {
      method: 'POST',
      json: { studentId: row.studentId, termId: termId.value, reportType: reportType.value, reportPeriod: reportPeriod.value },
    })
    const start = Date.now()
    for (;;) {
      await new Promise((r) => setTimeout(r, 2000))
      const p = await api<{ status: string; done: number; failed: number }>(`/api/report/task/${t.taskId}`)
      if (['成功', '失败', '部分失败'].includes(p.status) || Date.now() - start > 60_000) {
        ElMessage[p.failed > 0 ? 'error' : 'success'](`${row.name} ${p.status}（${Math.round((Date.now() - start) / 1000)}s）`)
        break
      }
    }
    await reload()
  } finally {
    generating.value = null
  }
}

async function startBatch() {
  if (!classId.value || !termId.value) return
  const existing = rows.value.filter((r) => r.status === '成功').length
  if (existing > 0) {
    await ElMessageBox.confirm(
      `该班本期已有 ${existing} 份报告，重新生成将出全新版本（家长端显示新版，历史版本留档可追溯）。继续？`,
      '批量生成', { type: 'warning' })
  }
  batching.value = true
  try {
    const t = await api<{ taskId: number }>('/api/report/generate-batch', {
      method: 'POST',
      json: { classId: classId.value, termId: termId.value, reportType: reportType.value, reportPeriod: reportPeriod.value },
    })
    ElMessage.success(`批量任务 #${t.taskId} 已创建`)
    router.push('/')
  } finally {
    batching.value = false
  }
}

/** 全年级批量（仅管理员）：任务页看进度 */
async function startGrade() {
  if (!gradeId.value || !termId.value) return
  await ElMessageBox.confirm('全年级批量生成报告？已在队列排队，可在任务进度页查看。', '批量生成')
  batching.value = true
  try {
    const t = await api<{ taskId: number }>('/api/report/generate-grade', {
      method: 'POST',
      json: { gradeId: gradeId.value, termId: termId.value, reportType: reportType.value, reportPeriod: reportPeriod.value },
    })
    ElMessage.success(`年级批量任务 #${t.taskId} 已创建`)
    router.push('/')
  } finally {
    batching.value = false
  }
}

function preview(row: { reportId?: number }) {
  router.push(`/reports/${row.reportId}/preview`)
}

async function download(row: { name: string; reportId?: number }) {
  if (!row.reportId) return
  const blob = await fetchBlob(`/api/report/file/${row.reportId}?disposition=attachment`)
  const label = reportType.value === 'YEAR' ? '学年报告' : reportType.value === 'SCHOOL' ? '在校报告' : '素质报告单'
  await saveFile(blob, `${row.name}-${label}.pdf`)
}

/** 批65：删除误生成的报告（生成教师本人或管理员；记录与教师版/家长版双 PDF 一并删，不可恢复） */
async function delReport(row: { name: string; reportId?: number }) {
  if (!row.reportId) return
  await ElMessageBox.confirm(
    `删除 ${row.name} 的这份报告？PDF 文件与记录一并删除、不可恢复（需要重出时可再生成）。`, '删除报告',
    { type: 'warning' })
  deleting.value = row.reportId
  try {
    await api(`/api/report/${row.reportId}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    await reload()
  } finally {
    deleting.value = null
  }
}

onMounted(init)
</script>
