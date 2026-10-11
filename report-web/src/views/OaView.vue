<template>
  <div class="app-page oa">
    <!-- 发起入口 -->
    <div class="app-card tex-b start">
      <button class="start-btn seal" type="button" @click="openNew('SEAL')">
        <van-icon name="bookmark-o" /><span>公章使用申请</span>
      </button>
      <button class="start-btn goods" type="button" @click="openNew('GOODS')">
        <van-icon name="shopping-cart-o" /><span>物资申领</span>
      </button>
      <button class="start-btn venue" type="button" @click="openNew('VENUE')">
        <van-icon name="location-o" /><span>场地申请</span>
      </button>
      <!-- 批43① 采购申请（钉钉流程移植：五级链+附件+多组明细） -->
      <button class="start-btn purchase" type="button" @click="openNew('PURCHASE')">
        <van-icon name="shop-o" /><span>采购申请</span>
      </button>
    </div>

    <van-tabs v-model:active="tab" class="oa-tabs" sticky>
      <van-tab title="我的申请">
        <div class="app-card list">
          <div v-if="!my.length" class="empty">还没有申请记录</div>
          <div v-for="f in my" :key="f.id" class="row" @click="openDetail(f.id)">
            <van-tag :type="tagOf(f.formType)" plain>{{ f.typeName }}</van-tag>
            <div class="r-body">
              <p class="r-title">{{ f.title }}</p>
              <p class="r-sub">{{ statusText(f) }} · {{ relTime(f.createTime) }}</p>
            </div>
            <span class="st" :class="stClass(f)">{{ stLabel(f) }}</span>
          </div>
        </div>
      </van-tab>
      <van-tab :title="todo.length ? `待我审批 ${todo.length}` : '待我审批'">
        <div class="app-card list">
          <div v-if="!todo.length" class="empty">暂无待审批单据</div>
          <div v-for="f in todo" :key="f.id" class="row" @click="openDetail(f.id)">
            <van-tag :type="tagOf(f.formType)" plain>{{ f.typeName }}</van-tag>
            <div class="r-body">
              <p class="r-title">{{ f.applicantName }}：{{ f.title }}</p>
              <p class="r-sub">待 {{ f.nodeName }} · {{ relTime(f.createTime) }}</p>
            </div>
            <span class="st pend">待审</span>
          </div>
        </div>
      </van-tab>
      <!-- 招采工作台（批33 两段式）：待领取核销 + 字典/入库，仅招采与管理员可见 -->
      <van-tab v-if="isProcurement" title="招采工作台">
        <div class="app-card proc-filters">
          <div class="f-row">
            <button class="f-chip" :class="{ on: procTab === 'APPROVED' }" type="button" @click="procTab = 'APPROVED'">待领取核销</button>
            <button class="f-chip" :class="{ on: procTab === 'ISSUED' }" type="button" @click="procTab = 'ISSUED'">已核销</button>
            <span class="spacer"></span>
            <button class="add-btn" type="button" @click="openManage">字典 / 入库</button>
          </div>
          <p class="f-tip">申请人领取物资后在此核销出库（核销时才扣库存）；库存不足时先入库再核销</p>
        </div>
        <div class="app-card list">
          <div v-if="!procRows.length" class="empty">{{ procTab === 'APPROVED' ? '暂无待领取的物资单' : '暂无已核销记录' }}</div>
          <div v-for="f in procRows" :key="f.id" class="row" @click="openDetail(f.id)">
            <div class="r-body">
              <p class="r-title">{{ f.applicantName }}：{{ f.title }}</p>
              <p class="r-sub">{{ f.status === 'APPROVED' ? '已审批通过 · 待领取' : '已核销出库' }} · {{ relTime(f.createTime) }}</p>
            </div>
            <button v-if="f.status === 'APPROVED'" class="issue-btn" type="button" @click.stop="doIssue(f)">核销出库</button>
            <span v-else class="st ok">已核销</span>
          </div>
        </div>
      </van-tab>
    </van-tabs>

    <!-- 发起弹层 -->
    <van-popup v-model:show="newOpen" position="bottom" round :style="{ maxHeight: '82%' }" class="pop">
      <div class="p-head">
        <b>{{ typeLabel(newType) }}</b>
        <small>{{ newType === 'SEAL' ? '提交后依次经一、二、三级审批'
          : newType === 'PURCHASE' ? '五级审批：部门负责人 → 库存确认 → 主管校领导 → 招采中心 → 采购验收'
          : '提交后按学校配置的级数审批' }}</small>
      </div>
      <div class="p-body">
        <template v-if="newType === 'SEAL'">
          <van-field v-model="newTitle" type="textarea" rows="2" autosize label="事由" placeholder="例如：学生竞赛报名表盖章"
            :rules="[{ required: true, message: '请填写事由' }]" />
          <van-field :model-value="useDate" is-link readonly label="使用日期" placeholder="选择日期（可选）"
            @click="openDate('use')" />
        </template>
        <template v-else-if="newType === 'VENUE'">
          <van-field :model-value="venueName" is-link readonly label="场地" placeholder="选择场地（必选）"
            @click="venueOpen = true" />
          <van-field :model-value="venueDate" is-link readonly label="使用日期" placeholder="必选"
            @click="openDate('venue')" />
          <van-field v-model="newTitle" type="textarea" rows="2" autosize label="事由"
            placeholder="例如：班会课使用报告厅" />
          <p v-if="!venues.length" class="goods-tip">暂无可申请场地，请管理员先在管理端维护场地字典</p>
        </template>
        <template v-else-if="newType === 'PURCHASE'">
          <van-field v-model="newTitle" type="textarea" rows="2" autosize label="申请事由"
            placeholder="例如：初一年级英语听说竞赛物资" />
          <van-field :model-value="purExpectDate" is-link readonly label="期望交付" placeholder="选择日期（必选）"
            @click="openDate('expect')" />
          <van-field v-model="purPlace" label="交付地点" placeholder="例如：办公楼二楼教务处" />
          <!-- 批49① 验收人：默认=申请人本人，提交时可改（第五级采购验收的办理人） -->
          <van-field :model-value="acceptorLabel" is-link readonly label="验收人" placeholder="默认为本人"
            @click="openAcceptors" />
          <!-- 附件（≤3 张，选图即传，同报修凭证模式） -->
          <div class="photo-row">
            <div v-for="(p, i) in purPhotos" :key="p.key" class="thumb">
              <img :src="p.url" alt="附件" />
              <button type="button" class="rm" @click="purPhotos.splice(i, 1)"><van-icon name="cross" /></button>
            </div>
            <button v-if="purPhotos.length < 3" type="button" class="add" @click="purPhotoInput?.click()">
              <van-icon name="photograph" /><small>附件</small>
            </button>
          </div>
          <input ref="purPhotoInput" type="file" accept="image/jpeg,image/png" hidden multiple @change="onPurPhoto" />
          <!-- 采购明细（多组：名称/型号规格/数量/备注，复制加行） -->
          <div class="app-sec" style="margin: 8px 0 4px">采购明细</div>
          <div v-for="(it, i) in newItems" :key="i" class="p-item">
            <div class="p-item-head">
              <b>物品{{ i + 1 }}</b>
              <span class="p-ops">
                <button type="button" class="op copy" @click="copyItem(i, it)">复制</button>
                <button v-if="newItems.length > 1" type="button" class="op del" @click="newItems.splice(i, 1)">删除</button>
              </span>
            </div>
            <van-field v-model="it.name" label="物品名称" placeholder="必填" input-align="right" />
            <van-field v-model="it.spec" label="型号规格" placeholder="必填" input-align="right" />
            <van-field v-model="it.qty" label="数量" placeholder="必填" input-align="right" />
            <van-field v-model="it.note" label="备注" placeholder="选填" input-align="right" />
          </div>
          <button class="add-line" type="button" @click="newItems.push({ name: '', spec: '', qty: '', note: '' })">
            + 添加物品
          </button>
        </template>
        <template v-else>
          <div v-for="(l, i) in newLines" :key="i" class="g-line">
            <van-field :model-value="l.name" is-link readonly label="物资" placeholder="选择物资"
              @click="pickLine = i; goodsOpen = true" />
            <van-field v-model="l.qty" type="digit" label="数量" placeholder="数量" class="qty" />
            <button v-if="newLines.length > 1" class="del" type="button" @click="newLines.splice(i, 1)">
              <van-icon name="cross" />
            </button>
          </div>
          <button class="add-line" type="button" @click="newLines.push({ goodsId: 0, name: '', qty: '' })">
            + 添加物资
          </button>
          <p v-if="!goods.length" class="goods-tip">暂无可申领物资，请管理员先在管理端添加物资字典</p>
        </template>
        <van-button round block type="primary" class="submit" :loading="submitting" @click="doSubmit">提交申请</van-button>
      </div>
    </van-popup>

    <!-- 详情弹层 -->
    <van-popup v-model:show="detailOpen" position="bottom" round :style="{ maxHeight: '86%' }" class="pop">
      <template v-if="detail">
        <div class="p-head">
          <b>{{ detail.typeName }} · {{ detail.title }}</b>
          <small>{{ detail.applicantName }} 发起于 {{ relTime(detail.createTime) }}</small>
        </div>
        <div class="p-body">
          <!-- 明细 -->
          <div class="app-sec" style="margin: 0 0 4px">申请明细</div>
          <div v-if="detail.formType === 'SEAL'" class="detail-cells">
            <p><span>使用日期</span><b>{{ detailJson.useDate || '未指定' }}</b></p>
            <p><span>事由</span><b>{{ detailJson.reason }}</b></p>
          </div>
          <div v-else-if="detail.formType === 'LEAVE'" class="detail-cells">
            <p><span>请假类型</span><b>{{ detailJson.leaveType }}</b></p>
            <p><span>起止日期</span><b>{{ detailJson.startDate }} ~ {{ detailJson.endDate }}</b></p>
            <p><span>事由</span><b>{{ detailJson.reason }}</b></p>
          </div>
          <div v-else-if="detail.formType === 'VENUE'" class="detail-cells">
            <p><span>场地</span><b>{{ detailJson.venueName }}</b></p>
            <p><span>使用日期</span><b>{{ detailJson.useDate }}</b></p>
            <p><span>事由</span><b>{{ detailJson.reason }}</b></p>
          </div>
          <!-- 批43① 采购：基础信息 + 明细 + 附件 + 库存参考 -->
          <template v-else-if="detail.formType === 'PURCHASE'">
            <div class="detail-cells">
              <p><span>期望交付</span><b>{{ detailJson.expectDate }}</b></p>
              <p><span>交付地点</span><b>{{ detailJson.place }}</b></p>
              <p v-if="detail.acceptorNames?.length"><span>验收人</span><b>{{ detail.acceptorNames.join('、') }}</b></p>
              <p><span>事由</span><b>{{ detailJson.reason }}</b></p>
            </div>
            <div class="app-sec" style="margin: 14px 0 4px">采购明细（{{ (detailJson.items || []).length }} 项）</div>
            <div class="p-items-view">
              <div v-for="(it, i) in detailJson.items" :key="i" class="pv-row">
                <p class="pv-name">{{ i + 1 }}. {{ it.name }}<van-tag plain size="small" class="pv-qty">{{ it.qty }}</van-tag></p>
                <p class="pv-sub">规格：{{ it.spec }}<template v-if="it.note"> · {{ it.note }}</template></p>
              </div>
            </div>
            <template v-if="detail.photoUrls?.length">
              <div class="app-sec" style="margin: 14px 0 4px">附件（{{ detail.photoUrls.length }}）</div>
              <div class="big-photos">
                <img v-for="(u, i) in purDetailUrls" :key="i" :src="u" alt="附件" />
              </div>
            </template>
            <template v-if="detail.stockMatches?.length">
              <div class="app-sec" style="margin: 14px 0 4px">现有库存参考（库存确认用）</div>
              <div class="p-items-view">
                <div v-for="(m, i) in detail.stockMatches" :key="i" class="pv-row">
                  <p class="pv-name">{{ m.name }}<van-tag plain type="success" size="small" class="pv-qty">库存 {{ m.stock }}{{ m.unit }}</van-tag></p>
                  <p class="pv-sub">匹配「{{ m.item }}」<template v-if="m.location"> · {{ m.location }}</template></p>
                </div>
              </div>
            </template>
            <p v-if="detail.ccNames?.length" class="cc-tip">抄送：{{ detail.ccNames.join('、') }}</p>
          </template>
          <div v-else class="detail-cells goods-cells">
            <p v-for="(l, i) in detailJson" :key="i">
              <span>{{ l.name }}</span><b>{{ l.qty }} {{ l.unit }}<small v-if="l.location"> · {{ l.location }}</small></b>
            </p>
          </div>

          <!-- 流转日志 -->
          <div class="app-sec" style="margin: 14px 0 4px">流转记录</div>
          <div class="logs">
            <div v-for="(l, i) in detail.logs" :key="i" class="log">
              <span class="l-dot" :class="actClass(l.action)"></span>
              <div class="l-body">
                <p><b>{{ l.nodeName }}</b> · {{ l.operatorName }} {{ actLabel(l.action) }}<small v-if="l.note">：{{ l.note }}</small></p>
                <small>{{ relTime(l.createTime) }}</small>
              </div>
            </div>
          </div>

          <!-- 动作区 -->
          <van-field v-if="canAct" v-model="opinion" type="textarea" rows="1" autosize label="意见"
            placeholder="审批意见（可选）" class="opinion" />
          <div v-if="canApprove" class="acts">
            <van-button round type="danger" plain @click="doHandle('REJECT')">驳回</van-button>
            <van-button round type="primary" @click="doHandle('AGREE')">通过</van-button>
          </div>
          <van-button v-else-if="canRevoke" round block plain type="default" class="submit"
            @click="doHandle('REVOKE')">撤回申请</van-button>
          <van-button v-else-if="canIssueDetail" round block type="primary" class="submit"
            @click="doIssueDetail">核销出库（已领取）</van-button>
          <p v-if="detail.status === 'PENDING'" class="node-tip">
            当前节点：<b>{{ detail.nodeName }}</b>（共 {{ detail.levels }} 级审批）
          </p>
        </div>
      </template>
    </van-popup>

    <!-- 物资/日期选择器 -->
    <van-popup v-model:show="goodsOpen" position="bottom" round>
      <van-picker title="选择物资" :columns="goodsColumns" @confirm="onGoods" @cancel="goodsOpen = false" />
    </van-popup>
    <van-popup v-model:show="venueOpen" position="bottom" round>
      <van-picker title="选择场地" :columns="venueColumns" @confirm="onVenue" @cancel="venueOpen = false" />
    </van-popup>
    <van-popup v-model:show="dateOpen" position="bottom" round>
      <van-date-picker :title="dateTitle"
        v-model="dateBuf" :columns-type="['year', 'month', 'day']"
        :min-date="minDate" :max-date="maxDate" @confirm="onDateOk" @cancel="dateOpen = false" />
    </van-popup>

    <!-- 批49① 验收人选择：搜索+多选（同配置或签口径，任一人验收即过级）；不选=申请人本人 -->
    <van-popup v-model:show="acceptorOpen" position="bottom" round :style="{ maxHeight: '72%' }" class="pop">
      <div class="p-head">
        <b>选择验收人</b>
        <small>不选=本人验收；多选时任一人验收即通过（第五级）</small>
      </div>
      <div class="p-body">
        <van-search v-model="acceptorKw" placeholder="搜索姓名" />
        <div class="acc-list">
          <div v-for="t in acceptorFiltered" :key="t.id" class="acc-row" @click="toggleAcceptor(t.id)">
            <van-checkbox :model-value="acceptorIds.includes(t.id)" shape="square" @click.prevent="toggleAcceptor(t.id)" />
            <span>{{ t.realName }}</span>
          </div>
          <p v-if="!acceptorFiltered.length" class="empty">无匹配人员</p>
        </div>
        <van-button round block type="primary" size="small" class="mini-btn" @click="acceptorOpen = false">确定</van-button>
      </div>
    </van-popup>

    <!-- 招采字典/入库/流水（批33；端点与管理端共用，招采已放行） -->
    <van-popup v-model:show="manageOpen" position="bottom" round :style="{ maxHeight: '88%' }" class="pop">
      <div class="p-head"><b>物资字典与库存</b><small>新增物资 · 入库登记 · 出入库流水</small></div>
      <div class="p-body">
        <div class="app-sec" style="margin: 0 0 4px">新增物资</div>
        <van-field v-model="newGoods.name" label="名称" placeholder="例如：A4复印纸" input-align="right" />
        <van-field v-model="newGoods.unit" label="单位" placeholder="例如：包（默认 件）" input-align="right" />
        <van-field v-model="newGoods.location" label="存放位置" placeholder="例如：仓库A架2层（可选）" input-align="right" />
        <van-button round block size="small" type="primary" plain class="mini-btn" :loading="savingGoods" @click="doAddGoods">保存新物资</van-button>

        <div class="app-sec" style="margin: 14px 0 4px">入库登记</div>
        <van-field :model-value="stockForm.name" is-link readonly label="物资" placeholder="选择物资"
          input-align="right" @click="goodsPickOpen = true" />
        <van-field v-model="stockForm.qty" type="digit" label="数量" placeholder="入库数量" input-align="right" />
        <van-field v-model="stockForm.note" label="备注" placeholder="例如：10月采购到货（可选）" input-align="right" />
        <van-button round block size="small" type="primary" class="mini-btn" :loading="stocking" @click="doStockIn">确认入库</van-button>

        <div class="app-sec" style="margin: 14px 0 4px">库存字典</div>
        <div class="dict">
          <p v-for="g in goodsAdmin" :key="g.id">
            <span>{{ g.name }}</span>
            <b>{{ g.stock }} {{ g.unit }}<small v-if="g.location"> · {{ g.location }}</small></b>
          </p>
        </div>

        <div class="app-sec" style="margin: 14px 0 4px">出入库流水（最近）</div>
        <div class="dict">
          <p v-for="r in flowRows" :key="r.id">
            <span>{{ r.direction === 'IN' ? '入库' : '出库' }} {{ r.goodsName }} × {{ r.qty }}{{ r.unit }}</span>
            <b class="flow-meta">{{ r.operatorName }} · {{ relTime(r.createTime) }}</b>
          </p>
        </div>
      </div>
    </van-popup>
    <van-popup v-model:show="goodsPickOpen" position="bottom" round>
      <van-picker title="选择物资" :columns="adminGoodsColumns" @confirm="onPickStockGoods" @cancel="goodsPickOpen = false" />
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { showConfirmDialog, showSuccessToast, showToast } from 'vant'
import { api, apiForm, fetchBlob } from '../api/http'
import { relTime } from '../utils/fmt'
import { useAuthStore } from '../stores/auth'

