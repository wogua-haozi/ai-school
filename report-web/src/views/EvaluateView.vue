<template>
  <div class="page">
    <motion.h2 class="page-title" :initial="{ opacity: 0, x: -16 }" :animate="{ opacity: 1, x: 0 }"
      :transition="{ type: 'spring', stiffness: 400, damping: 32 }"><el-icon><ChatDotRound /></el-icon>素养评价</motion.h2>
    <div class="toolbar">
      <!-- 批43 双轨：学生个人评价（进个人档案） / 班级整体记分（只进文明班评比）；无班级记分权限者不显示切换 -->
      <el-radio-group v-if="canClassScore" v-model="mode">
        <el-radio-button value="stu">学生评价</el-radio-button>
        <el-radio-button value="class">班级记分</el-radio-button>
      </el-radio-group>
      <template v-if="mode === 'stu'">
        <el-select v-model="classId" placeholder="班级" style="min-width: 140px" @change="loadStudents">
          <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="studentIds" multiple collapse-tags collapse-tags-tooltip filterable
          placeholder="选择学生（可多选批量）" style="min-width: 200px" @change="loadHistory">
          <el-option v-for="s in students" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>
        <el-select v-model="termId" placeholder="学期" style="min-width: 160px" @change="loadHistory">
          <el-option v-for="t in terms" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
        <el-button v-if="classId && termId" @click="exportXlsx">导出本班学期评价</el-button>
      </template>
    </div>

    <!-- 批43：班级整体加减分——不落具体学生，只进文明班评比（检查日+分值），不进学生个人档案 -->
    <template v-if="mode === 'class'">
      <el-card>
        <template #header>
          班级整体加减分
          <span class="hint">对班级层面的加/减分（如卫生检查、全班获奖），计入文明班评比；不落到具体学生</span>
        </template>
        <el-form label-width="90px">
          <el-form-item label="班级">
            <el-select v-model="csClassId" placeholder="选择班级" style="min-width: 200px" @change="loadCsRecords">
              <el-option v-for="c in csClasses" :key="c.id" :label="`${c.gradeName}${c.name}`" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="日期">
            <el-date-picker v-model="csDate" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width: 180px" />
            <span class="hint">限当日往前 31 天</span>
          </el-form-item>
          <el-form-item label="大项">
            <el-select v-model="csCategory" placeholder="不归类" clearable style="width: 240px" @change="onCsCategoryChange">
              <el-option v-for="(s, i) in conductSections" :key="i" :label="s.name" :value="i + 1" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="csCategoryItems.length" label="常见条目">
            <div class="cs-items">
              <el-tag v-for="(it, i) in csCategoryItems" :key="i" class="cs-item"
                :type="Number(it.delta) >= 0 ? 'warning' : 'danger'" effect="plain"
                @click="pickCsItem(it)">{{ it.text }}{{ it.delta ? ` ${it.delta}` : '' }}</el-tag>
            </div>
          </el-form-item>
          <el-form-item label="事项">
            <el-input v-model="csItemText" :readonly="csPicked" placeholder="如：课室卫生检查不达标 / 全班广播操一等奖" style="max-width: 360px" />
          </el-form-item>
          <el-form-item label="分值">
            <el-input-number v-model="csDelta" :step="0.5" :min="-50" :max="50" :precision="1" controls-position="right" style="width: 120px" :disabled="csPicked" />
            <span v-if="csPicked" class="hint">常见条目为固定分值，不可修改</span>
            <span v-else class="hint">正=加分，负=扣分（±50 以内）</span>
            <el-button v-if="csPicked" link type="primary" style="margin-left: 8px" @click="clearCsPick">重新填写</el-button>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="csNote" placeholder="选填" style="max-width: 360px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="csSaving" :disabled="!csClassId || !csItemText || !csDelta" @click="submitCs">提交记分</el-button>
          </el-form-item>
        </el-form>
      </el-card>
      <el-card v-if="csClassId" style="margin-top: 12px">
        <template #header>该班班级记分记录（新录入在前）</template>
        <el-table :data="csRecords" size="small" max-height="420">
          <el-table-column prop="scoreDate" label="日期" width="110" />
          <el-table-column label="大项" width="130">
            <template #default="{ row }">{{ row.category ? conductSections[row.category - 1]?.name : '—' }}</template>
          </el-table-column>
          <el-table-column prop="itemText" label="事项" min-width="180" />
          <el-table-column prop="delta" label="分值" width="70" />
          <el-table-column prop="operatorName" label="记分人" width="90" />
          <el-table-column prop="note" label="备注" min-width="110" />
          <el-table-column label="操作" width="64">
            <template #default="{ row }">
              <el-button link size="small" type="danger" @click="delCs(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <el-card v-if="mode === 'stu' && studentIds.length">
      <template #header>
        素养评价（一次评价同时写入九维 / 扬长币 / 班年级均值，报告即时可见）
        <el-tag v-if="studentIds.length > 1" type="warning" size="small" style="margin-left: 8px">
          已选 {{ studentIds.length }} 名学生，将为每人生成一条相同评价
        </el-tag>
      </template>
      <el-form label-width="90px">
        <el-form-item label="九维">
          <el-select v-model="gridId" style="width: 180px" @change="loadIndicators">
            <el-option v-for="g in grids" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
          <el-select v-if="indicators.length" v-model="indicatorId" placeholder="二级指标"
            style="width: 200px; margin-left: 8px">
            <el-option v-for="i in indicators" :key="i.id" :label="i.name" :value="i.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="分值">
          <el-radio-group v-model="score">
            <el-radio-button v-for="v in [1, 2, 5, -1, -2]" :key="v" :value="v" :class="v > 0 ? 'is-pos' : 'is-neg'">{{ v > 0 ? '+' + v : v }}</el-radio-button>
          </el-radio-group>
          <el-input-number v-model="score" :step="1" :min="-10" :max="10" :precision="0" controls-position="right" style="width: 120px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="title" placeholder="如：课堂发言精彩 / 作业未完成" style="max-width: 360px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="remark" placeholder="选填" style="max-width: 360px" />
          <VoiceMic class="mic" @text="onVoice" />
        </el-form-item>
        <el-form-item label="评价时间">
          <el-date-picker v-model="evalTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 220px" />
          <span class="hint">默认取当前学期内；学期末日请选 00:00 之前</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" :disabled="!indicatorId || !title" @click="submit">提交评价</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 多选批量时历史区隐藏（记录属单一学生，避免误读） -->
    <el-card v-if="mode === 'stu' && studentIds.length === 1" style="margin-top: 12px">
      <template #header>本学期评价记录（新录入在前）</template>
      <el-table :data="history" size="small" max-height="420">
        <el-table-column prop="evalTime" label="时间" width="160" />
        <el-table-column prop="gridName" label="九维" width="90" />
        <el-table-column prop="indicatorName" label="指标" width="120" />
        <el-table-column prop="title" label="标题" min-width="160" />
        <el-table-column prop="score" label="分值" width="70" />
        <el-table-column prop="teacherName" label="评价人" width="90" />
        <el-table-column prop="remark" label="备注" min-width="120" />
        <!-- 批40e 撤回：评价人/管理员可删（后端硬校验），九维/扬长币/均值联动冲销 -->
        <el-table-column label="操作" width="64">
          <template #default="{ row }">
            <el-button link size="small" type="danger" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { motion } from 'motion-v'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fetchBlob } from '../api/http'
