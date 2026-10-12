<template>
  <div class="app-page summary">
    <!-- 筛选卡：班级 / 学生 / 学期 + AI 分析 -->
    <div class="app-card tex-18 filter">
      <van-cell title="班级" is-link :value="curClassName || '选择班级'" @click="clsOpen = true" />
      <van-cell title="学生" is-link :value="curStuLabel || '选择学生'" @click="stuOpen = true" />
      <van-cell title="学期" is-link :value="curTermName || '选择学期'" @click="termOpen = true" />
      <div class="act">
        <van-button round block type="primary" :disabled="!studentId" :loading="!!isRunning"
          loading-text="AI 分析中…" @click="analyze">
          <van-icon name="bulb-o" /> AI 分析该学生
        </van-button>
        <p v-if="isRunning" class="run-tip">{{ cur?.status === '排队' ? '排队中' : '分析中' }}，可切换页面后台继续</p>
        <p v-else-if="!studentId" class="run-tip">先选择学生，再发起 AI 分析</p>
      </div>
    </div>

    <!-- 结果区 -->
    <template v-if="blocks.length || raw">
      <div v-if="raw && !blocks.length" class="app-card result">
        <div class="app-sec" style="margin: 0 0 8px">成长总结</div>
        <div class="text">{{ raw }}</div>
      </div>
      <div v-for="(b, i) in blocks" :key="i" class="app-card tl result" :class="tex(i)">
        <div class="app-sec" style="margin: 0 0 8px">{{ b.title }}</div>
        <div class="text">{{ b.content }}</div>
      </div>
    </template>
    <!-- 空状态（校园元素：淡校徽） -->
    <div v-else-if="!isRunning" class="app-card empty-card">
      <img src="/badge.png" alt="" class="empty-badge">
      <p>{{ studentId ? '点击「AI 分析该学生」开始生成' : '选择学生后点击「AI 分析该学生」' }}</p>
    </div>

    <!-- 班级选择 -->
    <van-popup v-model:show="clsOpen" position="bottom" round>
      <van-picker title="选择班级" :columns="clsColumns" @confirm="onCls" @cancel="clsOpen = false" />
    </van-popup>
    <!-- 学期选择 -->
    <van-popup v-model:show="termOpen" position="bottom" round>
      <van-picker title="选择学期" :columns="termColumns" @confirm="onTerm" @cancel="termOpen = false" />
    </van-popup>
    <!-- 学生选择：搜索 + 列表（行内可见任务进度） -->
    <van-popup v-model:show="stuOpen" position="bottom" round :style="{ height: '68%' }">
      <div class="stu-pop">
        <div class="stu-search">
          <van-icon name="search" />
          <input v-model="stuKeyword" placeholder="搜索学生姓名..." />
        </div>
        <div class="stu-list">
          <div v-for="s in filteredStudents" :key="s.id" class="stu-row" @click="onStu(s)">
            <span class="ava" :style="{ background: avaColor(s.name) }">{{ s.name.charAt(0) }}</span>
            <span class="name">{{ s.name }}</span>
            <span v-if="runningOf(s.id)" class="app-chip run">{{ runningOf(s.id) }}</span>
          </div>
          <div v-if="!filteredStudents.length" class="stu-none">没有匹配的学生</div>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { useAiTasksStore } from '../stores/aiTasks'
import { texAt } from '../utils/tex'

// 批52③ 37 槽轮换池：本页基准 17、静态位 1，结果卡轮换续在 18
const tex = (i: number) => texAt(18 + i)

const store = useAiTasksStore()
const route = useRoute()

const classes = ref<{ id: number; name: string }[]>([])
const terms = ref<{ id: number; name: string; isCurrent?: number }[]>([])
const students = ref<{ id: number; name: string }[]>([])
const classId = ref<number>()
const termId = ref<number>()
const studentId = ref<number>()
const raw = ref('')
const blocks = ref<{ title: string; content: string }[]>([])

const clsOpen = ref(false)
const stuOpen = ref(false)
const termOpen = ref(false)
const stuKeyword = ref('')

const clsColumns = computed(() => classes.value.map((c) => ({ text: c.name, value: c.id })))
const termColumns = computed(() => terms.value.map((t) => ({ text: t.name, value: t.id })))
const curClassName = computed(() => classes.value.find((c) => c.id === classId.value)?.name)
const curTermName = computed(() => terms.value.find((t) => t.id === termId.value)?.name)
const curStu = computed(() => students.value.find((s) => s.id === studentId.value))
const curStuLabel = computed(() => {
  const t = runningOf(studentId.value)
  return t ? `${curStu.value?.name} · ${t}` : curStu.value?.name
})
const filteredStudents = computed(() =>
  students.value.filter((s) => s.name.includes(stuKeyword.value.trim())))

/** 当前学生+学期的总结任务（总结不落库，恢复/完成展示都走任务结果体） */
const cur = computed(() => studentId.value && termId.value
  ? store.byKey.get(`SUMMARY-${studentId.value}-${termId.value}`) : undefined)
const isRunning = computed(() => cur.value && (cur.value.status === '排队' || cur.value.status === '生成中'))

/** 学生任务进度标注（学生弹层行内可见） */
function runningOf(id?: number) {
  if (!id || !termId.value) return ''
  const t = store.byKey.get(`SUMMARY-${id}-${termId.value}`)
  return t && (t.status === '排队' || t.status === '生成中') ? t.status : ''
}

