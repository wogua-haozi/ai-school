<template>
  <div>
    <h4>值日排班（当日值日老师可记「班级记分」任意班；管理员/领导不受限。素养评价已放开为全体教职工）</h4>
    <div class="bar">
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" range-separator="至"
        start-placeholder="开始日" end-placeholder="结束日" style="width: 260px" :clearable="false" />
      <el-select v-model="teacherIds" multiple filterable collapse-tags collapse-tags-tooltip
        placeholder="选择教师（可多选）" style="min-width: 220px; flex: 1; max-width: 360px">
        <el-option v-for="t in teachers" :key="t.id" :label="t.realName" :value="t.id" />
      </el-select>
      <el-input v-model="note" placeholder="备注（选填，全程同一备注）" style="width: 190px" />
      <el-button type="primary" :disabled="!teacherIds.length" @click="add">批量加入值班</el-button>
    </div>
    <div class="bar">
      <span class="lbl">按日查看</span>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width: 150px" @change="load" />
    </div>
    <el-table :data="rows" size="small">
      <el-table-column prop="dutyDate" label="值班日" width="120" />
      <el-table-column prop="teacherName" label="教师" min-width="120" />
      <el-table-column prop="note" label="备注" min-width="160">
        <template #default="{ row }">{{ row.note ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="danger" size="small" @click="remove(row)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <p class="hint" style="margin-top: 10px">
      口径=操作发生日：教师在当天值日即可记班级记分（记分日期可补录近 31 天，权限按当天是否值日判定）。
    </p>

    <!-- 批66① 按周模板生成：整学期排班一键排，每学期重排不用逐日手工 -->
    <el-divider content-position="left">按周模板生成（整学期一键排）</el-divider>
    <div class="bar">
      <span class="lbl">日期范围</span>
      <el-date-picker v-model="genRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"
        start-placeholder="开始日" end-placeholder="结束日" style="width: 260px" :clearable="false" />
      <el-checkbox v-model="evenWeek">区分单双周（两套模板，如初三轮换）</el-checkbox>
      <el-button type="primary" :disabled="!genReady" @click="generate">按模板生成</el-button>
    </div>
    <div v-for="(d, i) in DAYS" :key="d.key" class="bar tpl-row">
      <span class="lbl" style="width: 34px">{{ d.label }}</span>
      <el-select v-model="tplOdd[i]" multiple filterable collapse-tags collapse-tags-tooltip
        :placeholder="evenWeek ? '单周安排' : '当天安排'" style="min-width: 220px; flex: 1; max-width: 560px">
        <el-option v-for="t in teachers" :key="t.id" :label="t.realName" :value="t.id" />
      </el-select>
      <el-select v-if="evenWeek" v-model="tplEven[i]" multiple filterable collapse-tags collapse-tags-tooltip
        placeholder="双周安排" style="min-width: 220px; flex: 1; max-width: 560px">
        <el-option v-for="t in teachers" :key="t.id" :label="t.realName" :value="t.id" />
      </el-select>
    </div>
    <p class="hint" style="margin-top: 6px">
      教学周以当前学期开学日所在周为第 1 周，单周=第 1/3/5…周。生成会先清掉日期范围内所选教师的旧排班再写入
      （当天没人可留空，如周六）；其他教师手工排的行不受影响。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api/http'

// 本地时区的今天（toISOString 是 UTC，早 8 点前"今天"会落到昨天）
const today = new Date()
const todayStr = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
const date = ref(todayStr)
/** 排班日期范围，默认今天~今天（选一周即一次排 7 天） */
const range = ref<[string, string]>([todayStr, todayStr])
const teacherIds = ref<number[]>([])
const note = ref('')
const teachers = ref<any[]>([])
const rows = ref<any[]>([])

/** 批66① 周模板：周一~周日（中文习惯顺序）；tplOdd=单周（不区分时即每周），tplEven=双周 */
const DAYS = [
  { key: 'MONDAY', label: '周一' }, { key: 'TUESDAY', label: '周二' }, { key: 'WEDNESDAY', label: '周三' },
  { key: 'THURSDAY', label: '周四' }, { key: 'FRIDAY', label: '周五' }, { key: 'SATURDAY', label: '周六' },
  { key: 'SUNDAY', label: '周日' },
]
const tplOdd = ref<number[][]>(DAYS.map(() => []))
const tplEven = ref<number[][]>(DAYS.map(() => []))
const evenWeek = ref(false)
/** 生成范围默认=当前学期起止 */
const genRange = ref<[string, string]>([todayStr, todayStr])

const genReady = computed(() =>
  !!genRange.value?.[0] && tplOdd.value.some((ids) => ids.length > 0))

async function load() {
  rows.value = await api<any[]>(`/api/admin/duty/list?date=${date.value}`)
}

async function add() {
  if (!teacherIds.value.length || !range.value?.[0]) return
  const r = await api<{ added: number; skipped: number }>('/api/admin/duty', {
    method: 'POST',
    json: { dutyDate: range.value[0], dateEnd: range.value[1], teacherIds: teacherIds.value, note: note.value || null },
  })
  ElMessage.success(r.skipped ? `已排 ${r.added} 人日，跳过已排 ${r.skipped} 人日` : `已排 ${r.added} 人日`)
  note.value = ''
  date.value = range.value[0]
  await load()
}

async function remove(row: any) {
  await api(`/api/admin/duty/${row.id}`, { method: 'DELETE' })
  ElMessage.success('已移除')
  await load()
}

function buildTemplate(tpl: number[][]) {
  const out: Record<string, number[]> = {}
  DAYS.forEach((d, i) => {
    if (tpl[i]?.length) out[d.key] = [...tpl[i]]
  })
  return out
}

async function generate() {
  const weekly = buildTemplate(tplOdd.value)
  const weeklyEven = evenWeek.value ? buildTemplate(tplEven.value) : undefined
  await ElMessageBox.confirm(
    `将按模板生成 ${genRange.value[0]} ~ ${genRange.value[1]} 的排班，并先清掉该范围内所选教师的旧排班，确定？`,
    '按周模板生成', { type: 'warning' },
  )
  const r = await api<{ added: number; deleted: number }>('/api/admin/duty/generate', {
    method: 'POST',
    json: { fromDate: genRange.value[0], toDate: genRange.value[1], weekly, weeklyEven },
  })
  ElMessage.success(`已生成 ${r.added} 人日（清除旧排班 ${r.deleted} 人日）`)
  await load()
}

onMounted(async () => {
  // 全量教职员下拉（/api/meta/teachers 不分页，管理员按姓名查找；旧源 user/list 只取前 200 会漏人）
  teachers.value = await api<any[]>('/api/meta/teachers')
  const terms = await api<any[]>('/api/admin/term/list')
  const cur = terms.find((t) => t.isCurrent === 1) ?? terms[terms.length - 1]
  if (cur?.startDate && cur?.endDate) {
    genRange.value = [cur.startDate, cur.endDate]
  }
  await load()
})
</script>

<style scoped>
.bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 10px 0; }
.lbl { font-size: 13px; color: #606266; }
.tpl-row { margin: 4px 0; }
</style>