interface OaRow {
  id: number; formType: string; typeName: string; title: string; applicantName: string
  status: string; currentLevel: number; nodeName: string; createTime: string
}
interface OaDetail extends OaRow {
  detail: string; levels: number
  logs: { action: string; nodeName: string; operatorName: string; note: string; createTime: string }[]
  photoUrls?: string[] // 批43① 采购附件
  stockMatches?: { item: string; name: string; stock: number; unit: string; location: string }[]
  ccNames?: string[]
  acceptorNames?: string[] // 批49① 采购验收人
}
interface GoodsOpt { id: number; name: string; unit: string; stock: number; location: string }

const auth = useAuthStore()
const tab = ref(0)
const my = ref<OaRow[]>([])
const todo = ref<OaRow[]>([])
const detail = ref<OaDetail | null>(null)
const detailOpen = ref(false)

async function load() {
  const [a, b] = await Promise.all([
    api<OaRow[]>('/api/oa/my'),
    api<OaRow[]>('/api/oa/todo'),
  ])
  my.value = a; todo.value = b
}

// ---- 发起 ----
const newOpen = ref(false)
const newType = ref('SEAL')
const newTitle = ref('')
const useDate = ref('')
const goods = ref<GoodsOpt[]>([])
const goodsOpen = ref(false)
const pickLine = ref(0)
const newLines = ref<{ goodsId: number; name: string; qty: string }[]>([{ goodsId: 0, name: '', qty: '' }])
const submitting = ref(false)

