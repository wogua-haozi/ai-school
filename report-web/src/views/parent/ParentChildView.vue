<template>
  <div class="app-page p-child">
    <!-- 孩子信息卡（第一版卡片语言；psub 壳已有返回导航条，页面内不再叠 hero） -->
    <div class="app-card tl tex-05 kid-card">
      <div class="avatar">{{ child?.name?.charAt(0) ?? '?' }}</div>
      <div class="info">
        <h1>{{ child?.name ?? '加载中…' }}<span v-if="child?.relation" class="app-chip rel">{{ child.relation }}</span></h1>
        <p>{{ child?.className ?? '未分班' }}<template v-if="child?.studentNo"> · 学号 {{ child.studentNo }}</template></p>
      </div>
    </div>

    <!-- 成长银行双账本（批3）：操行分 + 扬长币 -->
    <div class="app-sec">成长银行</div>
    <div class="wallet">
      <div class="app-card tex-06 w-cell">
        <p class="w-label">操行分 · <b :class="'g-' + wallet.conduct?.grade">{{ wallet.conduct?.grade ?? '—' }} 级</b></p>
        <strong>{{ wallet.conduct?.balance ?? '—' }}</strong>
        <span class="w-sub">基础 {{ wallet.conduct?.rule?.baseScore }} · A≥{{ wallet.conduct?.rule?.gradeAMin }} B≥{{ wallet.conduct?.rule?.gradeBMin }}</span>
        <div v-if="(wallet.conduct?.logs ?? []).length" class="w-logs">
          <p v-for="l in wallet.conduct.logs" :key="l.id">
            <i>{{ l.sourceType }} · {{ fmtTime(l.createTime) }}</i>
            <b>{{ l.reason }}</b>
            <em :class="Number(l.delta) >= 0 ? 'pos' : 'neg'">{{ Number(l.delta) >= 0 ? '+' : '' }}{{ l.delta }}</em>
          </p>
        </div>
      </div>
      <div class="app-card tex-07 w-cell">
        <p class="w-label">扬长币 · 可用</p>
        <strong class="c-gold">{{ wallet.coin?.currentCoin ?? 0 }}</strong>
        <span class="w-sub">累计获得 {{ wallet.coin?.totalCoin ?? 0 }}</span>
        <div v-if="(wallet.coin?.expenses ?? []).length" class="w-logs">
          <p v-for="e in wallet.coin.expenses" :key="e.id">
            <i>兑换 · {{ fmtTime(e.createTime) }}</i>
            <b>{{ e.item }}</b>
            <em class="neg">-{{ e.coin }}</em>
          </p>
        </div>
      </div>
    </div>

    <!-- 成长档案入口（批14 生命周期）：在校全期记录 -->
    <div class="app-card tex-08 archive-link" @click="goArchive">
      <van-icon name="records" class="a-icon" />
      <div class="a-text">
        <b>成长档案</b>
        <span>入学以来全部评价 · 活动 · 荣誉 · 微光</span>
      </div>
      <van-icon name="arrow" class="a-arrow" />
    </div>

    <!-- 成长动态（feed 结构同教师端最近动态） -->
    <div class="app-sec">成长动态</div>
    <div class="app-card tex-09 feed">
      <van-skeleton v-if="loading" :row="6" class="feed-skeleton" />
      <div v-else-if="!evaluations.length" class="feed-empty">还没有老师评价记录</div>
      <article v-for="(e, i) in evaluations" :key="i" class="eval">
        <div class="f-line1">
          <span class="f-title">{{ e.title }}</span>
          <b class="f-score">{{ e.score }}</b>
        </div>
        <p class="f-meta">{{ e.teacherName ?? '老师' }} · {{ fmtTime(e.evalTime) }}</p>
        <p v-if="e.remark" class="f-remark">{{ e.remark }}</p>
      </article>
    </div>

    <p class="app-foot">学期成长报告请在首页「成长报告」中查看</p>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../../api/http'

const route = useRoute()
const router = useRouter()
const child = ref<any>(null)
const evaluations = ref<any[]>([])
const wallet = ref<any>({})
const loading = ref(true)

