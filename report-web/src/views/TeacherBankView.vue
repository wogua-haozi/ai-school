<template>
  <div class="app-page bank">
    <!-- 选学生：点击弹双列联动选择（班级/学生） -->
    <div class="app-sec">成长银行</div>
    <div class="app-card tl tex-24 picker-card" @click="showPicker = true">
      <span class="ava" :class="{ has: student }" :style="student ? { background: avaColor(student.name) } : {}">
        {{ student ? student.name.charAt(0) : '?' }}
      </span>
      <div class="p-info">
        <b>{{ student ? student.name : '点击选择学生' }}</b>
        <p>{{ student ? className : '选班后列出该班学生' }}</p>
      </div>
      <van-icon name="arrow" class="p-arrow" />
    </div>

    <template v-if="student">
      <!-- 双账本：操行分 / 扬长币 -->
      <div class="wallet">
        <div class="app-card tex-25 w-cell conduct">
          <p class="w-label">操行分 · <b :class="'g-' + conduct.grade">{{ conduct.grade }} 级</b></p>
          <strong>{{ conduct.balance }}</strong>
          <span class="w-sub">基础 {{ conduct.rule?.baseScore }} · A≥{{ conduct.rule?.gradeAMin }} B≥{{ conduct.rule?.gradeBMin }} C≥{{ conduct.rule?.gradeCMin }}</span>
          <van-button size="small" plain color="#A8232B" class="w-btn" @click="openAdjust">手动调整</van-button>
        </div>
        <div class="app-card tex-26 w-cell coin">
          <p class="w-label">扬长币 · 可用</p>
          <strong class="c-gold">{{ coin.currentCoin }}</strong>
          <span class="w-sub">累计获得 {{ coin.totalCoin }}</span>
          <van-button size="small" color="#1F2A44" class="w-btn" @click="scrollToShelf">去兑换</van-button>
        </div>
      </div>

      <!-- 操行流水 -->
      <div class="app-sec">操行分流水</div>
      <div class="app-card tex-27 logs">
        <div v-if="!conduct.logs.length" class="empty">本学期暂无流水<span>基础分 {{ conduct.rule?.baseScore }} 起步</span></div>
        <div v-for="l in conduct.logs" :key="l.id" class="log">
          <div class="l-main">
            <b>{{ l.reason }}</b>
            <p>{{ l.sourceType }}{{ l.createTime ? ' · ' + fmtTime(l.createTime) : '' }}</p>
          </div>
          <!-- 批67：手动调整录错可撤销（录入人本人/管理员），余额反向回补；评价类流水不在此撤 -->
          <van-button v-if="canRevokeConduct(l)" size="small" plain type="danger"
            :loading="revokingConduct === l.id" @click="revokeConduct(l)">撤销</van-button>
          <b class="l-delta" :class="Number(l.delta) >= 0 ? 'pos' : 'neg'">{{ Number(l.delta) >= 0 ? '+' : '' }}{{ l.delta }}</b>
        </div>
      </div>

      <!-- 货架兑换（教师录入） -->
      <div class="app-sec" ref="shelfRef">货架兑换</div>
      <div class="app-card tex-28 shelf">
        <div v-if="!items.length" class="empty">货架暂无商品<span>管理员可在系统管理-成长银行上架</span></div>
        <div v-for="it in items" :key="it.id" class="item">
          <div class="i-main">
            <b>{{ it.name }}</b>
            <p>{{ it.stock >= 0 ? `剩 ${it.stock} 份` : '不限量' }}</p>
          </div>
          <div class="i-act">
            <span class="i-price">{{ it.priceCoin }} 币</span>
            <van-button size="small" color="#A8232B" :disabled="it.stock === 0" :loading="redeeming === it.id"
              @click="redeem(it)">兑换</van-button>
          </div>
        </div>
      </div>

      <!-- 最近兑换（批30：待领取可核销=领取二次确认） -->
      <div v-if="coin.recentExpenses?.length" class="app-sec">最近兑换</div>
      <div v-if="coin.recentExpenses?.length" class="app-card tex-29 logs">
        <div v-for="e in coin.recentExpenses" :key="e.id" class="log">
          <div class="l-main">
            <b>{{ e.item }}
              <van-tag v-if="e.itemId && (e.status ?? 1) === 0" plain type="warning" class="pend">待领取</van-tag>
            </b>
            <p>{{ e.createTime ? fmtTime(e.createTime) : '' }}<template v-if="e.confirmTime"> · 已核销 {{ fmtTime(e.confirmTime) }}</template></p>
          </div>
          <van-button v-if="e.itemId && (e.status ?? 1) === 0" size="small" plain color="#B07A1C"
            :loading="confirming === e.id" @click="confirmPick(e)">核销</van-button>
          <!-- 批65：误录兑换可整单撤销（录入教师本人；退币+回库存，核销前后均可。itemId 空的老手动流水无货架可退，不显示） -->
          <van-button v-if="e.itemId && isMine(e)" size="small" plain type="danger"
            :loading="reverting === e.id" @click="revertPick(e)">撤销</van-button>
          <b class="l-delta neg">-{{ e.coin }}</b>
        </div>
      </div>
    </template>

    <p class="app-foot">石实实验学校 · 石实SHINE</p>

    <!-- 班级/学生双列选择（cascade 树：班级→该班学生） -->
    <van-popup v-model:show="showPicker" position="bottom" round>
      <van-picker show-toolbar title="选择学生" :columns="pickerColumns" @cancel="showPicker = false"
        @confirm="onPick" />
    </van-popup>

    <!-- 手动调整弹层 -->
    <van-popup v-model:show="showAdjust" position="bottom" round class="adjust-pop">
      <div class="adj-head">操行分手动调整 · {{ student?.name }}</div>
      <div class="adj-body">
        <div class="adj-row">
          <span>调整值</span>
          <van-stepper v-model="adjust.delta" :step="1" :precision="0" allow-empty integer />
        </div>
        <p class="adj-hint">正数=加分，负数=减分（不能为 0）</p>
        <van-field v-model="adjust.reason" label="事由" placeholder="必填，如：课堂纪律提醒" />
        <van-button block color="#A8232B" :loading="adjusting" style="margin-top: 14px" @click="submitAdjust">确认调整</van-button>
      </div>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { showConfirmDialog, showSuccessToast, showToast } from 'vant'
