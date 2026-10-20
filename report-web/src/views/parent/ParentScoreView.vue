<template>
  <div class="app-page p-score">
    <van-pull-refresh v-model="refreshing" @refresh="reload" success-text="已刷新">
    <!-- 多孩切换 -->
    <div v-if="children.length > 1" class="kids">
      <button v-for="c in children" :key="c.studentId" class="app-chip kid" type="button"
        :class="{ on: c.studentId === curId }" @click="switchKid(c.studentId)">{{ c.name }}</button>
    </div>

    <div class="app-sec">{{ curName }}的成绩 · {{ exams.length }} 场考试</div>
    <van-skeleton v-if="loading" :row="4" style="padding: 14px" />
    <div v-else-if="!exams.length" class="app-card empty">还没有成绩记录</div>
    <div v-for="(e, i) in exams" :key="e.examId" class="app-card exam" :class="tex(i)">
      <p class="e-title">{{ e.examName }}</p>
      <p class="e-sub">{{ e.termName }}<template v-if="e.examDate"> · {{ e.examDate }}</template></p>
      <div class="s-row" v-for="s in e.subjects" :key="s.subjectName">
        <span class="s-name">{{ s.subjectName }}</span>
        <span class="s-score">{{ s.score ?? '—' }}<small v-if="s.fullScore"> / {{ s.fullScore }}</small></span>
        <van-tag plain :type="lvType(s.level)" class="s-lv">{{ s.level ?? '—' }}</van-tag>
      </div>
    </div>

    <p class="app-foot">{{ note || '等级按满分比例换算' }}</p>
    <p class="app-foot">石实实验学校 · 石实SHINE</p>
    </van-pull-refresh>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../../api/http'
import { texAt } from '../../utils/tex'

/* 批63 家长成绩页：分数+等级（无排名，服务端白名单口径无 rank 字段），按考试分组 */
interface Kid { studentId: number; name: string }
interface SubjectRow { subjectName: string; score?: number; fullScore?: number; level?: string }
interface ExamGroup { examId: number; examName: string; termName?: string; examDate?: string; subjects: SubjectRow[] }

const children = ref<Kid[]>([])
const curId = ref<number>()
const exams = ref<ExamGroup[]>([])
const note = ref('')
const loading = ref(true)
const refreshing = ref(false)

const curName = computed(() => children.value.find((c) => c.studentId === curId.value)?.name ?? '')

function lvType(lv?: string) {
  return lv === 'A' ? 'warning' : lv === 'B' ? 'primary' : lv === 'D' ? 'danger' : 'default'
}

async function loadKid() {
  if (!curId.value) return
  loading.value = true
  try {
    const d = await api<{ note?: string; exams: ExamGroup[] }>(`/api/parent/children/${curId.value}/scores`)
    exams.value = d.exams
    note.value = d.note ?? ''
  } finally { loading.value = false }
}
async function reload() {
  try { await loadKid() } finally { refreshing.value = false }
}
function switchKid(id: number) {
  if (id === curId.value) return
  curId.value = id
  loadKid()
}

// 批52③ 37 槽轮换池：家长页已占 7/11/20/29/31，本页取基准 24
const tex = (i: number) => texAt(24 + i)

onMounted(async () => {
  children.value = await api<Kid[]>('/api/parent/children').catch(() => [])
  if (children.value.length) curId.value = children.value[0].studentId
  await reload()
})
</script>

<style scoped>
.kids { display: flex; gap: 8px; margin: 12px 0 0; }
.kid.on { background: var(--shine-navy); color: #fff; }

.empty { padding: 26px 0; text-align: center; color: var(--app-text-3); font-size: 13px; }

.exam { padding: 12px 14px; margin-top: 10px; }
.e-title { margin: 0; font-size: 14px; font-weight: 600; color: var(--app-text-1); }
.e-sub { margin: 4px 0 2px; font-size: 11px; color: var(--app-text-3); }
.s-row { display: flex; align-items: center; gap: 10px; padding: 9px 0; border-top: 1px solid var(--app-card-border); }
.s-row:first-of-type { border-top: none; }
.s-name { flex: 1; font-size: 13px; color: var(--app-text-1); }
.s-score { font-size: 15px; font-weight: 600; color: var(--app-text-1); font-variant-numeric: tabular-nums; }
.s-score small { font-size: 11px; font-weight: 400; color: var(--app-text-3); }
.s-lv { flex: none; min-width: 22px; text-align: center; }
</style>