let renderedTaskId = 0 // 已渲染结果的任务：防轮询重复渲染/重复拉详情

// 切换学生/学期先清展示（本 watch 先于下方 cur watch 声明，同 tick 先执行）
watch([studentId, termId], () => {
  raw.value = ''
  blocks.value = []
  renderedTaskId = 0
})

// 任务完成（含切页/重开浏览器回来后）→ 拉结果体渲染
watch(cur, async (t) => {
  if (!t || t.status !== '成功' || renderedTaskId === t.taskId) return
  renderedTaskId = t.taskId
  const d = t.result ? t : await store.fetchDetail(t.taskId)
  const r = d.result as { raw?: string; blocks?: Record<string, string> } | undefined
  if (!r) return
  raw.value = r.raw ?? ''
  blocks.value = Object.entries(r.blocks ?? {})
    .filter(([, v]) => v)
    .map(([k, v]) => ({ title: k, content: v }))
  if (t.source === 'template') {
    showToast('未配置大模型 API，已按模板生成')
  }
}, { immediate: true })

const palette = ['#A8232B', '#7C4DD8', '#0D9467', '#B07A1C', '#D6567A', '#3A7CA5']
function avaColor(name?: string) {
  if (!name) return palette[0]
  let h = 0
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) % 997
  return palette[h % palette.length]
}

/** Vant4 confirm 载荷：{selectedValues, selectedOptions, selectedIndexes}，取值走 selectedOptions */
function onCls(ev: { selectedOptions?: { value: number }[] }) {
  classId.value = ev.selectedOptions?.[0]?.value
  clsOpen.value = false
  loadStudents()
}
function onTerm(ev: { selectedOptions?: { value: number }[] }) {
  termId.value = ev.selectedOptions?.[0]?.value
  termOpen.value = false
}
function onStu(s: { id: number }) {
  studentId.value = s.id
  stuOpen.value = false
}

async function init() {
  const [cs, ts] = await Promise.all([
    api<{ id: number; name: string }[]>('/api/meta/my-classes'),
    api<{ id: number; name: string; isCurrent?: number }[]>('/api/meta/terms'),
  ])
  classes.value = cs
  terms.value = ts
  termId.value = ts.find((t) => t.isCurrent === 1)?.id ?? ts[0]?.id
  if (cs.length) {
    classId.value = cs[0].id
    await loadStudents()
  }
  // 任务面板点进来的预选
  if (route.query.termId) {
    const t = ts.find((x) => x.id === Number(route.query.termId))
    if (t) termId.value = t.id
  }
  if (route.query.studentId && students.value.some((s) => s.id === Number(route.query.studentId))) {
    studentId.value = Number(route.query.studentId)
  }
}

async function loadStudents() {
  if (!classId.value) return
  const d = await api<{ records: { id: number; name: string }[] }>(
    `/api/student/list?classId=${classId.value}&page=1&size=100`)
  students.value = d.records
  studentId.value = undefined
}

async function analyze() {
  if (!studentId.value || !termId.value) return
  await store.submit('SUMMARY', studentId.value, termId.value)
}

onMounted(init)
</script>

<style scoped>
.filter { padding: 4px 0 14px; }
.filter :deep(.van-cell) { padding: 12px 16px; font-size: 15px; }
.filter :deep(.van-cell__value) { color: var(--app-text-2); max-width: 55%; }

.act { padding: 14px 16px 0; }
.act .van-button { height: 44px; font-size: 16px; font-weight: 600; border: none;
  background: linear-gradient(150deg, #8C1D23, #A8232B); }
.act .van-button .van-icon { font-size: 17px; margin-right: 4px; }
.run-tip { margin: 10px 2px 0; font-size: 12px; color: var(--app-text-3); text-align: center; }

.result { margin-top: 12px; }
.text { font-size: 14px; line-height: 1.9; color: var(--app-text-1); white-space: pre-wrap; }

.empty-card { margin-top: 12px; padding: 30px 16px; display: flex; flex-direction: column;
  align-items: center; gap: 10px; }
.empty-badge { width: 44px; opacity: .3; }
.empty-card p { margin: 0; font-size: 13px; color: var(--app-text-3); }

.stu-pop { display: flex; flex-direction: column; height: 100%; padding: 14px 14px calc(10px + var(--sab)); }
.stu-search { display: flex; align-items: center; gap: 8px; padding: 10px 14px; border-radius: 12px;
  background: #F2F4F8; color: var(--app-text-3); }
.stu-search .van-icon { font-size: 15px; }
.stu-search input { flex: 1; border: none; outline: none; background: none; font-size: 14px; color: var(--app-text-1); }
.stu-search input::placeholder { color: var(--app-text-3); }
.stu-list { flex: 1; overflow-y: auto; margin-top: 8px; }
.stu-row { display: flex; align-items: center; gap: 12px; padding: 11px 6px; cursor: pointer; }
.stu-row + .stu-row { border-top: 1px solid var(--app-card-border); }
.stu-row:active { opacity: .7; }
.ava { display: flex; align-items: center; justify-content: center; width: 38px; height: 38px;
  border-radius: 50%; color: #fff; font-size: 15px; font-weight: 600; flex: none; }
.stu-row .name { flex: 1; font-size: 15px; color: var(--app-text-1); }
.stu-row .run { background: rgba(168,35,43,.08); color: var(--app-blue); }
.stu-none { padding: 30px 0; text-align: center; font-size: 13px; color: var(--app-text-3); }
</style>