/* 日期三字段共用一个 picker（use=公章使用日期 / venue=场地使用日期 / expect=采购期望交付） */
const dateOpen = ref(false)
const dateField = ref<'use' | 'venue' | 'expect'>('use')
const dateBuf = ref<string[]>([])
const venues = ref<{ id: number; name: string; location: string; capacity: number | null }[]>([])
const venueOpen = ref(false)
const venueId = ref(0)
const venueName = ref('')
const venueDate = ref('')

// ───────── 批43① 采购申请 ─────────
const purExpectDate = ref('')
const purPlace = ref('')
const purPhotos = ref<{ key: string; url: string }[]>([])
const purPhotoInput = ref<HTMLInputElement>()
const newItems = ref<{ name: string; spec: string; qty: string; note: string }[]>([{ name: '', spec: '', qty: '', note: '' }])
const purDetailUrls = ref<string[]>([])

// 批49① 验收人（默认=申请人本人，提交时可改）
const acceptorIds = ref<number[]>([])
const acceptorOpen = ref(false)
const acceptorKw = ref('')
const staffList = ref<{ id: number; realName: string }[]>([])
const acceptorLabel = computed(() => {
  if (!acceptorIds.value.length) return '本人（默认）'
  return acceptorIds.value.map((id) => staffList.value.find((t) => t.id === id)?.realName)
    .filter(Boolean).join('、')
})
const acceptorFiltered = computed(() => {
  const kw = acceptorKw.value.trim()
  return kw ? staffList.value.filter((t) => t.realName.includes(kw)) : staffList.value
})
function openAcceptors() {
  acceptorOpen.value = true
  if (!staffList.value.length) {
    api<{ id: number; realName: string }[]>('/api/meta/teachers').then((d) => (staffList.value = d)).catch(() => {})
  }
}
function toggleAcceptor(id: number) {
  const i = acceptorIds.value.indexOf(id)
  if (i >= 0) acceptorIds.value.splice(i, 1)
  else acceptorIds.value.push(id)
}