import { saveFile } from '../api/nativeShare'
import VoiceMic from '../components/VoiceMic.vue'
import { CONDUCT_SECTIONS } from '../data/conductRules'

const route = useRoute()

// ───────── 批43：班级整体加减分（进文明班评比，不进学生个人档案） ─────────
const mode = ref<'stu' | 'class'>('stu')
const canClassScore = ref(false)
const conductSections = CONDUCT_SECTIONS
const csClasses = ref<{ id: number; name: string; gradeName: string }[]>([])
const csClassId = ref<number>()
const csCategory = ref<number>()
const csItemText = ref('')
const csDelta = ref(-1)
const csPicked = ref(false)
const csNote = ref('')
const csRecords = ref<any[]>([])
const csSaving = ref(false)
const csDate = ref('')

/** 选大项后的常见条目（德育规范快填；点选即填事项与分值） */
const csCategoryItems = computed(() => {
  if (!csCategory.value) return []
  return (conductSections[csCategory.value - 1]?.groups ?? []).flatMap((g) => g.items).slice(0, 30)
})

function pickCsItem(it: { text: string; delta: string }) {
  csItemText.value = it.text
  const d = Number(it.delta)
  if (it.delta !== '' && !Number.isNaN(d)) csDelta.value = d
  csPicked.value = true
}

