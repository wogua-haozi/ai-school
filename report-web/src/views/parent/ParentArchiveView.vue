<template>
  <div class="app-page">
    <!-- 孩子概要（批14 生命周期档案：在校全期沉淀） -->
    <div class="app-card tl tex-02 kid-card">
      <div class="avatar">{{ full?.student.name?.charAt(0) ?? '?' }}</div>
      <div class="info">
        <h1>成长档案</h1>
        <p>{{ full?.student.name ?? '加载中…' }} · {{ full.student.className || '未分班' }}<template v-if="full.student.studentNo"> · 学号 {{ full.student.studentNo }}</template></p>
        <p v-if="full.student.enrollDate">入学 {{ full.student.enrollDate }} 起，在校全部成长记录</p>
      </div>
    </div>

    <!-- 总览统计：五格 -->
    <div class="app-sec">在校总览</div>
    <div class="stats">
      <div v-for="(s, i) in statCells" :key="s.label" class="app-card s-cell" :class="[s.cls, tex(i)]">
        <strong>{{ s.value }}</strong>
        <span>{{ s.label }}</span>
      </div>
    </div>

    <!-- 全期事件流 -->
    <div class="app-sec">成长大事记</div>
    <div class="app-card list">
      <van-skeleton v-if="loading" :row="6" />
      <div v-else-if="!events.length" class="empty">在校期间暂无记录</div>
      <article v-for="(e, i) in events" :key="i" class="ev">
        <div class="l1">
          <i class="tag" :class="'t-' + typeKey(e.type)">{{ e.type }}</i>
          <b>{{ e.title }}</b>
        </div>
        <p class="time">{{ e.time }}</p>
        <p v-if="e.detail" class="detail">{{ e.detail }}</p>
      </article>
      <p v-if="full.truncated" class="more">记录较多，仅显示最近 500 条</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../../api/http'
import { texAt } from '../../utils/tex'

// 批52③ 37 槽轮换池：本页基准 10、静态位 1，统计卡轮换续在 11
const tex = (i: number) => texAt(11 + i)

const route = useRoute()

interface Event { type: string; time: string; title: string; detail: string }
const loading = ref(true)
const events = ref<Event[]>([])
const full = ref<any>({ student: {} })

const statCells = computed(() => {
  const s = full.value.stats ?? {}
  return [
    { label: '评价', value: s.evaluations ?? 0, cls: 'c1' },
    { label: '活动', value: s.activities ?? 0, cls: 'c2' },
    { label: '荣誉', value: s.honors ?? 0, cls: 'c3' },
    { label: '微光', value: s.moments ?? 0, cls: 'c4' },
    { label: '报告', value: s.reports ?? 0, cls: 'c5' },
  ]
})

/** 类型→样式键（与 tag 配色表对应） */
function typeKey(t: string) {
  return ({ 评价: 'ev', 活动: 'ac', 荣誉: 'ho', 微光: 'mo', 成绩: 'sc' } as Record<string, string>)[t] || 'ev'
}

onMounted(async () => {
  try {
    const d = await api<any>(`/api/timeline/${route.params.id}/lifecycle`)
    full.value = d
    events.value = d.events ?? []
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.kid-card { display: flex; align-items: center; gap: 14px; margin-top: 14px; }
.avatar { flex: none; width: 54px; height: 54px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: var(--shine-navy); color: var(--shine-gold); font-size: 22px; font-weight: 700; }
.info h1 { margin: 0; font-size: 18px; font-weight: 700; color: var(--app-text-1); }
.info p { margin: 4px 0 0; font-size: 12px; color: var(--app-text-2); }

.app-page .app-sec::before { background: var(--shine-red); }

.stats { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px; }
@media (max-width: 420px) { .stats { grid-template-columns: repeat(3, 1fr); } }
.s-cell { padding: 12px 6px; display: flex; flex-direction: column; align-items: center; gap: 3px; }
.s-cell strong { font-size: 24px; font-weight: 800; line-height: 1.1; }
.s-cell span { font-size: 11px; color: var(--app-text-3); }
.c1 strong { color: #3A5B8F; } .c2 strong { color: #B4641E; } .c3 strong { color: #0D9467; }
.c4 strong { color: #B07A1C; } .c5 strong { color: var(--shine-navy); }

.list { padding: 4px 14px; }
.empty { padding: 26px 0; text-align: center; color: var(--app-text-3); font-size: 13px; }
.ev { padding: 12px 0; }
.ev + .ev { border-top: 1px solid var(--app-card-border); }
.l1 { display: flex; align-items: center; gap: 8px; }
.l1 b { font-size: 14px; color: var(--app-text-1); }
.tag { flex: none; font-style: normal; font-size: 10px; line-height: 1; padding: 4px 7px; border-radius: 4px; color: #fff; }
.t-ev { background: #3A5B8F; } .t-ac { background: #B4641E; } .t-ho { background: #0D9467; }
.t-mo { background: #B07A1C; } .t-sc { background: var(--shine-navy); }
.time { margin: 5px 0 0; font-size: 11px; color: var(--app-text-3); }
.detail { margin: 6px 0 0; font-size: 13px; line-height: 1.5; color: var(--app-text-2);
  background: var(--shine-bg); border-radius: 8px; padding: 8px 10px; }
.more { margin: 10px 0 6px; text-align: center; font-size: 11px; color: var(--app-text-3); }
</style>
