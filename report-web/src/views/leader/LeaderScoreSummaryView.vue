<template>
  <div class="app-page l-score">
    <!-- 考试横滑档（同教师使用情况页 ranges 形态） -->
    <div class="ranges">
      <button v-for="e in exams" :key="e.id" class="app-chip range" type="button"
        :class="{ on: e.id === examId }" @click="switchExam(e.id)">
        {{ e.name }}<em v-if="e.entryOpen === false" class="closed">已关闭</em>
      </button>
    </div>

    <van-skeleton v-if="loading" :row="9" style="padding: 14px" />

    <template v-else-if="summary">
      <!-- 模式切换：总分 + 各科（chips 横滑；导出按钮钉行右端，不随 chips 滚出屏） -->
      <div class="mode-bar">
        <div class="ranges modes">
          <button class="app-chip range" type="button" :class="{ on: !subjectId }" @click="switchSubject(undefined as any)">总分</button>
          <button v-for="s in summary.subjects" :key="s.subjectId" class="app-chip range" type="button"
            :class="{ on: s.subjectId === subjectId }" @click="switchSubject(s.subjectId)">{{ s.name }}</button>
        </div>
        <button class="app-chip exp" type="button" @click="exportXlsx">
          <van-icon name="down" /> 导出
        </button>
      </div>

      <!-- 考试信息条 -->
      <div class="app-card meta tex-a">
        <b>{{ summary.exam.name }}</b>
        <span>{{ summary.exam.termName }} · {{ summary.exam.examDate ?? '日期未定' }} · 满分 {{ summary.fullScore ?? '—' }}</span>
        <span>参考 {{ summary.total }} 人 · {{ !subjectId ? '总分' : summary.subjectName }}排名</span>
      </div>

      <!-- 各班统计（均分/最高/参考人数） -->
      <div class="app-sec">各班统计</div>
      <div class="app-card tex-b classes">
        <div v-for="c in summary.classStats" :key="c.classId" class="cls">
          <p class="cn">{{ c.className }}<span>{{ c.count }} 人参考</span></p>
          <div class="cs">
            <div><b>{{ c.avg }}</b><span>平均分</span></div>
            <div><b>{{ c.max ?? '—' }}</b><span>最高分</span></div>
          </div>
        </div>
        <p v-if="!summary.classStats.length" class="empty">暂无成绩数据</p>
      </div>

      <!-- 年级排名（分页） -->
      <div class="app-sec">年级排名 · {{ summary.total }} 人</div>
      <div class="app-card tex-c ranks">
        <div v-for="r in summary.rows" :key="r.studentId" class="rk" :class="{ top: r.gradeRank <= 3 }">
          <i class="no">{{ r.gradeRank }}</i>
          <div class="who">
            <p>{{ r.name }}</p>
            <span>{{ r.className }} · {{ r.studentNo }}</span>
          </div>
          <b class="sc">{{ r.score }}</b>
        </div>
        <p v-if="!summary.rows.length" class="empty">暂无成绩数据</p>
        <van-button v-if="page * pageSize < summary.total" block plain size="small" class="more"
          :loading="moreLoading" @click="loadMore">加载更多（{{ summary.rows.length }}/{{ summary.total }}）</van-button>
      </div>
    </template>

    <div v-else class="app-card empty">暂无考试，请先在「成绩管理」页建考试并录入成绩</div>

    <p class="app-foot">石实实验学校 · 石实SHINE</p>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { showToast } from 'vant'
import { api, fetchBlob } from '../../api/http'
import { saveFile } from '../../api/nativeShare'

interface Summary {
  exam: { id: number; name: string; termName: string; examDate: string | null; entryOpen: boolean }
  mode: string
  subjectName: string | null
  subjects: { subjectId: number; name: string; fullScore: number }[]
  fullScore: number | null
  total: number
  classStats: { classId: number; className: string; count: number; avg: number; max: number | null }[]
  rows: { studentId: number; studentNo: string; name: string; className: string; score: number; gradeRank: number }[]
}

const exams = ref<any[]>([])
const examId = ref<number>()
const subjectId = ref<number>()
const summary = ref<Summary | null>(null)
const loading = ref(true)
const moreLoading = ref(false)
const page = ref(1)
const pageSize = 100

async function loadExams() {
  exams.value = await api<any[]>('/api/score/exam/list')
  if (exams.value.length && !examId.value) {
    examId.value = exams.value[0].id
  }
}

