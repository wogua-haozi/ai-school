<template>
  <div>
    <h4>成绩单（批50：管理员在电脑上即可查看/录入/导入导出；与 App 端「成绩管理」同数据同权限；批63 改年级视角+多科导入）</h4>
    <div class="bar">
      <el-select v-model="examId" placeholder="考试" style="width: 220px" @change="onExamChange">
        <el-option v-for="e in exams" :key="e.id"
          :label="`${e.name}（${e.termName}）${e.entryOpen === false ? ' · 录入已关闭' : ''}`" :value="e.id" />
      </el-select>
      <el-select v-model="gradeId" placeholder="年级" style="width: 150px" @change="loadSubjects">
        <el-option v-for="g in grades" :key="g.id" :label="g.name" :value="g.id" />
      </el-select>
      <el-select v-if="subjectOptions.length" v-model="subjectId" placeholder="学科" style="width: 150px" @change="load">
        <el-option v-for="s in subjectOptions" :key="s.subjectId" :label="s.name" :value="s.subjectId" />
      </el-select>
      <el-button type="primary" @click="examDialog = true">新建考试</el-button>
      <el-button v-if="examId" @click="openEditExam">修改考试</el-button>
      <el-button @click="$router.push('/l/scores')">全校汇总</el-button>
      <el-button v-if="examId && gradeId && subjectId !== undefined" @click="exportXlsx">导出 Excel</el-button>
      <el-button v-if="examId" @click="openImport">导入 / 模板</el-button>
    </div>

    <el-table v-if="rows.length" :data="rows" size="small" max-height="560">
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
      <el-table-column label="班级名次" min-width="100" align="center">
        <template #default="{ row }">{{ row.classRank ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="年级名次" min-width="100" align="center">
        <template #default="{ row }">{{ row.gradeRank ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" align="center">
        <template #default="{ row }">
          <el-button v-if="editable || gradeEditable" link type="primary" @click="saveOne(row)">存</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-else-if="loaded && examId && gradeId && !subjects.length" description="该考试尚未配置科目" />
    <el-empty v-else-if="loaded" description="选择考试/年级/学科后加载成绩单" />
    <div v-if="rows.length" class="bar" style="margin-top: 12px">
      <el-button type="primary" :disabled="!editable && !gradeEditable" :loading="saving" @click="save">批量保存</el-button>
      <el-tag v-if="!editable && !gradeEditable" size="small" type="info">{{ totalMode() ? '总分模式只读' : '只读（仅管理员、书记、级长可录入）' }}</el-tag>
    </div>
    <p class="hint">空白 = 未录入，清空分数后保存即删除；保存后自动计算班级/年级排名。考试开关窗口与删考试仍在「考试管理」页。</p>

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
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, apiForm, fetchBlob } from '../../api/http'
import { saveFile } from '../../api/nativeShare'

/* 批50 管理端成绩页：复用 /api/score/* 既有端点（权限后端硬校验：
   ADMIN/书记可传可改，级长绑定年级，其余角色 403），editable 以后端下发为准 */
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
  // 批63：班级下拉改年级下拉（管理员可见全部年级）
  const mine = new Set(cs.map((c) => c.gradeId).filter(Boolean))
  grades.value = gs.filter((g) => mine.has(g.id)).map((g) => ({ id: g.id, name: g.name }))
  newExam.value.termId = ts.find((t: any) => t.isCurrent === 1)?.id ?? ts[0]?.id
  examId.value = ex[0]?.id
  if (grades.value.length && examId.value) {
    gradeId.value = grades.value[0].id
    await loadSubjects()
  }
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


/** 单行快存：只提交该生（电脑上逐个改分/等级即存；批63c 等级走独立通道） */
async function saveOne(row: any) {
  if (editable.value) {
    await api('/api/score/entry', {
      method: 'PUT',
      json: { examId: examId.value, subjectId: subjectId.value,
        rows: [{ studentId: row.studentId, score: row.score }] },
    })
  }
  if (gradeEditable.value) {
    await api('/api/score/entry-grade', {
      method: 'PUT',
      json: { examId: examId.value, subjectId: totalMode() ? null : subjectId.value,
        rows: [{ studentId: row.studentId, grade: row.grade || null }] },
    })
  }
  ElMessage.success(`已保存 ${row.name}`)
  await load()
}

async function save() {
  saving.value = true
  try {
    let saved = 0
    if (editable.value) {
      const r = await api<{ saved?: number }>('/api/score/entry', {
        method: 'PUT',
        json: { examId: examId.value, subjectId: subjectId.value,
          rows: rows.value.map((x: any) => ({ studentId: x.studentId, score: x.score })) },
      })
      saved += r.saved ?? 0
    }
    if (gradeEditable.value) { // 批63c 等级独立通道（总分模式 subjectId=null=总分等级）
      const r = await api<{ saved?: number }>('/api/score/entry-grade', {
        method: 'PUT',
        json: { examId: examId.value, subjectId: totalMode() ? null : subjectId.value,
          rows: rows.value.map((x: any) => ({ studentId: x.studentId, grade: x.grade || null })) },
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
