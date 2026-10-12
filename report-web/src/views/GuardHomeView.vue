<template>
  <div class="app-page guard">
    <!-- 门卫单页：核验当日有效请假 + 离校/返校登记（GUARD 角色仅此一个功能） -->
    <div class="app-hero hero">
      <img class="hero-photo" :src="asset.url('campus-bg', '/campus-bg.jpg')" alt="石实实验学校">
      <div class="hero-top">
        <div class="hello">
          <p class="hi">{{ greeting }}，{{ auth.realName }}</p>
          <h1>请假核验</h1>
          <p class="sub">{{ todayLabel }} · 有效请假 {{ rows.length }} 单</p>
        </div>
        <div class="hero-actions">
          <button class="hero-btn" type="button" @click="$router.push('/change-password')" aria-label="修改密码">
            <van-icon name="lock" />
          </button>
        </div>
      </div>
    </div>

    <!-- 查询条件 -->
    <div class="app-card overlap tl tex-22 query">
      <van-field v-model="q" label="搜索" placeholder="学号 / 姓名 / 班级" class="q-in" @update:model-value="debouncedLoad" />
      <div class="date-row" @click="dateOpen = true">
        <span>核验日期</span><b>{{ date }}</b><van-icon name="arrow" />
      </div>
    </div>

    <!-- 有效请假列表 -->
    <div class="app-sec">当日有效请假单（已批准）</div>
    <div class="app-card tex-23 list">
      <van-skeleton v-if="!loaded" :row="6" />
      <div v-else-if="!rows.length" class="empty">该日期没有有效请假单</div>
      <div v-for="r in rows" :key="r.id" class="row">
        <div class="r-body" @click="openDetail(r)">
          <p class="r-title"><b>{{ r.studentName }}</b><span class="cls">{{ r.className }} · {{ r.studentNo }}</span></p>
          <p class="r-meta">{{ r.leaveType }} · {{ r.startTime }} ~ {{ r.endTime }}</p>
          <p class="r-sub">
            <template v-if="r.returnTime">离校 {{ fmtTime(r.leaveTime) }} · 已返校 {{ fmtTime(r.returnTime) }}</template>
            <template v-else-if="r.leaveTime">已离校 {{ fmtTime(r.leaveTime) }}，返校后请登记</template>
            <template v-else>未离校（在宿舍/校内）</template>
          </p>
        </div>
        <div class="r-side">
          <button v-if="!r.leaveTime" class="btn leave" type="button" @click="ask(r, 'leave')">登记离校</button>
          <button v-else-if="!r.returnTime" class="btn back" type="button" @click="ask(r, 'return')">登记返校</button>
          <span v-else class="done">已闭环</span>
        </div>
      </div>
    </div>

    <p class="g-foot">
      <button type="button" class="out" @click="logoutOpen = true">退出登录</button>
    </p>

    <!-- 日期切换 -->
    <van-popup v-model:show="dateOpen" position="bottom" round>
      <van-date-picker title="核验日期" v-model="dateBuf" :columns-type="['year', 'month', 'day']"
        :min-date="minDate" :max-date="maxDate" @confirm="onDate" @cancel="dateOpen = false" />
    </van-popup>

    <!-- 详情弹层（核验信息+凭证照片） -->
    <van-popup v-model:show="detailOpen" position="bottom" round :style="{ maxHeight: '82%' }" class="pop">
      <template v-if="detail">
        <div class="p-head">
          <b>{{ detail.studentName }} · {{ detail.leaveType }}</b>
          <small>{{ detail.className }} · 学号 {{ detail.studentNo }}</small>
        </div>
        <div class="p-body">
          <div class="detail-cells">
            <p><span>请假时间</span><b>{{ detail.startTime }} ~ {{ detail.endTime }}</b></p>
            <p><span>审批老师</span><b>{{ detail.approverName || '—' }}</b></p>
            <p v-if="detail.approveNote"><span>审批意见</span><b>{{ detail.approveNote }}</b></p>
            <p v-if="detail.leaveTime"><span>离校登记</span><b>{{ fmtTime(detail.leaveTime) }}</b></p>
            <p v-if="detail.returnTime"><span>返校登记</span><b>{{ fmtTime(detail.returnTime) }}</b></p>
          </div>
          <div class="app-sec" style="margin: 14px 0 4px">请假事由</div>
          <p class="desc">{{ detail.reason }}</p>
          <template v-if="detailUrls.length">
            <div class="app-sec" style="margin: 14px 0 4px">凭证照片（{{ detailUrls.length }}）</div>
            <div class="big-photos"><img v-for="(u, i) in detailUrls" :key="i" :src="u" alt="凭证" /></div>
          </template>
        </div>
      </template>
    </van-popup>

    <van-dialog v-model:show="logoutOpen" title="退出登录" message="确定退出当前账号吗？" show-cancel-button @confirm="logout" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showSuccessToast } from 'vant'
