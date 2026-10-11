<template>
  <div class="page">
    <motion.h2 class="page-title" :initial="{ opacity: 0, x: -16 }" :animate="{ opacity: 1, x: 0 }"
      :transition="{ type: 'spring', stiffness: 400, damping: 32 }"><el-icon><TrendCharts /></el-icon>成绩管理</motion.h2>
    <div class="toolbar">
      <el-select v-model="examId" placeholder="考试" style="min-width: 200px" @change="onExamChange">
        <el-option v-for="e in exams" :key="e.id"
          :label="`${e.name}（${e.termName}）${e.entryOpen === false ? ' · 录入已关闭' : ''}`" :value="e.id" />
      </el-select>
      <el-select v-model="classId" placeholder="班级" style="min-width: 140px" @change="loadSubjects">
        <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select v-if="subjects.length" v-model="subjectId" placeholder="学科" style="min-width: 140px" @change="load">
        <el-option v-for="s in subjects" :key="s.subjectId" :label="s.name" :value="s.subjectId" />
      </el-select>
      <!-- 批49：建考试=可传可改者（管理员/书记/级长）；汇总页=成绩可见角色（领导驾驶舱另有入口） -->
      <el-button v-if="auth.scoreUpload" type="primary" @click="examDialog = true">新建考试</el-button>
      <el-button v-if="auth.role !== 'LEADER'" @click="$router.push('/l/scores')">全校汇总</el-button>
      <!-- 批40e 撤回：删误建考试（含其下全部成绩）；批49=管理员/书记 -->
      <el-button v-if="['ADMIN', 'SECRETARY'].includes(auth.role) && examId" type="danger" plain @click="delExam">删除考试</el-button>
      <el-button v-if="classId" @click="downloadTemplate">下载模板</el-button>
      <el-button v-if="examId && subjectId && classId" @click="exportXlsx">导出 Excel</el-button>
      <el-button v-if="examId && subjectId && classId && editable" @click="importDialog = true">导入 Excel</el-button>
    </div>

    <el-card v-if="rows.length">
      <template #header>
        成绩单（满分 {{ fullScore ?? '未设置' }}，保存后自动计算班级/年级排名；空白 = 未录入，清空分数后保存即删除）
      </template>
      <el-table :data="rows" size="small" max-height="560" :row-class-name="rowClass">
        <el-table-column prop="studentNo" label="学号" min-width="110" align="center" />
        <el-table-column prop="name" label="姓名" min-width="110" />
        <el-table-column label="分数" min-width="150" align="center">
          <template #default="{ row }">
            <el-input-number v-if="editable" v-model="row.score" :min="0" :max="fullScore" :step="1"
              controls-position="right" style="width: 130px" />
            <span v-else>{{ row.score ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="班级名次" min-width="100" align="center">
          <template #default="{ row }">{{ row.classRank ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="年级名次" min-width="100" align="center">
          <template #default="{ row }">{{ row.gradeRank ?? '—' }}</template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 12px">
        <el-button type="primary" :disabled="!editable" :loading="saving" @click="save">批量保存</el-button>
        <el-tag v-if="!editable" size="small" type="info">只读（批49：仅管理员、书记、级长可录入）</el-tag>
      </div>
    </el-card>
    <el-empty v-else-if="loaded && examId && classId && !subjects.length"
      description="该考试尚未配置科目" />
    <el-empty v-else-if="loaded" description="选择考试/班级/学科后加载成绩单" />

    <el-dialog v-model="examDialog" title="新建考试" width="560px">
      <el-form label-width="90px">
        <el-form-item label="学期">
          <el-select v-model="newExam.termId" style="width: 100%">
            <el-option v-for="t in terms" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="考试名称">
          <el-input v-model="newExam.name" placeholder="如期中考试" />
        </el-form-item>
        <el-form-item label="考试日期">
          <el-date-picker v-model="newExam.examDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="考试科目">
          <div style="width: 100%; display: flex; flex-direction: column; gap: 6px">
            <div v-for="s in newExam.subjects" :key="s.subjectId" style="display: flex; gap: 8px; align-items: center">
              <el-select v-model="s.subjectId" style="flex: 1">
                <el-option v-for="sub in allSubjects" :key="sub.id" :label="sub.name" :value="sub.id" />
              </el-select>
              <el-input-number v-model="s.fullScore" :min="1" placeholder="满分" controls-position="right" style="width: 130px" />
              <el-button link type="danger" @click="newExam.subjects = newExam.subjects.filter(x => x !== s)">删除</el-button>
            </div>
            <el-button link type="primary" @click="newExam.subjects.push({ subjectId: undefined, fullScore: 100 })">+ 加科目</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="examDialog = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createExam">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importDialog" title="导入 Excel（单科）" width="460px">
      <el-upload drag :auto-upload="false" :limit="1" :on-change="(f: any) => (importFile = f.raw)" :on-remove="() => (importFile = null)">
        <div class="el-upload__text">拖入或点击选择 .xlsx（先用「下载模板」取该班名册）</div>
      </el-upload>
      <template #footer>
        <el-button @click="importDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!importFile" :loading="importing" @click="doImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { motion } from 'motion-v'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, apiForm, fetchBlob } from '../api/http'
import { saveFile } from '../api/nativeShare'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const auth = useAuthStore()
/* 批49 甲方口径：教师/班主任零入口零权限；此处进入者均为成绩可见角色
   （管理员/书记=可传可改，级长=绑定年级可传可改且受录入窗口约束，其余只读），editable 由后端下发 */

/** 批40e 撤回：删除整场考试（管理员；含全部成绩，报告重新生成即对齐） */
async function delExam() {
  const ex = exams.value.find((e: any) => e.id === examId.value)
  await ElMessageBox.confirm(
    `删除考试「${ex?.name ?? examId.value}」及其全部科目成绩？该操作不可恢复`, '删除考试',
    { type: 'warning' },
  )
  await api(`/api/score/exam/${examId.value}`, { method: 'DELETE' })
  ElMessage.success('已删除')
  exams.value = await api<any[]>('/api/score/exam/list')
  examId.value = exams.value[0]?.id
  rows.value = []
  if (examId.value) await loadSubjects()
}
const hlStudentId = ref<number>(Number(route.query.studentId) || 0)   // 从学生详情进来：高亮该生行

function rowClass({ row }: { row: any }) {
  return row.studentId === hlStudentId.value ? 'hl-row' : ''
}
const exams = ref<any[]>([])
const classes = ref<{ id: number; name: string }[]>([])
const terms = ref<any[]>([])
const allSubjects = ref<{ id: number; name: string }[]>([])
const subjects = ref<any[]>([])
const rows = ref<any[]>([])
const examId = ref<number>()
const classId = ref<number>()
const subjectId = ref<number>()
const fullScore = ref<number>()
const editable = ref(false)
const loaded = ref(false)
const saving = ref(false)
const examDialog = ref(false)
const creating = ref(false)
const newExam = ref<any>({ subjects: [{ subjectId: undefined, fullScore: 100 }] })
const importDialog = ref(false)
const importFile = ref<File | null>(null)
const importing = ref(false)

async function init() {
  const [ex, cs, ts, subs] = await Promise.all([
    api<any[]>('/api/score/exam/list'),
    api<{ id: number; name: string }[]>('/api/meta/my-classes'),
    api<any[]>('/api/meta/terms'),
    api<{ id: number; name: string }[]>('/api/meta/subjects'),
  ])
  exams.value = ex
  classes.value = cs
  terms.value = ts
  allSubjects.value = subs
  newExam.value.termId = ts.find((t: any) => t.isCurrent === 1)?.id ?? ts[0]?.id
  examId.value = ex[0]?.id
  if (cs.length) {
    classId.value = cs[0].id
    if (examId.value) await loadSubjects()
  }
  // 学生详情宫格带学生进来：自动选到该生所在班，成绩单里该生行高亮
  if (hlStudentId.value) {
    try {
      const s = await api<{ classId?: number }>(`/api/student/${hlStudentId.value}`)
      if (s.classId && cs.some((c) => c.id === s.classId)) {
        classId.value = s.classId
        await loadSubjects()
      }
    } catch { /* 深链失效则保持默认视图 */ }
  }
  // 无班无课教师走不到 load()：兜底置 loaded，让空态提示（而非空白）出现
  loaded.value = true
}

async function onExamChange() {
  subjects.value = []
  subjectId.value = undefined
  rows.value = []
  await loadSubjects()
}

async function loadSubjects() {
  subjects.value = []
  subjectId.value = undefined
  rows.value = []
  if (!examId.value || !classId.value) return
  subjects.value = await api<any[]>(`/api/score/subject-context?examId=${examId.value}&classId=${classId.value}`)
  if (subjects.value.length) {
    subjectId.value = subjects.value[0].subjectId
    await load()
  }
}

async function load() {
  if (!examId.value || !subjectId.value || !classId.value) return
  const d = await api<any>(`/api/score/list?examId=${examId.value}&subjectId=${subjectId.value}&classId=${classId.value}`)
  rows.value = d.rows
  fullScore.value = d.fullScore
  editable.value = d.editable
  loaded.value = true
}

async function save() {
  saving.value = true
  try {
    const r = await api<{ saved?: number }>('/api/score/entry', {
      method: 'PUT',
      json: {
        examId: examId.value,
        subjectId: subjectId.value,
        classId: classId.value,
        rows: rows.value.map((x: any) => ({ studentId: x.studentId, score: x.score })),
      },
    })
    ElMessage.success(`已保存 ${r.saved ?? rows.value.length} 条`)
    await load()
  } finally {
    saving.value = false
  }
}

async function createExam() {
  creating.value = true
  try {
    await api('/api/score/exam', {
      method: 'POST',
      json: {
        termId: newExam.value.termId,
        name: newExam.value.name,
        examDate: newExam.value.examDate,
        subjects: newExam.value.subjects.filter((s: any) => s.subjectId),
      },
    })
    ElMessage.success('考试已创建')
    examDialog.value = false
    exams.value = await api<any[]>('/api/score/exam/list')
  } finally {
    creating.value = false
  }
}

async function downloadTemplate() {
  const blob = await fetchBlob(`/api/score/template?classId=${classId.value}`)
  await saveFile(blob, '成绩导入模板.xlsx')
}

async function exportXlsx() {
  const blob = await fetchBlob(`/api/score/export?examId=${examId.value}&subjectId=${subjectId.value}&classId=${classId.value}`)
  const ex = exams.value.find((e: any) => e.id === examId.value)?.name ?? ''
  const sub = subjects.value.find((s: any) => s.subjectId === subjectId.value)?.name ?? ''
  const cls = classes.value.find((c: any) => c.id === classId.value)?.name ?? ''
  await saveFile(blob, `成绩_${ex}_${sub}_${cls}.xlsx`)
}

async function doImport() {
  if (!importFile.value) return
  importing.value = true
  try {
    const form = new FormData()
    form.append('file', importFile.value)
    const r = await apiForm<{ saved: number; skipped: { reason: string }[] }>(
      `/api/score/import?examId=${examId.value}&subjectId=${subjectId.value}&classId=${classId.value}`, form)
    ElMessage.success(`导入 ${r.saved} 条，跳过 ${r.skipped?.length ?? 0} 条`)
    if (r.skipped?.length) {
      ElMessage.warning(r.skipped.map((s: any) => s.reason).join('；').slice(0, 200))
    }
    importDialog.value = false
    importFile.value = null
    await load()
  } finally {
    importing.value = false
  }
}

onMounted(init)
</script>
