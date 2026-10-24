<template>
  <div class="page">
    <motion.h2 class="page-title" :initial="{ opacity: 0, x: -16 }" :animate="{ opacity: 1, x: 0 }"
      :transition="{ type: 'spring', stiffness: 400, damping: 32 }"><el-icon><TrendCharts /></el-icon>成绩管理</motion.h2>
    <div class="toolbar">
      <el-select v-model="examId" placeholder="考试" style="min-width: 200px" @change="onExamChange">
        <el-option v-for="e in exams" :key="e.id"
          :label="`${e.name}（${e.termName}）${e.entryOpen === false ? ' · 录入已关闭' : ''}`" :value="e.id" />
      </el-select>
      <el-select v-model="gradeId" placeholder="年级" style="min-width: 140px" @change="loadSubjects">
        <el-option v-for="g in grades" :key="g.id" :label="g.name" :value="g.id" />
      </el-select>
      <el-select v-if="subjectOptions.length" v-model="subjectId" placeholder="学科" style="min-width: 140px" @change="load">
        <el-option v-for="s in subjectOptions" :key="s.subjectId" :label="s.name" :value="s.subjectId" />
      </el-select>
      <!-- 批63：建/改考试=可传可改者（管理员/书记/级长）；汇总页=排名可见角色（领导驾驶舱另有入口） -->
      <el-button v-if="auth.scoreUpload" type="primary" @click="examDialog = true">新建考试</el-button>
      <el-button v-if="auth.scoreUpload && examId" @click="openEditExam">修改考试</el-button>
      <el-button v-if="auth.canSeeRank && auth.role !== 'LEADER'" @click="$router.push('/l/scores')">全校汇总</el-button>
      <!-- 批40e 撤回：删误建考试（含其下全部成绩）；批49=管理员/书记 -->
      <el-button v-if="['ADMIN', 'SECRETARY'].includes(auth.role) && examId" type="danger" plain @click="delExam">删除考试</el-button>
      <!-- 批63：导出=排名可见角色；模板/导入移入弹窗（导入永远单班） -->
      <el-button v-if="examId && gradeId && subjectId !== undefined && auth.canSeeRank" @click="exportXlsx">导出 Excel</el-button>
      <el-button v-if="auth.scoreUpload && examId" @click="openImport">导入 / 模板</el-button>
    </div>

    <el-card v-if="rows.length">
      <template #header>
        {{ totalMode
          ? `总分汇总（满分 ${fullScore ?? '未设置'}，按各科已录成绩现算，只读）`
          : `成绩单（满分 ${fullScore ?? '未设置'}，保存后自动计算班级/年级排名；空白 = 未录入，清空分数后保存即删除）` }}
      </template>
      <el-table :data="rows" size="small" max-height="560" :row-class-name="rowClass">
        <el-table-column prop="studentNo" label="学号" min-width="110" align="center" />
        <el-table-column prop="name" label="姓名" min-width="110" />
        <el-table-column prop="className" label="班级" min-width="120" />
        <el-table-column label="分数" min-width="150" align="center">
          <template #default="{ row }">
            <el-input-number v-if="editable" v-model="row.score" :min="0" :max="fullScore" :step="1"
              controls-position="right" style="width: 130px" />
            <span v-else>{{ row.score ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="等级" min-width="90" align="center">
          <template #default="{ row }">
            <el-input v-if="gradeEditable" v-model="row.grade" maxlength="2" size="small"
              style="width: 75px" placeholder="—" />
            <span v-else>{{ row.grade ?? '—' }}</span>
          </template>
        </el-table-column>
        <!-- 批63：排名列只对管理面角色显示（服务端已置 null，此处仅显示层） -->
        <el-table-column v-if="auth.canSeeRank" label="班级名次" min-width="100" align="center">
          <template #default="{ row }">{{ row.classRank ?? '—' }}</template>
        </el-table-column>
        <el-table-column v-if="auth.canSeeRank" label="年级名次" min-width="100" align="center">
          <template #default="{ row }">{{ row.gradeRank ?? '—' }}</template>
        </el-table-column>
        <!-- 批65：单条删除显式入口（后端 entry 双空=删行；原「清空分数再保存」隐式路径保留） -->
        <el-table-column v-if="editable" label="操作" width="70" align="center">
          <template #default="{ row }">
            <el-button v-if="row.score != null || row.grade != null" link type="danger" size="small"
              @click="delScore(row)">删除</el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 12px">
        <el-button type="primary" :disabled="!editable && !gradeEditable" :loading="saving" @click="save">批量保存</el-button>
        <el-tag v-if="!editable && !gradeEditable" size="small" type="info">{{ totalMode ? '总分模式只读' : '只读（批63：仅管理员、书记、级长可录入）' }}</el-tag>
      </div>
    </el-card>
    <el-empty v-else-if="loaded && examId && gradeId && !subjects.length"
      description="该考试尚未配置科目" />
    <el-empty v-else-if="loaded" description="选择考试/年级/学科后加载成绩单" />

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

    <!-- 批63：修改已设考试（增删科目/改各科满分；名称日期学期不动） -->
    <el-dialog v-model="editDialog" title="修改考试科目" width="560px">
      <el-alert type="warning" :closable="false" show-icon
        title="删除科目会连带删除该科已录成绩；降低满分不得低于该科已录最高分" style="margin-bottom: 12px" />
      <el-form label-width="90px">
        <el-form-item label="考试科目">
          <div style="width: 100%; display: flex; flex-direction: column; gap: 6px">
            <div v-for="s in editExam.subjects" :key="s.subjectId" style="display: flex; gap: 8px; align-items: center">
              <el-select v-model="s.subjectId" style="flex: 1">
                <el-option v-for="sub in allSubjects" :key="sub.id" :label="sub.name" :value="sub.id" />
              </el-select>
              <el-input-number v-model="s.fullScore" :min="1" placeholder="满分" controls-position="right" style="width: 130px" />
              <el-button link type="danger" @click="removeEditSubject(s)">删除</el-button>
            </div>
            <el-button link type="primary" @click="editExam.subjects.push({ subjectId: undefined, fullScore: 100 })">+ 加科目</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="updating" @click="updateExam">保存修改</el-button>
      </template>
    </el-dialog>

    <!-- 批63：模板/导入弹窗（导入永远单班，模板含该考试全部科目列） -->
    <el-dialog v-model="importDialog" title="导入成绩（单班）" width="460px">
      <el-form label-width="90px">
        <el-form-item label="班级">
          <el-select v-model="importClassId" style="width: 100%">
            <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-button :disabled="!importClassId" @click="downloadTemplate">下载模板（按考试科目）</el-button>
      <el-upload drag :auto-upload="false" :limit="1" style="margin-top: 12px"
        :on-change="(f: any) => (importFile = f.raw)" :on-remove="() => (importFile = null)">
        <div class="el-upload__text">拖入或点击选择 .xlsx（多科列一次导入；某科留空 = 不动该科）</div>
      </el-upload>
      <template #footer>
        <el-button @click="importDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!importFile || !importClassId" :loading="importing" @click="doImport">导入</el-button>
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
/* 批63 口径：管理员/书记=可传可改，级长=绑定年级可传可改且受录入窗口约束，
   班主任/科任=只读（无排名列/无汇总/无导出，服务端已置 null；分数+等级可见），editable/canSeeRank 由后端下发 */

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
const classes = ref<{ id: number; name: string; gradeId: number }[]>([])
const grades = ref<{ id: number; name: string }[]>([])
const terms = ref<any[]>([])
const allSubjects = ref<{ id: number; name: string }[]>([])
const subjects = ref<any[]>([])
const rows = ref<any[]>([])
const examId = ref<number>()
const gradeId = ref<number>()
const subjectId = ref<number>()
const fullScore = ref<number>()
const editable = ref(false)
const gradeEditable = ref(false) // 批63c 等级上传通道（总分模式也开）
const loaded = ref(false)
const saving = ref(false)
const examDialog = ref(false)
const creating = ref(false)
const newExam = ref<any>({ subjects: [{ subjectId: undefined, fullScore: 100 }] })
const editDialog = ref(false)
const updating = ref(false)
const editExam = ref<any>({ subjects: [] })
const importDialog = ref(false)
const importClassId = ref<number>()
const importFile = ref<File | null>(null)
const importing = ref(false)

/** 批63：subjectId=0 为总分选项（请求时不传 subjectId，后端汇总各科并现算排名） */
const TOTAL = 0
const subjectOptions = ref<any[]>([])
const totalMode = () => subjectId.value === TOTAL

async function init() {
  const [ex, cs, ts, subs, gs] = await Promise.all([
    api<any[]>('/api/score/exam/list'),
    api<{ id: number; name: string; gradeId: number }[]>('/api/meta/my-classes'),
    api<any[]>('/api/meta/terms'),
    api<{ id: number; name: string }[]>('/api/meta/subjects'),
    api<{ id: number; name: string }[]>('/api/meta/grades'),
  ])
  exams.value = ex
  classes.value = cs
  terms.value = ts
  allSubjects.value = subs
  // 批63：第二行班级下拉改年级下拉（可见班级去重出年级，按年级表排序取名）
  const mine = new Set(cs.map((c) => c.gradeId).filter(Boolean))
  grades.value = gs.filter((g) => mine.has(g.id)).map((g) => ({ id: g.id, name: g.name }))
  newExam.value.termId = ts.find((t: any) => t.isCurrent === 1)?.id ?? ts[0]?.id
  examId.value = ex[0]?.id
  if (grades.value.length) {
    gradeId.value = grades.value[0].id
    if (examId.value) await loadSubjects()
  }
  // 学生详情宫格带学生进来：自动选到该生所在年级，成绩单里该生行高亮
  if (hlStudentId.value) {
    try {
      const s = await api<{ classId?: number }>(`/api/student/${hlStudentId.value}`)
      const cls = cs.find((c) => c.id === s.classId)
      if (cls?.gradeId && grades.value.some((g) => g.id === cls.gradeId)) {
        gradeId.value = cls.gradeId
        await loadSubjects()
      }
    } catch { /* 深链失效则保持默认视图 */ }
  }
  // 无班无课教师走不到 load()：兜底置 loaded，让空态提示（而非空白）出现
  loaded.value = true
}

async function onExamChange() {
  subjects.value = []
  subjectOptions.value = []
  subjectId.value = undefined
  rows.value = []
  await loadSubjects()
}

async function loadSubjects() {
  subjects.value = []
  subjectOptions.value = []
  subjectId.value = undefined
  rows.value = []
  if (!examId.value || !gradeId.value) return
  subjects.value = await api<any[]>(`/api/score/subject-context?examId=${examId.value}&gradeId=${gradeId.value}`)
  subjectOptions.value = [...subjects.value, { subjectId: TOTAL, name: '总分' }]
  if (subjects.value.length) {
    subjectId.value = subjects.value[0].subjectId
    await load()
  }
}

async function load() {
  if (!examId.value || !gradeId.value || subjectId.value === undefined) return
  const sub = totalMode() ? '' : `&subjectId=${subjectId.value}`
  const d = await api<any>(`/api/score/list?examId=${examId.value}&gradeId=${gradeId.value}${sub}`)
  rows.value = d.rows
  fullScore.value = d.fullScore
  editable.value = d.editable
  gradeEditable.value = d.gradeEditable
  loaded.value = true
}

/** 批65：删该生该科成绩（含等级）——entry 传 score/grade 双空=整行删除（后端 writeRows 语义） */
async function delScore(row: any) {
  await ElMessageBox.confirm(`删除 ${row.name} 的该科成绩与等级？`, '删除成绩', { type: 'warning' })
  await api('/api/score/entry', {
    method: 'PUT',
    json: {
      examId: examId.value,
      subjectId: subjectId.value,
      rows: [{ studentId: row.studentId, score: null, grade: null }],
    },
  })
  ElMessage.success('已删除')
  await load()
}

async function save() {
  saving.value = true
  try {
    let saved = 0
    if (editable.value) {
      const r = await api<{ saved?: number }>('/api/score/entry', {
        method: 'PUT',
        json: {
          examId: examId.value,
          subjectId: subjectId.value,
          rows: rows.value.map((x: any) => ({ studentId: x.studentId, score: x.score })),
        },
      })
      saved += r.saved ?? 0
    }
    if (gradeEditable.value) { // 批63c 等级独立通道（不动分数；总分模式 subjectId=null=总分等级）
      const r = await api<{ saved?: number }>('/api/score/entry-grade', {
        method: 'PUT',
        json: {
          examId: examId.value,
          subjectId: totalMode() ? null : subjectId.value,
          rows: rows.value.map((x: any) => ({ studentId: x.studentId, grade: x.grade || null })),
        },
      })
      saved += r.saved ?? 0
    }
    ElMessage.success(`已保存 ${saved} 条`)
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

/** 批63：打开修改考试弹窗，科目预填当前考试配置 */
function openEditExam() {
  editExam.value.subjects = subjects.value.map((s: any) => ({ subjectId: s.subjectId, fullScore: s.fullScore }))
  editDialog.value = true
}

/** 删已设科目须二次确认：连带删除该科已录成绩 */
async function removeEditSubject(s: any) {
  if (s.subjectId) {
    const name = allSubjects.value.find((x) => x.id === s.subjectId)?.name ?? s.subjectId
    await ElMessageBox.confirm(`删除科目「${name}」将连带删除该科已录成绩，确定？`, '删除科目', { type: 'warning' })
  }
  editExam.value.subjects = editExam.value.subjects.filter((x: any) => x !== s)
}

async function updateExam() {
  const list = editExam.value.subjects.filter((s: any) => s.subjectId)
  if (!list.length) {
    ElMessage.warning('至少保留一个科目')
    return
  }
  updating.value = true
  try {
    await api(`/api/score/exam/${examId.value}`, { method: 'PUT', json: { subjects: list } })
    ElMessage.success('考试科目已更新')
    editDialog.value = false
    await loadSubjects()
  } finally {
    updating.value = false
  }
}

function openImport() {
  importClassId.value = classes.value[0]?.id
  importFile.value = null
  importDialog.value = true
}

async function downloadTemplate() {
  const blob = await fetchBlob(`/api/score/template?examId=${examId.value}&classId=${importClassId.value}`)
  const cls = classes.value.find((c) => c.id === importClassId.value)?.name ?? ''
  await saveFile(blob, `成绩导入模板_${cls}.xlsx`)
}

async function exportXlsx() {
  const sub = totalMode() ? '' : `&subjectId=${subjectId.value}`
  const blob = await fetchBlob(`/api/score/export?examId=${examId.value}&gradeId=${gradeId.value}${sub}`)
  const ex = exams.value.find((e: any) => e.id === examId.value)?.name ?? ''
  const sname = subjectOptions.value.find((s: any) => s.subjectId === subjectId.value)?.name ?? ''
  const gname = grades.value.find((g) => g.id === gradeId.value)?.name ?? ''
  await saveFile(blob, `成绩_${ex}_${sname}_${gname}.xlsx`)
}

async function doImport() {
  if (!importFile.value) return
  importing.value = true
  try {
    const form = new FormData()
    form.append('file', importFile.value)
    const r = await apiForm<{ saved: number; skipped: { reason: string }[] }>(
      `/api/score/import?examId=${examId.value}&classId=${importClassId.value}`, form)
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