/** 附件选图即传（≤3 张，jpg/jpeg/png；提交时只带 objectName） */
async function onPurPhoto(e: Event) {
  const files = Array.from((e.target as HTMLInputElement).files ?? [])
  ;(e.target as HTMLInputElement).value = ''
  for (const f of files) {
    if (purPhotos.value.length >= 3) { showToast('附件最多 3 张'); break }
    try {
      const fd = new FormData()
      fd.append('photo', f)
      const r = await apiForm<{ photo: string }>('/api/oa/purchase/photo', fd)
      purPhotos.value.push({ key: r.photo, url: URL.createObjectURL(f) })
    } catch { showToast('附件上传失败'); return }
  }
}

/** 参考钉钉「复制」：复制该组明细为新行 */
function copyItem(i: number, it: { name: string; spec: string; qty: string; note: string }) {
  newItems.value.splice(i + 1, 0, { ...it })
}

const minDate = new Date(2020, 0, 1)
const maxDate = new Date(2030, 11, 31)
const goodsColumns = computed(() => goods.value.map((g) => ({
  text: `${g.name}（库存 ${g.stock}${g.unit}）`, value: g.id,
})))
const venueColumns = computed(() => venues.value.map((v) => ({
  text: v.location ? `${v.name}（${v.location}）` : v.name, value: v.id,
})))