/** 常见条目分值固定，锁定后仅可换选条目或点「重新填写」恢复自由录入 */
function onCsCategoryChange() {
  if (csPicked.value) clearCsPick()
}

function clearCsPick() {
  csPicked.value = false
  csItemText.value = ''
  csDelta.value = -1
}

async function loadCsRecords() {
  if (!csClassId.value) { csRecords.value = []; return }
  csRecords.value = await api<any[]>(`/api/civility/class-score/list?classId=${csClassId.value}`).catch(() => [])
}

async function submitCs() {
  if (!csClassId.value || !csItemText.value || !csDelta.value) return
  csSaving.value = true
  try {
    await api('/api/civility/class-score', {
      method: 'POST',
      json: {
        classId: csClassId.value,
        scoreDate: csDate.value,
        category: csCategory.value || undefined,
        itemText: csItemText.value,
        delta: csDelta.value,
        note: csNote.value || undefined,
      },
    })
    ElMessage.success('已记录，文明班排名即时生效')
    csItemText.value = ''
    csPicked.value = false
    csNote.value = ''
    await loadCsRecords()
  } finally {
    csSaving.value = false
  }
}

async function delCs(row: any) {
  await ElMessageBox.confirm(
    `删除班级记分「${row.itemText}」？文明班排名下次汇总自动对齐`, '撤回班级记分',
    { type: 'warning' },
  )
  await api(`/api/civility/class-score/${row.id}`, { method: 'DELETE' })
  ElMessage.success('已删除')
  await loadCsRecords()
}

/** 班级×学期评价导出（漏项C2） */
async function exportXlsx() {
  const blob = await fetchBlob(`/api/evaluation/export?classId=${classId.value}&termId=${termId.value}`)
  const cls = classes.value.find((c: any) => c.id === classId.value)?.name ?? ''
  const tm = terms.value.find((t: any) => t.id === termId.value)?.name ?? ''
  await saveFile(blob, `素养评价_${cls}_${tm}.xlsx`)
}

const classes = ref<{ id: number; name: string }[]>([])
const terms = ref<any[]>([])
const students = ref<{ id: number; name: string }[]>([])
const grids = ref<{ id: number; name: string }[]>([])
const indicators = ref<{ id: number; name: string }[]>([])
const history = ref<any[]>([])
const classId = ref<number>()
const termId = ref<number>()
/** 学生多选（批15：选学生支持批量评价）；单选时保留历史记录视图 */
const studentIds = ref<number[]>([])
const gridId = ref<number>()
const indicatorId = ref<number>()
const score = ref(1)
const title = ref('')
const remark = ref('')

/** 语音输入（批39⑧）：转写文本整段追加到备注末尾 */
function onVoice(t: string) {
  remark.value += t
}
const evalTime = ref('')
const saving = ref(false)