async function load() {
  if (!examId.value) {
    summary.value = null
    return
  }
  loading.value = true
  try {
    const q = `examId=${examId.value}${subjectId.value ? `&subjectId=${subjectId.value}` : ''}&page=${page.value}&size=${pageSize}`
    summary.value = await api<Summary>(`/api/leader/score-summary?${q}`)
  } finally {
    loading.value = false
  }
}

/** 追加下一页（排名表分页加载） */
async function loadMore() {
  moreLoading.value = true
  try {
    page.value++
    const q = `examId=${examId.value}${subjectId.value ? `&subjectId=${subjectId.value}` : ''}&page=${page.value}&size=${pageSize}`
    const d = await api<Summary>(`/api/leader/score-summary?${q}`)
    summary.value!.rows.push(...d.rows)
  } finally {
    moreLoading.value = false
  }
}

function switchExam(id: number) {
  if (id === examId.value) return
  examId.value = id
  subjectId.value = undefined
  page.value = 1
  load()
}

function switchSubject(id: number) {
  if (id === subjectId.value) return
  subjectId.value = id
  page.value = 1
  load()
}

async function exportXlsx() {
  const q = `examId=${examId.value}${subjectId.value ? `&subjectId=${subjectId.value}` : ''}`
  const blob = await fetchBlob(`/api/leader/score-summary/export?${q}`)
  const ex = exams.value.find((e) => e.id === examId.value)?.name ?? ''
  await saveFile(blob, `成绩汇总_${ex}_${subjectId.value ? summary.value?.subjectName : '总分'}.xlsx`)
  showToast('已导出')
}

onMounted(async () => {
  await loadExams()
  await load()
})
</script>

<style scoped>
/* C 风格页面：结构复用第一版全局类（.app-page/.app-card/.app-sec/.app-chip），点缀色 C 令牌 */
.ranges { display: flex; gap: 8px; padding: 14px 2px 0; overflow-x: auto; scrollbar-width: none; }
.ranges::-webkit-scrollbar { display: none; }
.range { flex: none; border: none; cursor: pointer; padding: 5px 14px; }
.range.on { background: var(--shine-red-soft); color: var(--shine-red); font-weight: 600; }
.range em.closed { font-style: normal; font-size: 10px; margin-left: 4px; color: var(--app-text-3); }
.mode-bar { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
.mode-bar .ranges { flex: 1; min-width: 0; padding-top: 0; }
.exp { flex: none; border: none; cursor: pointer; padding: 5px 14px;
  background: var(--shine-navy); color: #fff; border-radius: 999px; font-size: 12px;
  display: flex; align-items: center; gap: 4px; }

.meta { padding: 14px; display: flex; flex-direction: column; gap: 4px; margin-top: 12px; }
.meta b { font-size: 16px; color: var(--app-text-1); }
.meta span { font-size: 12px; color: var(--app-text-3); }

.empty { text-align: center; color: var(--app-text-2); font-size: 13px; padding: 26px 0; margin: 0; }

.classes { padding: 4px 14px; }
.cls { padding: 12px 0; }
.cls + .cls { border-top: 1px solid var(--app-card-border); }
.cn { margin: 0 0 8px; font-size: 14px; font-weight: 600; color: var(--app-text-1);
  display: flex; justify-content: space-between; align-items: baseline; }
.cn span { font-size: 11px; font-weight: 400; color: var(--app-text-3); }
.cs { display: flex; }
.cs > div { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; }
.cs > div + div { border-left: 1px solid var(--app-card-border); }
.cs b { font-size: 18px; color: var(--app-text-1); font-variant-numeric: tabular-nums; }
.cs span { font-size: 11px; color: var(--app-text-3); }

.ranks { padding: 4px 14px; }
.rk { display: flex; align-items: center; gap: 12px; padding: 10px 0; }
.rk + .rk { border-top: 1px solid var(--app-card-border); }
.rk .no { flex: none; width: 28px; height: 28px; border-radius: 50%; font-style: normal;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 700; color: var(--app-text-2); background: var(--app-card-border); }
.rk.top .no { background: var(--shine-gold-soft); color: var(--shine-gold-deep, #8a6d1d); }
.rk .who { flex: 1; min-width: 0; }
.rk .who p { margin: 0; font-size: 14px; font-weight: 600; color: var(--app-text-1); }
.rk .who span { font-size: 11px; color: var(--app-text-3); }
.rk .sc { font-size: 15px; color: var(--app-text-1); font-variant-numeric: tabular-nums; }
.rk.top .sc { color: var(--shine-red); }
.more { margin: 10px 0; }
</style>