function typeLabel(t: string) {
  return ({ SEAL: '公章使用申请', GOODS: '物资申领', LEAVE: '教师请假', VENUE: '场地申请', PURCHASE: '采购申请' } as Record<string, string>)[t] || t
}
function tagOf(t: string) {
  return ({ SEAL: 'primary', GOODS: 'warning', LEAVE: 'success', VENUE: 'default', PURCHASE: 'primary' } as Record<string, string>)[t] || 'primary'
}
const dateTitle = computed(() =>
  ({ use: '使用日期', venue: '使用日期', expect: '期望交付日期' } as Record<string, string>)[dateField.value])

function openDate(field: 'use' | 'venue' | 'expect') {
  dateField.value = field
  const v = field === 'use' ? useDate.value
    : field === 'expect' ? purExpectDate.value : venueDate.value
  if (v) {
    dateBuf.value = v.split('-')
  } else { // 空 model 的 van-date-picker 会落 min-date（2020），须预置今天
    const now = new Date()
    dateBuf.value = [String(now.getFullYear()), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')]
  }
  dateOpen.value = true
}
function onDateOk() {
  const v = dateBuf.value.join('-')
  if (dateField.value === 'use') useDate.value = v
  else if (dateField.value === 'expect') purExpectDate.value = v
  else venueDate.value = v
  dateOpen.value = false
}

function onVenue(ev: { selectedOptions: { text: string; value: number }[] }) {
  const opt = ev.selectedOptions?.[0]
  if (opt) {
    venueId.value = opt.value
    venueName.value = opt.text
  }
  venueOpen.value = false
}

function openNew(type: string) {
  newType.value = type
  newTitle.value = ''
  useDate.value = ''
  venueId.value = 0
  venueName.value = ''
  venueDate.value = ''
  newLines.value = [{ goodsId: 0, name: '', qty: '' }]
  purExpectDate.value = ''
  purPlace.value = ''
  purPhotos.value.forEach((p) => URL.revokeObjectURL(p.url))
  purPhotos.value = []
  newItems.value = [{ name: '', spec: '', qty: '', note: '' }]
  acceptorIds.value = []
  if (type === 'GOODS' && !goods.value.length) {
    api<GoodsOpt[]>('/api/oa/goods').then((d) => (goods.value = d)).catch(() => {})
  }
  if (type === 'VENUE' && !venues.value.length) {
    api<typeof venues.value>('/api/venue/list').then((d) => (venues.value = d)).catch(() => {})
  }
  newOpen.value = true
}

function onGoods(ev: { selectedOptions: { text: string; value: number }[] }) {
  const opt = ev.selectedOptions?.[0]
  if (opt && newLines.value[pickLine.value]) {
    const g = goods.value.find((x) => x.id === opt.value)
    newLines.value[pickLine.value].goodsId = opt.value
    newLines.value[pickLine.value].name = g ? g.name : opt.text
  }
  goodsOpen.value = false
}

async function doSubmit() {
  if (newType.value === 'SEAL') {
    if (!newTitle.value.trim()) { showToast('请填写事由'); return }
    submitting.value = true
    try {
      await api('/api/oa/submit', { method: 'POST', json: { formType: 'SEAL', title: newTitle.value, useDate: useDate.value } })
    } finally { submitting.value = false }
  } else if (newType.value === 'VENUE') {
    if (!venueId.value) { showToast('请选择场地'); return }
    if (!venueDate.value) { showToast('请选择使用日期'); return }
    if (!newTitle.value.trim()) { showToast('请填写申请事由'); return }
    submitting.value = true
    try {
      await api('/api/oa/submit', {
        method: 'POST',
        json: { formType: 'VENUE', title: newTitle.value, venueId: venueId.value, useDate: venueDate.value },
      })
    } finally { submitting.value = false }
  } else if (newType.value === 'PURCHASE') {
    if (!newTitle.value.trim()) { showToast('请填写申请事由'); return }
    if (!purExpectDate.value) { showToast('请选择期望交付日期'); return }
    if (!purPlace.value.trim()) { showToast('请填写交付地点'); return }
    const items = newItems.value.filter((it) => it.name.trim() || it.spec.trim() || it.qty.trim())
    if (!items.length) { showToast('请至少填写一项采购明细'); return }
    for (const it of items) {
      if (!it.name.trim() || !it.spec.trim() || !it.qty.trim()) { showToast('每项明细须填写物品名称、型号规格与数量'); return }
    }
    submitting.value = true
    try {
      await api('/api/oa/submit', {
        method: 'POST',
        json: {
          formType: 'PURCHASE',
          title: newTitle.value,
          expectDate: purExpectDate.value,
          place: purPlace.value,
          photos: purPhotos.value.map((p) => p.key),
          items,
          acceptorIds: acceptorIds.value, // 批49①：空=后端回退申请人本人
        },
      })
    } finally { submitting.value = false }
  } else {
    const lines = newLines.value.filter((l) => l.goodsId && Number(l.qty) > 0)
    if (!lines.length) { showToast('请选择物资并填写数量'); return }
    submitting.value = true
    try {
      await api('/api/oa/submit', {
        method: 'POST',
        json: { formType: 'GOODS', goodsLines: lines.map((l) => ({ goodsId: l.goodsId, qty: Number(l.qty) })) },
      })
    } finally { submitting.value = false }
  }
  newOpen.value = false
  showSuccessToast('已提交')
  load()
}

// ---- 详情/审批 ----
const opinion = ref('')
const detailJson = computed<any>(() => {
  if (!detail.value) return {}
  try { return JSON.parse(detail.value.detail) } catch { return detail.value.formType === 'SEAL' ? {} : [] }
})
/** 管理员=任意 PENDING 可代审；普通教师=当前级审批人是自己（todo 列表成员） */
const canApprove = computed(() => {
  if (!detail.value || detail.value.status !== 'PENDING') return false
  if (auth.role === 'ADMIN') return true
  return todo.value.some((t) => t.id === detail.value!.id)
})
const canRevoke = computed(() => detail.value?.status === 'PENDING' && my.value.some((m) => m.id === detail.value!.id))
const canAct = computed(() => canApprove.value || canRevoke.value)

async function openDetail(id: number) {
  opinion.value = ''
  detail.value = await api<OaDetail>(`/api/oa/${id}`)
  detailOpen.value = true
  // 批43① 采购附件：鉴权下流式直取（同报修凭证模式）
  purDetailUrls.value = []
  for (const u of detail.value.photoUrls ?? []) {
    try {
      const blob = await fetchBlob(u)
      purDetailUrls.value.push(URL.createObjectURL(blob))
    } catch { /* 单张缺失不阻塞 */ }
  }
}

async function doHandle(action: string) {
  if (!detail.value) return
  await api(`/api/oa/${detail.value.id}/handle`, { method: 'POST', json: { action, note: opinion.value.trim() } })
  showSuccessToast(action === 'AGREE' ? '已通过' : action === 'REJECT' ? '已驳回' : '已撤回')
  detailOpen.value = false
  load()
}

// ---- 招采工作台（批33 两段式：末级通过=待领取，领取后核销才出库） ----
interface AdminGoods { id: number; name: string; unit: string; stock: number; location: string; status: number }
interface FlowRow { id: number; goodsName: string; qty: number; unit: string; direction: string; operatorName: string; createTime: string }

const isProcurement = computed(() => ['PROCUREMENT', 'ADMIN'].includes(auth.role))
const procTab = ref('APPROVED')
const procRows = ref<OaRow[]>([])

async function loadProc() {
  procRows.value = await api<OaRow[]>(`/api/oa/procurement/list?status=${procTab.value}`)
}
watch(procTab, loadProc)
// 招采 tab 下标 2（仅招采/管理员有第三 tab），激活时加载
watch(tab, (v) => { if (v === 2 && isProcurement.value) loadProc() })

async function doIssue(f: OaRow) {
  await showConfirmDialog({ title: '核销出库', message: `确认 ${f.applicantName} 已领取「${f.title}」？核销后将扣减库存并通知申请人。` })
  await api(`/api/oa/${f.id}/issue`, { method: 'PUT' })
  showSuccessToast('已核销出库')
  loadProc()
}

const canIssueDetail = computed(() => isProcurement.value && !!detail.value
  && detail.value.formType === 'GOODS' && detail.value.status === 'APPROVED')
async function doIssueDetail() {
  if (!detail.value) return
  await showConfirmDialog({ title: '核销出库', message: '确认申请人已领取该批物资？核销后将扣减库存并通知申请人。' })
  await api(`/api/oa/${detail.value.id}/issue`, { method: 'PUT' })
  showSuccessToast('已核销出库')
  detailOpen.value = false
  loadProc()
  load()
}

// 字典/入库/流水（与管理端共用端点，批33 起招采已放行）
const manageOpen = ref(false)
const goodsAdmin = ref<AdminGoods[]>([])
const flowRows = ref<FlowRow[]>([])
const newGoods = ref({ name: '', unit: '', location: '' })
const stockForm = ref({ goodsId: 0, name: '', qty: '', note: '' })
const goodsPickOpen = ref(false)
const savingGoods = ref(false)
const stocking = ref(false)
const adminGoodsColumns = computed(() => goodsAdmin.value.map((g) => ({
  text: `${g.name}（库存 ${g.stock}${g.unit}）`, value: g.id,
})))

async function openManage() {
  manageOpen.value = true
  if (!goodsAdmin.value.length) goodsAdmin.value = await api<AdminGoods[]>('/api/admin/goods')
  if (!flowRows.value.length) flowRows.value = await api<FlowRow[]>('/api/admin/goods/flow')
}

async function doAddGoods() {
  if (!newGoods.value.name.trim()) { showToast('请填写物资名称'); return }
  savingGoods.value = true
  try {
    await api('/api/admin/goods', { method: 'POST', json: { name: newGoods.value.name.trim(), unit: newGoods.value.unit.trim(), location: newGoods.value.location.trim() } })
    showSuccessToast('已添加新物资')
    newGoods.value = { name: '', unit: '', location: '' }
    goodsAdmin.value = await api<AdminGoods[]>('/api/admin/goods')
  } finally { savingGoods.value = false }
}

function onPickStockGoods(ev: { selectedOptions: { text: string; value: number }[] }) {
  const opt = ev.selectedOptions?.[0]
  if (opt) {
    stockForm.value.goodsId = opt.value
    stockForm.value.name = goodsAdmin.value.find((g) => g.id === opt.value)?.name || opt.text
  }
  goodsPickOpen.value = false
}

async function doStockIn() {
  if (!stockForm.value.goodsId) { showToast('请选择物资'); return }
  if (!(Number(stockForm.value.qty) > 0)) { showToast('请填写入库数量'); return }
  stocking.value = true
  try {
    await api('/api/admin/goods/stock', { method: 'POST', json: { goodsId: stockForm.value.goodsId, qty: Number(stockForm.value.qty), note: stockForm.value.note.trim() } })
    showSuccessToast('已入库')
    stockForm.value = { goodsId: 0, name: '', qty: '', note: '' }
    goodsAdmin.value = await api<AdminGoods[]>('/api/admin/goods')
    flowRows.value = await api<FlowRow[]>('/api/admin/goods/flow')
  } finally { stocking.value = false }
}

// ---- 展示 ----
function statusText(f: OaRow) {
  return f.status === 'PENDING' ? `${f.nodeName}中` : stLabel(f)
}
function stLabel(f: OaRow) {
  if (f.formType === 'GOODS' && f.status === 'APPROVED') return '待领取' // 批33 两段式
  return ({ PENDING: '待审', APPROVED: '已通过', REJECTED: '已驳回', REVOKED: '已撤回', ISSUED: '已核销' } as Record<string, string>)[f.status] || f.status
}
function stClass(f: OaRow) {
  if (f.formType === 'GOODS' && f.status === 'APPROVED') return 'pend'
  return ({ PENDING: 'pend', APPROVED: 'ok', REJECTED: 'bad', REVOKED: 'off', ISSUED: 'ok' } as Record<string, string>)[f.status] || ''
}
function actLabel(a: string) {
  return ({ SUBMIT: '提交了申请', AGREE: '通过', REJECT: '驳回', REVOKE: '撤回', ISSUE: '核销出库' } as Record<string, string>)[a] || a
}
function actClass(a: string) {
  return ({ SUBMIT: 's', AGREE: 'ok', REJECT: 'bad', REVOKE: 'off', ISSUE: 'ok' } as Record<string, string>)[a] || ''
}

onMounted(load)
</script>

<style scoped>
.oa-tabs { margin-top: 12px; }
.start { display: flex; gap: 8px; padding: 14px; margin-top: 12px; }
.start-btn { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 6px;
  padding: 14px 0 10px; border-radius: 14px; border: none; color: #fff; font-size: 12px; font-weight: 600; }
.start-btn .van-icon { font-size: 21px; }
.start-btn.seal { background: linear-gradient(150deg, #8C1D23, #A8232B); }
.start-btn.goods { background: linear-gradient(150deg, #B45309, #D97706); }
.start-btn.venue { background: linear-gradient(150deg, #1E40AF, #3B82F6); }
.start-btn.purchase { background: linear-gradient(150deg, #6D28D9, #8B5CF6); }

/* 批43① 采购：附件选择行（同报修） */
.photo-row { display: flex; gap: 8px; margin: 10px 2px; }
.photo-row .thumb { position: relative; }
.photo-row .thumb img { width: 72px; height: 72px; object-fit: cover; border-radius: 10px; display: block; }
.photo-row .thumb .rm { position: absolute; right: -6px; top: -6px; width: 20px; height: 20px;
  border: none; background: rgba(0, 0, 0, 0.55); color: #fff; border-radius: 50%;
  display: flex; align-items: center; justify-content: center; }
.photo-row .add { width: 72px; height: 72px; border: 1px dashed var(--app-card-border); border-radius: 10px;
  background: none; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px;
  color: var(--app-text-3); font-size: 11px; }
.photo-row .add .van-icon { font-size: 18px; }

/* 批43① 采购：明细组（多组+复制加行） */
.p-item { border: 1px solid var(--app-card-border); border-radius: 12px; margin: 8px 0; overflow: hidden; }
.p-item-head { display: flex; align-items: center; justify-content: space-between;
  padding: 8px 12px; background: var(--app-card-border); }
.p-item-head b { font-size: 12px; color: var(--app-text-2); }
.p-ops { display: flex; gap: 6px; }
.op { border: none; border-radius: 999px; padding: 3px 12px; font-size: 11px; }
.op.copy { background: #EEF2FF; color: #4F46E5; }
.op.del { background: #FDECEC; color: #EF4444; }

/* 批49① 验收人选择弹层 */
.acc-list { border: 1px solid var(--app-card-border); border-radius: 12px; margin-top: 8px;
  max-height: 46vh; overflow-y: auto; }
.acc-row { display: flex; align-items: center; gap: 10px; padding: 11px 14px; cursor: pointer; }
.acc-row + .acc-row { border-top: 1px solid var(--app-card-border); }
.acc-row span { font-size: 14px; color: var(--app-text-1); }

/* 批43① 采购：详情明细/库存参考展示 */
.p-items-view { border: 1px solid var(--app-card-border); border-radius: 12px; padding: 2px 12px; }
.pv-row { padding: 10px 0; }
.pv-row + .pv-row { border-top: 1px solid var(--app-card-border); }
.pv-name { margin: 0; font-size: 13px; font-weight: 600; color: var(--app-text-1); }
.pv-qty { margin-left: 8px; }
.pv-sub { margin: 3px 0 0; font-size: 11px; color: var(--app-text-3); }
.cc-tip { margin: 10px 2px 0; font-size: 11px; color: var(--app-text-3); }
.big-photos { display: flex; gap: 8px; flex-wrap: wrap; }
.big-photos img { width: 100%; border-radius: 10px; display: block; }

.list { margin-top: 12px; padding: 6px 14px; }
.row { display: flex; align-items: center; gap: 10px; padding: 12px 0; cursor: pointer; }
.row + .row { border-top: 1px solid var(--app-card-border); }
.r-body { flex: 1; min-width: 0; }
.r-title { margin: 0; font-size: 14px; font-weight: 600; color: var(--app-text-1);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.r-sub { margin: 3px 0 0; font-size: 11px; color: var(--app-text-3); }
.st { flex: none; font-size: 12px; font-weight: 600; }
.st.pend { color: #B45309; } .st.ok { color: #0D9467; } .st.bad { color: #EF4444; } .st.off { color: var(--app-text-3); }
.empty { padding: 26px 0; text-align: center; font-size: 13px; color: var(--app-text-3); }

.pop { padding-bottom: 14px; }
.p-head { padding: 16px 18px 6px; }
.p-head b { font-size: 16px; display: block; }
.p-head small { display: block; margin-top: 3px; font-size: 11px; color: var(--app-text-3); }
.p-body { padding: 0 12px; }

.g-line { position: relative; padding-right: 30px; }
.g-line .del { position: absolute; right: 2px; bottom: 14px; width: 26px; height: 26px;
  border: none; background: #FDECEC; color: #EF4444; border-radius: 50%; display: flex;
  align-items: center; justify-content: center; }
.add-line { display: block; width: 100%; margin: 6px 0; padding: 10px 0; border: 1px dashed var(--app-card-border);
  border-radius: 10px; background: none; font-size: 13px; color: var(--app-blue); }
.goods-tip { margin: 8px 4px; font-size: 11px; color: var(--app-text-3); }
.submit { margin: 14px 0 4px; }

.detail-cells { border: 1px solid var(--app-card-border); border-radius: 12px; padding: 4px 12px; }
.detail-cells p { display: flex; justify-content: space-between; gap: 12px; margin: 0; padding: 10px 0;
  font-size: 13px; }
.detail-cells p + p { border-top: 1px solid var(--app-card-border); }
.detail-cells span { flex: none; color: var(--app-text-3); }
.detail-cells b { text-align: right; font-weight: 600; color: var(--app-text-1); }
.detail-cells small { color: var(--app-text-3); font-weight: 400; }

.logs { padding: 2px 2px 0; }
.log { display: flex; gap: 10px; padding: 8px 0; }
.l-dot { flex: none; width: 10px; height: 10px; border-radius: 50%; margin-top: 5px; background: var(--app-text-3); }
.l-dot.s { background: var(--app-blue); } .l-dot.ok { background: #0D9467; }
.l-dot.bad { background: #EF4444; } .l-dot.off { background: var(--app-text-3); }
.l-body p { margin: 0; font-size: 13px; color: var(--app-text-1); }
.l-body b { font-weight: 600; }
.l-body small { font-size: 11px; color: var(--app-text-3); }
.l-body > small { display: block; margin-top: 2px; }
.opinion { margin-top: 10px; }
.acts { display: flex; gap: 12px; margin: 12px 0 4px; }
.acts .van-button { flex: 1; }
.node-tip { margin: 10px 4px 4px; font-size: 11px; color: var(--app-text-3); }
.node-tip b { color: var(--app-text-2); }

/* 招采工作台（批33） */
.proc-filters { margin-top: 12px; padding: 10px 14px; }
.f-row { display: flex; align-items: center; gap: 8px; }
.f-chip { flex: none; border: 1px solid var(--app-card-border); background: none; border-radius: 999px;
  padding: 5px 16px; font-size: 13px; color: var(--app-text-2); }
.f-chip.on { border-color: var(--app-blue); color: #fff; background: var(--app-blue); font-weight: 600; }
.spacer { flex: 1; }
.f-tip { margin: 8px 0 0; font-size: 11px; color: var(--app-text-3); }
.add-btn { flex: none; border: none; border-radius: 999px; padding: 5px 16px; font-size: 13px; font-weight: 600;
  background: var(--app-blue); color: #fff; }
.issue-btn { flex: none; border: none; border-radius: 999px; padding: 6px 14px; font-size: 12px; font-weight: 600;
  background: var(--app-blue); color: #fff; }
.mini-btn { margin: 10px 0 2px; }
.dict { border: 1px solid var(--app-card-border); border-radius: 12px; padding: 4px 12px; }
.dict p { display: flex; justify-content: space-between; gap: 12px; margin: 0; padding: 9px 0; font-size: 13px; }
.dict p + p { border-top: 1px solid var(--app-card-border); }
.dict span { flex: none; color: var(--app-text-3); }
.dict b { text-align: right; font-weight: 600; color: var(--app-text-1); }
.dict b.flow-meta { font-weight: 400; color: var(--app-text-3); }
.dict small { color: var(--app-text-3); font-weight: 400; }
</style>