import { api } from '../api/http'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const auth = useAuthStore()

interface Clazz { id: number; name: string }
interface Stu { id: number; name: string; classId?: number }

const classes = ref<Clazz[]>([])
const studentsByClass = ref<Record<number, Stu[]>>({})
const student = ref<Stu | null>(null)
const className = ref('')
const showPicker = ref(false)

const conduct = ref<any>({ balance: 0, grade: '', logs: [], rule: null })
const coin = ref<any>({ currentCoin: 0, totalCoin: 0, recentExpenses: [] })
const items = ref<any[]>([])
const showAdjust = ref(false)
const adjust = ref<{ delta: number | null; reason: string }>({ delta: null, reason: '' })
const adjusting = ref(false)
const revokingConduct = ref<number | null>(null)
const redeeming = ref<number | null>(null)
const confirming = ref<number | null>(null)
const shelfRef = ref<HTMLElement>()

/** Vant4 cascade 树：班级 → 该班学生（value=id） */
const pickerColumns = computed(() => classes.value.map((c) => ({
  text: c.name,
  value: c.id,
  children: (studentsByClass.value[c.id] ?? []).map((s) => ({ text: s.name, value: s.id })),
})))

function onPick({ selectedOptions }: any) {
  const [cls, stu] = selectedOptions
  if (!cls?.value || !stu?.value) return
  const hit = studentsByClass.value[cls.value]?.find((s) => s.id === stu.value)
  if (hit) {
    student.value = { ...hit, classId: cls.value as number }
    className.value = cls.text
    loadWallet()
  }
  showPicker.value = false
}

/** 开屏拉全「我的班级」学生（班主任通常 1-2 个班，一次拉完供 cascade 用） */
async function loadAllStudents() {
  for (const c of classes.value) {
    if (studentsByClass.value[c.id]) continue
    studentsByClass.value[c.id] = await api<any>(`/api/student/list?classId=${c.id}&page=1&size=200`)
      .then((d: any) => d.records ?? d).catch(() => [])
  }
}