function fmtTime(t?: string) {
  if (!t) return ''
  return t.slice(0, 16).replace('T', ' ')
}

function goArchive() {
  router.push(`/p/archive/${route.params.id}`)
}

onMounted(async () => {
  const id = route.params.id
  try {
    // children 接口只回绑定孩子（后端 requireBound 双保险）
    const list = await api<any[]>('/api/parent/children')
    child.value = list.find((c) => String(c.studentId) === String(id)) ?? null
    evaluations.value = await api<any[]>(`/api/parent/children/${id}/evaluations?limit=20`)
    api<any>(`/api/parent/children/${id}/wallet`).then((d) => (wallet.value = d)).catch(() => {})
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* C 风格页面（方案C 新中式）：结构复用第一版全局类，仅覆盖点缀色为 C 令牌 */
.kid-card { display: flex; align-items: center; gap: 14px; margin-top: 14px; }
.avatar { flex: none; width: 54px; height: 54px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: var(--shine-navy); color: var(--shine-gold); font-size: 22px; font-weight: 700; }
.info h1 { margin: 0; font-size: 18px; font-weight: 700; color: var(--app-text-1); }
.info .rel { margin-left: 8px; background: var(--shine-red-soft); color: var(--shine-red); }
.info p { margin: 5px 0 0; font-size: 12px; color: var(--app-text-2); }

.feed { padding: 4px 14px; }
.feed-skeleton { padding: 14px 0; }
.feed-empty { padding: 26px 0; text-align: center; color: var(--app-text-3); font-size: 13px; }
.eval { padding: 12px 0; }
.eval + .eval { border-top: 1px solid var(--app-card-border); }
.f-line1 { display: flex; align-items: baseline; gap: 8px; }
.f-title { flex: 1; font-size: 14px; font-weight: 600; color: var(--app-text-1);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.f-score { font-size: 13px; color: var(--shine-red); }
.f-meta { margin: 5px 0 0; font-size: 11px; color: var(--app-text-3); }
.f-remark { margin: 6px 0 0; font-size: 13px; line-height: 1.5; color: var(--app-text-2);
  background: var(--shine-bg); border-radius: 8px; padding: 8px 10px; }

/* C 覆盖：区块竖条换 C 红（卡片/结构均第一版全局类） */
.p-child .app-sec::before { background: var(--shine-red); }

/* 成长档案入口条（批14）：横向点击卡 */
.archive-link { margin-top: 12px; padding: 14px 16px; display: flex; align-items: center; gap: 12px; cursor: pointer; }
.a-icon { flex: none; font-size: 22px; color: var(--shine-navy); }
.a-text { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.a-text b { font-size: 14px; color: var(--app-text-1); }
.a-text span { font-size: 11px; color: var(--app-text-3); }
.a-arrow { flex: none; color: var(--app-text-3); }

/* 双账本卡（批3）：左操行右扬长币，窄于 480px 纵排 */
.wallet { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
@media (max-width: 480px) { .wallet { grid-template-columns: 1fr; } }
.w-cell { padding: 14px 16px; display: flex; flex-direction: column; gap: 4px; }
.w-label { margin: 0; font-size: 12px; color: var(--app-text-3); }
.g-A { color: #0D9467; } .g-B { color: #3A4664; } .g-C { color: #B07A1C; } .g-D { color: var(--shine-red); }
.w-cell strong { font-size: 28px; font-weight: 800; color: var(--app-text-1); line-height: 1.15; }
.c-gold { color: #B07A1C; }
.w-sub { font-size: 11px; color: var(--app-text-3); }
.w-logs { margin-top: 8px; border-top: 1px dashed var(--app-card-border); padding-top: 6px; }
.w-logs p { display: flex; align-items: center; gap: 6px; margin: 0; padding: 4px 0; font-size: 11px; }
.w-logs i { flex: none; font-style: normal; color: var(--app-text-3); }
.w-logs b { flex: 1; min-width: 0; font-weight: 500; color: var(--app-text-2);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.w-logs em { flex: none; font-style: normal; font-weight: 700; }
.w-logs em.pos { color: #0D9467; }
.w-logs em.neg { color: var(--shine-red); }
</style>
