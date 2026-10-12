<template>
  <div class="app-page notice">
    <div class="app-hero hero mark">
      <h1>通知</h1>
      <p>AI 生成任务与报告批量进度</p>
    </div>

    <!-- 进行中 -->
    <div v-if="running.length" class="app-card overlap tl green tex-37 group">
      <div class="app-sec" style="margin: 0 0 4px">进行中<span class="cnt">{{ running.length }}</span></div>
      <div v-for="t in running" :key="t.taskId" class="task" @click="goAi(t)">
        <span class="t-icon spin"><van-icon name="replay" /></span>
        <div class="t-body">
          <p class="t-title">{{ t.studentName || `学生${t.studentId}` }} · {{ t.taskType === 'COMMENT' ? '寄语草稿' : '成长总结' }}</p>
          <p class="t-sub">{{ t.status === '排队' ? '排队中…' : 'AI 生成中…' }}</p>
        </div>
      </div>
    </div>

    <!-- AI 任务：最近完成 -->
    <div class="app-card tex-01 group" :class="{ overlap: !running.length }">
      <div class="app-sec" style="margin: 0 0 4px">AI 任务<span class="cnt">{{ done.length }}</span></div>
      <div v-if="!done.length" class="empty">还没有任务，去寄语/成长总结页发起</div>
      <div v-for="t in done" :key="t.taskId" class="task" @click="goAi(t)">
        <span class="t-icon" :class="t.status === '成功' ? 'ok' : 'fail'">
          <van-icon :name="t.status === '成功' ? 'checked' : 'warning-o'" />
        </span>
        <div class="t-body">
          <p class="t-title">{{ t.studentName || `学生${t.studentId}` }} · {{ t.taskType === 'COMMENT' ? '寄语草稿' : '成长总结' }}</p>
          <p class="t-sub">
            {{ t.status === '成功' ? (t.source === 'llm' ? '大模型生成完成' : '模板生成完成') : `失败：${t.error || '未知原因'}` }}
          </p>
        </div>
        <span class="t-time">{{ relTime(t.createTime) }}</span>
      </div>
    </div>

    <!-- 报告批量任务（原批量任务页并入） -->
    <div class="app-card tex-02 group">
      <div class="app-sec" style="margin: 0 0 4px">报告批量任务<span class="cnt">{{ batchTasks.length }}</span>
        <span class="more" @click="$router.push('/reports')">发起生成 ›</span>
      </div>
      <div v-if="!batchTasks.length" class="empty">还没有批量任务，去报告列表页发起</div>
      <div v-for="t in batchTasks" :key="t.taskId" class="task batch" @click="selectBatch(t.taskId)">
        <div class="t-body">
          <p class="t-title">任务 #{{ t.taskId }} · {{ t.status }}</p>
          <van-progress v-if="t.taskId === curBatchId" :percentage="pct(t)" :show-pivot="true"
            :color="t.status === '失败' || t.status === '部分失败' ? '#EF4444' : '#A8232B'" />
          <p class="t-sub" v-if="t.taskId === curBatchId">总数 {{ t.total }} · 完成 {{ t.done }} · 失败 {{ t.failed }}</p>
          <p class="t-sub" v-else>总数 {{ t.total }} · 完成 {{ t.done }}</p>
        </div>
        <van-button v-if="t.taskId === curBatchId && (t.status === '部分失败' || t.status === '失败')"
          size="small" round type="warning" plain @click.stop="retry">重试失败项</van-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showSuccessToast } from 'vant'
import { api } from '../api/http'
import { useAiTasksStore, type AiTask } from '../stores/aiTasks'
import { relTime } from '../utils/fmt'

const store = useAiTasksStore()
const router = useRouter()

const running = computed(() => store.running)
const done = computed(() => store.tasks.filter((t) => t.status === '成功' || t.status === '失败').slice(0, 20))

function goAi(t: AiTask) {
  router.push({
    path: t.taskType === 'COMMENT' ? '/comments' : '/summary',
    query: { studentId: String(t.studentId), termId: String(t.termId), taskId: String(t.taskId) },
  })
}

/* 报告批量任务（原 TasksView 逻辑并入，列表 + 选中项进度轮询） */
interface TaskView { taskId: number; termId: number; scope: string; targetId: number; status: string; total: number; done: number; failed: number }
const batchTasks = ref<TaskView[]>([])
const curBatchId = ref<number>()
let timer: number | undefined

function pct(t: TaskView) {
  return t.total ? Math.round((t.done / t.total) * 100) : 0
}

async function loadBatch() {
  batchTasks.value = await api<TaskView[]>('/api/report/task/list?limit=20')
  if (!batchTasks.value.length) curBatchId.value = undefined
  else if (!curBatchId.value || !batchTasks.value.some((t) => t.taskId === curBatchId.value)) {
    curBatchId.value = batchTasks.value[0].taskId
  }
  if (curBatchId.value) {
    const d = await api<TaskView>(`/api/report/task/${curBatchId.value}`)
    const cur = batchTasks.value.find((t) => t.taskId === curBatchId.value)
    if (cur) Object.assign(cur, d)
  }
}

function selectBatch(id: number) {
  curBatchId.value = id
}

async function retry() {
  if (!curBatchId.value) return
  await api(`/api/report/task/${curBatchId.value}/retry`, { method: 'POST' })
  showSuccessToast('失败项已重新入队')
  loadBatch()
}

onMounted(() => {
  loadBatch()
  timer = window.setInterval(loadBatch, 3000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<style scoped>
.hero { padding-bottom: 18px; }
.hero h1 { margin: 4px 0 2px; font-size: 21px; font-weight: 800; }
.hero p { margin: 0; font-size: 12px; color: rgba(255,255,255,.65); }

.group { margin-top: 12px; padding: 14px 14px 8px; }
.group.overlap { margin-top: -36px; }
.cnt { margin-left: 6px; padding: 0 8px; border-radius: 999px; background: var(--app-chip-bg);
  color: var(--app-chip-text); font-size: 11px; font-weight: 600; }

.task { display: flex; align-items: center; gap: 12px; padding: 11px 0; cursor: pointer; }
.task + .task { border-top: 1px solid var(--app-card-border); }
.task:active { opacity: .75; }
.t-icon { display: flex; align-items: center; justify-content: center; width: 36px; height: 36px;
  border-radius: 50%; flex: none; background: rgba(168,35,43,.08); color: var(--app-blue); }
.t-icon.ok { background: #E8F6EF; color: #0D9467; }
.t-icon.fail { background: #FDECEC; color: #EF4444; }
.t-icon.spin .van-icon { animation: spin 1.2s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.t-icon .van-icon { font-size: 18px; }
.t-body { flex: 1; min-width: 0; }
.t-title { margin: 0; font-size: 14px; font-weight: 600; color: var(--app-text-1); }
.t-sub { margin: 3px 0 0; font-size: 12px; color: var(--app-text-3);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.t-time { flex: none; font-size: 11px; color: var(--app-text-3); }
.empty { padding: 22px 0; text-align: center; font-size: 13px; color: var(--app-text-3); }
.task.batch { align-items: center; }
</style>