/** 学生详情入口带预选（query.studentId） */
async function preselect(sid: number) {
  for (const c of classes.value) {
    const hit = studentsByClass.value[c.id]?.find((s) => s.id === sid)
    if (hit) {
      student.value = { ...hit, classId: c.id }
      className.value = c.name
      await loadWallet()
      return
    }
  }
}

let walletSeq = 0
async function loadWallet() {
  if (!student.value) return
  const sid = student.value.id
  const my = ++walletSeq
  const [c, k] = await Promise.all([
    api<any>(`/api/conduct/student/${sid}`),
    api<any>(`/api/shop/account/${sid}`),
  ])
  if (my !== walletSeq) return // 已切学生，丢弃晚到的旧学生余额/流水
  conduct.value = c
  coin.value = k
}

function fmtTime(t: string) {
  return t.slice(0, 16).replace('T', ' ')
}

function scrollToShelf() {
  shelfRef.value?.scrollIntoView({ behavior: 'smooth' })
}

function openAdjust() {
  adjust.value = { delta: null, reason: '' }
  showAdjust.value = true
}

async function submitAdjust() {
  if (!student.value) return
  if (!adjust.value.delta) {
    showToast('调整值不能为 0')
    return
  }
  if (!adjust.value.reason.trim()) {
    showToast('请填写事由')
    return
  }
  adjusting.value = true
  try {
    await api('/api/conduct/adjust', {
      method: 'POST',
      json: { studentId: student.value.id, delta: adjust.value.delta, reason: adjust.value.reason.trim() },
    })
    showSuccessToast('已调整')
    showAdjust.value = false
    await loadWallet()
  } catch (e: any) {
    showToast(e?.message || '操作失败')
  } finally { adjusting.value = false }
}

async function redeem(it: any) {
  if (!student.value) return
  try {
    await showConfirmDialog({
      title: '确认兑换',
      message: `${student.value.name} 兑换「${it.name}」，扣 ${it.priceCoin} 扬长币？`,
    })
  } catch { return }
  redeeming.value = it.id
  try {
    const r = await api<any>('/api/shop/redeem', { method: 'POST', json: { studentId: student.value.id, itemId: it.id } })
    showSuccessToast(`已兑换，余额 ${r.currentCoin}——待领取，发放后请核销`)
    await Promise.all([loadWallet(), loadItems()])
  } catch (e: any) {
    showToast(e?.message || '兑换失败')
  } finally { redeeming.value = null }
}

async function loadItems() {
  items.value = await api<any[]>('/api/shop/items').catch(() => [])
}

/** 核销（批30 领取二次确认）：实物发放后确认；幂等，重复点无副作用 */
async function confirmPick(e: any) {
  confirming.value = e.id
  try {
    await api(`/api/shop/expense/${e.id}/confirm`, { method: 'PUT' })
    showSuccessToast('已核销')
    await loadWallet()
  } catch (err: any) {
    showToast(err?.message || '核销失败')
  } finally { confirming.value = null }
}

/** 批65：撤销兑换（录入教师本人或管理员）——退币+回库存+删记录，核销前后均可 */
const reverting = ref<number | null>(null)
const myId = ref<number>()
function isMine(e: any) {
  return e.operatorId != null && e.operatorId === myId.value
}
async function revertPick(e: any) {
  try {
    await showConfirmDialog({
      title: '撤销兑换',
      message: `撤销「${e.item}」兑换？退回 ${e.coin} 枚扬长币并回补库存，已核销同样可撤销。`,
    })
  } catch { return }
  reverting.value = e.id
  try {
    await api(`/api/shop/redeem/${e.id}`, { method: 'DELETE' })
    showSuccessToast('已撤销')
    await loadWallet()
  } catch (err: any) {
    showToast(err?.message || '撤销失败')
  } finally { reverting.value = null }
}

/** 批67：撤销操行分手动调整（录入人本人或管理员）——删流水+余额反向回补；评价类不在此撤 */
function canRevokeConduct(l: any) {
  return l.sourceType === '手动'
    && (auth.role === 'ADMIN' || (l.operatorId != null && l.operatorId === myId.value))
}
async function revokeConduct(l: any) {
  try {
    await showConfirmDialog({
      title: '撤销操行分调整',
      message: `撤销「${l.reason}」（${Number(l.delta) >= 0 ? '+' : ''}${l.delta}）？余额将反向回补。`,
    })
  } catch { return }
  revokingConduct.value = l.id
  try {
    await api(`/api/conduct/adjust/${l.id}`, { method: 'DELETE' })
    showSuccessToast('已撤销')
    await loadWallet()
  } catch (err: any) {
    showToast(err?.message || '撤销失败')
  } finally { revokingConduct.value = null }
}