/** 本地时区日期（toISOString 是 UTC：北京时间 8 点前"今天"会落到昨天） */
function localDate(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function defaultEvalTime() {
  // 今天在学期内→今天 12:00；否则当前学期末前一天 12:00（学期末日白天在可写窗口外）
  const cur = terms.value.find((t: any) => t.isCurrent === 1) ?? terms.value[0]
  if (!cur) return ''
  const today = localDate(new Date())
  if (today >= cur.startDate && today < cur.endDate) return today + 'T12:00:00'
  const d = new Date(cur.endDate)
  d.setDate(d.getDate() - 1)
  return localDate(d) + 'T12:00:00'
}

async function init() {
  const [cs, ts, gs] = await Promise.all([
    api<{ id: number; name: string }[]>('/api/meta/my-classes'),
    api<any[]>('/api/meta/terms'),
    api<{ id: number; name: string }[]>('/api/meta/grids'),
  ])
  classes.value = cs
  terms.value = ts
  grids.value = gs
  termId.value = ts.find((t: any) => t.isCurrent === 1)?.id ?? ts[0]?.id
  evalTime.value = defaultEvalTime()
  if (cs.length) {
    classId.value = cs[0].id
    await loadStudents()
  }
  await preselect()
  // 班级记分权限探测：可记分班级非空才显示双轨切换（普通任课教师保持纯学生评价）
  const today = new Date()
  csDate.value = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
  csClasses.value = await api<{ id: number; name: string; gradeName: string }[]>(
    '/api/civility/class-score/classes').catch(() => [])
  canClassScore.value = csClasses.value.length > 0
  if (canClassScore.value) csClassId.value = csClasses.value[0].id
}

/** 学生详情宫格带学生进来：自动选中该生（班级 → 学生 → 拉评价记录） */
async function preselect() {
  const sid = Number(route.query.studentId)
  if (!sid) return
  if (route.query.termId && terms.value.some((t: any) => t.id === Number(route.query.termId))) {
    termId.value = Number(route.query.termId)
  }
  try {
    const s = await api<{ classId?: number }>(`/api/student/${sid}`)
    if (s.classId && classes.value.some((c) => c.id === s.classId)) {
      classId.value = s.classId
      await loadStudents()
      studentIds.value = [sid]
      await loadHistory()
    }
  } catch { /* 深链失效则保持默认视图 */ }
}

let listSeq = 0
async function loadStudents() {
  if (!classId.value) return
  const my = ++listSeq
  const d = await api<{ records: { id: number; name: string }[] }>(
    `/api/student/list?classId=${classId.value}&page=1&size=100`)
  if (my !== listSeq) return // 已切班，丢弃晚到的旧班名单
  students.value = d.records
  studentIds.value = []
  history.value = []
}

async function loadIndicators() {
  indicatorId.value = undefined
  if (!gridId.value) return
  indicators.value = await api<{ id: number; name: string }[]>(`/api/meta/indicators?gridId=${gridId.value}`)
  if (indicators.value.length) indicatorId.value = indicators.value[0].id
}

let histSeq = 0
async function loadHistory() {
  // 历史记录仅单选时展示（=该生的记录）
  if (studentIds.value.length !== 1 || !termId.value) return
  const my = ++histSeq
  history.value = [] // 先清：切换后不残留前一学生的记录
  if (!gridId.value && grids.value.length) {
    gridId.value = grids.value[0].id
    await loadIndicators()
  }
  const list = await api<any[]>(`/api/evaluation/list?studentId=${studentIds.value[0]}&termId=${termId.value}`)
  if (my !== histSeq) return // 已切走，丢弃晚到的旧响应
  history.value = list.slice().reverse()
}

/** 提交：单选=现状；多选=同一评价逐生落库（写穿链逐条独立，银行/操行/微光联动全复用） */
async function submit() {
  if (!studentIds.value.length || !indicatorId.value) return
  saving.value = true
  try {
    let ok = 0, failed = 0, weekNo = 0
    for (const sid of studentIds.value) {
      try {
        const r = await api<{ weekNo: number }>('/api/evaluation', {
          method: 'POST',
          json: {
            studentId: sid,
            indicatorId: indicatorId.value,
            title: title.value,
            score: score.value,
            remark: remark.value || undefined,
            evalTime: evalTime.value,
          },
        })
        ok++
        weekNo = r.weekNo
      } catch { failed++ }
    }
    if (failed) ElMessage.warning(`成功 ${ok} 条，失败 ${failed} 条`)
    else ElMessage.success(`已记录 ${ok} 条（第 ${weekNo} 周），报告单即时生效`)
    title.value = ''
    remark.value = ''
    await loadHistory()
  } finally {
    saving.value = false
  }
}

/** 批40e 撤回：删除单条评价（后端逆向冲销九维/周币/班年级均值/扬长币/操行分联动） */
async function del(row: any) {
  await ElMessageBox.confirm(
    `删除评价「${row.title}」？九维累计、扬长币、班年级均值将同步冲销`, '撤回评价',
    { type: 'warning' },
  )
  await api(`/api/evaluation/${row.id}`, { method: 'DELETE' })
  ElMessage.success('已删除，聚合已冲销')
  await loadHistory()
}

onMounted(init)
</script>

<style scoped>
.mic { margin-left: 8px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; margin-left: 8px; }
/* 批43 班级记分：德育规范常见条目快填 chips */
.cs-items { display: flex; flex-wrap: wrap; gap: 6px; max-width: 560px; }
.cs-item { cursor: pointer; }
/* 加分=成长绿、扣分=警示红（激活态覆写） */
:deep(.el-radio-button.is-pos.is-active .el-radio-button__inner) {
  background: var(--brand-grow-deep); border-color: var(--brand-grow-deep); box-shadow: -1px 0 0 0 var(--brand-grow-deep);
}
:deep(.el-radio-button.is-neg.is-active .el-radio-button__inner) {
  background: var(--el-color-danger); border-color: var(--el-color-danger); box-shadow: -1px 0 0 0 var(--el-color-danger);
}
</style>