import { api, fetchBlob } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { useAssetStore } from '../stores/asset'

interface LeaveRow {
  id: number; studentId: number; studentName: string; studentNo?: string; className?: string
  leaveType: string; startTime: string; endTime: string; reason: string; status: string
  approverName: string; approveNote: string
  leaveTime?: string; returnTime?: string; createTime: string
  photoCount: number; photoUrls: string[]
}

const auth = useAuthStore()
const asset = useAssetStore()
const router = useRouter()
const today = new Date()
const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const date = ref(iso(today))
const dateOpen = ref(false)
const dateBuf = ref<string[]>(date.value.split('-'))
const minDate = new Date(today.getFullYear() - 1, 0, 1)
const maxDate = new Date(today.getFullYear() + 1, 11, 31)
const q = ref('')
const rows = ref<LeaveRow[]>([])
const loaded = ref(false)
const detail = ref<LeaveRow | null>(null)
const detailOpen = ref(false)
const detailUrls = ref<string[]>([])
const logoutOpen = ref(false)

const greeting = computed(() => {
  const h = today.getHours()
  return h < 12 ? '上午好' : h < 18 ? '下午好' : '晚上好'
})
const todayLabel = computed(() => date.value === iso(today) ? '今天' : date.value)

let timer: ReturnType<typeof setTimeout> | undefined
function debouncedLoad() {
  clearTimeout(timer)
  timer = setTimeout(load, 350)
}

async function load() {
  const qs = new URLSearchParams({ date: date.value })
  if (q.value.trim()) qs.set('q', q.value.trim())
  rows.value = await api<LeaveRow[]>(`/api/student-leave/guard?${qs}`)
  loaded.value = true
}

function onDate({ selectedValues }: { selectedValues: string[] }) {
  date.value = selectedValues.join('-')
  dateOpen.value = false
  loaded.value = false
  load()
}

function ask(r: LeaveRow, act: 'leave' | 'return') {
  const verb = act === 'leave' ? '离校' : '返校'
  showConfirmDialog({
    title: `登记${verb}`,
    message: `确认登记 ${r.studentName}（${r.className}）${verb}？时间为当前时刻。`,
  }).then(async () => {
    await api(`/api/student-leave/${r.id}/${act}`, { method: 'PUT' })
    showSuccessToast(`已登记${verb}`)
    load()
  }).catch(() => { /* 取消 */ })
}

async function openDetail(r: LeaveRow) {
  detail.value = await api<LeaveRow>(`/api/student-leave/${r.id}`)
  detailUrls.value = []
  detailOpen.value = true
  for (const u of detail.value.photoUrls) {
    try { detailUrls.value.push(URL.createObjectURL(await fetchBlob(u))) } catch { /* 单张缺失不阻塞 */ }
  }
}

function logout() {
  auth.logout()
  router.push('/login')
}

function fmtTime(t?: string) {
  return t ? t.slice(0, 16).replace('T', ' ') : '—'
}

onMounted(load)
</script>