const palette = ['#A8232B', '#7C4DD8', '#0D9467', '#B07A1C', '#D6567A', '#3A7CA5']
function avaColor(name?: string) {
  if (!name) return palette[0]
  let h = 0
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) % 997
  return palette[h % palette.length]
}

onMounted(async () => {
  api<{ id: number }>('/api/auth/me').then((d) => { myId.value = d.id }).catch(() => { /* 拿不到则不显撤销按钮 */ })
  classes.value = await api<Clazz[]>('/api/meta/my-classes').catch(() => [])
  await Promise.all([loadAllStudents(), loadItems()])
  const pre = Number(route.query.studentId)
  if (pre) await preselect(pre)
})
</script>

<style scoped>
.picker-card { display: flex; align-items: center; gap: 12px; padding: 14px 16px; cursor: pointer; }
.ava { flex: none; width: 46px; height: 46px; border-radius: 50%; display: flex; align-items: center;
  justify-content: center; font-size: 18px; font-weight: 700; color: #fff; background: var(--app-text-3, #B7C0D2); }
.ava.has { box-shadow: 0 3px 8px rgba(23,43,99,.14); }
.p-info { flex: 1; min-width: 0; }
.p-info b { font-size: 15px; color: var(--app-text-1); }
.p-info p { margin: 4px 0 0; font-size: 12px; color: var(--app-text-3); }

.wallet { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 12px; }
.w-cell { padding: 14px 16px; display: flex; flex-direction: column; gap: 4px; }
.w-label { margin: 0; font-size: 12px; color: var(--app-text-3); }
.w-label b { font-size: 12px; }
.g-A { color: #0D9467; } .g-B { color: #3A4664; } .g-C { color: #B07A1C; } .g-D { color: var(--shine-red); }
.w-cell strong { font-size: 30px; font-weight: 800; color: var(--app-text-1); line-height: 1.15; }
.c-gold { color: #B07A1C; }
.w-sub { font-size: 11px; color: var(--app-text-3); }
.w-btn { margin-top: 8px; align-self: flex-start; }

.logs { padding: 4px 14px; }
.empty { padding: 26px 0; text-align: center; color: var(--app-text-3); font-size: 13px; line-height: 1.8; }
.empty span { display: block; font-size: 11px; }
.log { display: flex; align-items: center; gap: 10px; padding: 12px 0; }
.log + .log { border-top: 1px solid var(--app-card-border); }
.l-main { flex: 1; min-width: 0; }
.l-main b { font-size: 14px; font-weight: 600; color: var(--app-text-1);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; display: block; }
.l-main p { margin: 4px 0 0; font-size: 11px; color: var(--app-text-3); }
.pend { margin-left: 6px; vertical-align: 1px; }
.l-delta { font-size: 15px; font-weight: 700; }
.l-delta.pos { color: #0D9467; }
.l-delta.neg { color: var(--shine-red); }

.shelf { padding: 4px 14px; }
.item { display: flex; align-items: center; gap: 10px; padding: 12px 0; }
.item + .item { border-top: 1px solid var(--app-card-border); }
.i-main { flex: 1; min-width: 0; }
.i-main b { font-size: 14px; color: var(--app-text-1); }
.i-main p { margin: 4px 0 0; font-size: 11px; color: var(--app-text-3); }
.i-act { display: flex; align-items: center; gap: 10px; }
.i-price { font-size: 13px; font-weight: 700; color: #B07A1C; }

.adjust-pop { padding: 18px 16px 22px; }
.adj-head { text-align: center; font-size: 15px; font-weight: 600; color: var(--app-text-1); margin-bottom: 12px; }
.adj-row { display: flex; align-items: center; justify-content: space-between; padding: 8px 2px; font-size: 14px; }
.adj-hint { margin: 4px 2px 8px; font-size: 11px; color: var(--app-text-3); }
</style>