<style scoped>
/* 门卫端单页（GUARD 仅此功能）：结构复用第一版全局类，C 藏蓝令牌（同家长端 p-home 口径） */
.hero-top { display: flex; align-items: flex-start; justify-content: space-between; }
.hello .hi { margin: 6px 0 2px; font-size: 13px; color: rgba(255,255,255,.75); }
.hello h1 { margin: 0; font-size: 24px; font-weight: 800; letter-spacing: 2px; }
.hello .sub { margin: 4px 0 0; font-size: 12px; color: rgba(255,255,255,.65); letter-spacing: 1px; }
.hero-actions { display: flex; align-items: center; gap: 12px; }
.hero-btn { display: flex; align-items: center; justify-content: center;
  width: 38px; height: 38px; border-radius: 50%; background: rgba(255,255,255,.16); color: #fff;
  border: none; box-shadow: 0 2px 6px rgba(10,22,60,.25); }
.hero-btn .van-icon { font-size: 19px; }

.query { padding: 8px 14px; }
.q-in { padding: 6px 0; }
.date-row { display: flex; align-items: center; gap: 10px; padding: 10px 0; cursor: pointer; }
.date-row span { flex: none; width: 72px; font-size: 13px; color: var(--app-text-3); }
.date-row b { flex: 1; font-size: 14px; color: var(--app-text-1); font-weight: 500; }
.date-row .van-icon { color: var(--app-text-3); }

.list { padding: 6px 14px; }
.row { display: flex; align-items: center; gap: 10px; padding: 12px 0; }
.row + .row { border-top: 1px solid var(--app-card-border); }
.r-body { flex: 1; min-width: 0; cursor: pointer; }
.r-title { margin: 0; font-size: 14px; color: var(--app-text-1); display: flex; align-items: baseline; gap: 6px; }
.r-title b { font-weight: 600; }
.cls { font-size: 11px; color: var(--app-text-3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.r-meta { margin: 3px 0 0; font-size: 12px; color: var(--app-text-2); }
.r-sub { margin: 3px 0 0; font-size: 11px; color: var(--app-text-3); }
.r-side { flex: none; display: flex; flex-direction: column; align-items: flex-end; gap: 5px; }
.btn { border: none; border-radius: 999px; font-size: 12px; padding: 4px 14px; }
.btn.leave { background: var(--shine-navy); color: #fff; }
.btn.back { background: var(--shine-red); color: #fff; }
.done { font-size: 11px; color: #0D9467; font-weight: 600; }
.empty { padding: 26px 0; text-align: center; font-size: 13px; color: var(--app-text-3); }

.g-foot { margin: 22px 0 30px; text-align: center; }
.g-foot .out { border: none; background: none; font-size: 13px; color: var(--app-text-3); text-decoration: underline; }

.pop { padding-bottom: 14px; }
.p-head { padding: 16px 18px 6px; }
.p-head b { font-size: 16px; display: block; }
.p-head small { display: block; margin-top: 3px; font-size: 11px; color: var(--app-text-3); }
.p-body { padding: 0 12px; }
.desc { margin: 6px 4px; font-size: 13px; color: var(--app-text-1); line-height: 1.6; }
.big-photos { display: flex; gap: 8px; flex-wrap: wrap; }
.big-photos img { width: 100%; border-radius: 10px; display: block; }
.detail-cells { border: 1px solid var(--app-card-border); border-radius: 12px; padding: 4px 12px; }
.detail-cells p { display: flex; justify-content: space-between; gap: 12px; margin: 0; padding: 10px 0; font-size: 13px; }
.detail-cells p + p { border-top: 1px solid var(--app-card-border); }
.detail-cells span { flex: none; color: var(--app-text-3); }
.detail-cells b { text-align: right; font-weight: 600; color: var(--app-text-1); }

/* C 风格覆盖（同 p-home） */
.guard .app-hero { background: var(--shine-gradient); border-bottom: 2px solid var(--shine-gold); }
.guard .app-hero::after {
  background: radial-gradient(closest-side, rgba(201,162,39,.22), rgba(201,162,39,0));
}
.guard .app-sec::before { background: var(--shine-red); }
</style>
