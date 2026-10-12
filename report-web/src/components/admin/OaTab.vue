<template>
  <div>
    <h4>OA 审批（公章=三级：一级审批人由申请人在行政候选名单内自选（或签，任一人通过即过级）→二级→三级；物资/场地=级数可配。审批人配齐后教师端才能提交；管理员可代审任意节点）</h4>

    <!-- 审批人配置 -->
    <div class="cfg">
      <!-- 批52① 公章：一级=行政候选池（多人）、二级/三级每级多人或签，逐级竖排 -->
      <div class="cfg-row seal-cfg">
        <span class="lbl">公章审批（三级）</span>
        <div class="lv-box">
          <span class="lv">一级候选（行政人员）</span>
          <el-select v-model="seal[0]" multiple filterable clearable placeholder="行政候选名单（可多人，申请人从中自选）" style="width: 240px">
            <el-option v-for="t in teachers" :key="t.id" :label="t.realName" :value="t.id" />
          </el-select>
        </div>
        <div class="lv-box">
          <span class="lv">二级（或签）</span>
          <el-select v-model="seal[1]" multiple filterable clearable placeholder="选择审批人（可多人）" style="width: 240px">
            <el-option v-for="t in teachers" :key="t.id" :label="t.realName" :value="t.id" />
          </el-select>
        </div>
        <div class="lv-box">
          <span class="lv">三级（终审）</span>
          <el-select v-model="seal[2]" multiple filterable clearable placeholder="选择审批人（可多人）" style="width: 240px">
            <el-option v-for="t in teachers" :key="t.id" :label="t.realName" :value="t.id" />
          </el-select>
        </div>
      </div>
      <div class="cfg-row">
        <span class="lbl">物资审批</span>
        <el-select v-model="goodsLevels" style="width: 90px" @change="trimGoods">
          <el-option :value="1" label="1 级" />
          <el-option :value="2" label="2 级" />
          <el-option :value="3" label="3 级" />
        </el-select>
        <template v-for="i in goodsLevels" :key="i">
          <span class="lv">{{ ['一', '二', '三'][i - 1] }}级</span>
          <el-select v-model="goods[i - 1]" filterable clearable placeholder="选择审批人" style="width: 160px">
            <el-option v-for="t in teachers" :key="t.id" :label="t.realName" :value="t.id" />
          </el-select>
        </template>
      </div>
      <div class="cfg-row">
        <span class="lbl">场地审批</span>
        <el-select v-model="venueLevels" style="width: 90px">
          <el-option :value="1" label="1 级" />
          <el-option :value="2" label="2 级" />
          <el-option :value="3" label="3 级" />
        </el-select>
        <template v-for="i in venueLevels" :key="i">
          <span class="lv">{{ ['一', '二', '三'][i - 1] }}级</span>
          <el-select v-model="venue[i - 1]" filterable clearable placeholder="选择审批人" style="width: 160px">
            <el-option v-for="t in teachers" :key="t.id" :label="t.realName" :value="t.id" />
          </el-select>
        </template>
      </div>
      <!-- 批43① 采购：五级固定链（每级多人=或签，任一人通过即过级）+ 抄送人（终态通知，预设不可删） -->
      <div class="cfg-row purchase-cfg">
        <span class="lbl">采购审批（五级）</span>
        <div v-for="(lv, i) in purchase" :key="i" class="lv-box">
          <span class="lv">{{ ['部门负责人', '库存确认(或签)', '主管校领导', '招采中心(或签)', '采购验收'][i] }}</span>
          <el-select v-model="purchase[i]" multiple filterable clearable placeholder="选择审批人（可多人）" style="width: 220px">
            <el-option v-for="t in teachers" :key="t.id" :label="t.realName" :value="t.id" />
          </el-select>
        </div>
        <div class="lv-box">
          <span class="lv">抄送人</span>
          <el-select v-model="purchaseCc" multiple filterable clearable placeholder="终态通知（可多人）" style="width: 220px">
            <el-option v-for="t in teachers" :key="t.id" :label="t.realName" :value="t.id" />
          </el-select>
        </div>
        <el-button type="primary" :loading="saving" @click="saveCfg">保存配置</el-button>
      </div>
    </div>

    <!-- 单据全量 -->
    <div class="bar">
      <el-select v-model="qType" clearable placeholder="类型" style="width: 130px" @change="load">
        <el-option value="SEAL" label="公章使用申请" />
        <el-option value="GOODS" label="物资申领" />
        <el-option value="VENUE" label="场地申请" />
        <el-option value="PURCHASE" label="采购申请" />
      </el-select>
      <el-select v-model="qStatus" clearable placeholder="状态" style="width: 110px" @change="load">
        <el-option value="PENDING" label="待审" />
        <el-option value="APPROVED" label="已通过" />
        <el-option value="REJECTED" label="已驳回" />
        <el-option value="REVOKED" label="已撤回" />
      </el-select>
    </div>
    <el-table :data="rows" size="small">
      <el-table-column prop="id" label="单号" width="70" />
      <el-table-column prop="typeName" label="类型" width="110" />
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="applicantName" label="申请人" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="tagType(row.status)" size="small">{{ stLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="当前节点" width="100">
        <template #default="{ row }">{{ row.status === 'PENDING' ? row.nodeName : '—' }}</template>
      </el-table-column>
      <el-table-column label="发起时间" width="140">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="open(row.id)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 详情/代审 -->
    <el-dialog v-model="dlg" title="单据详情" width="560px">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="类型">{{ detail.typeName }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ detail.title }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.formType === 'SEAL'" label="使用日期">{{ detailJson.useDate || '未指定' }}</el-descriptions-item>
          <template v-if="detail.formType === 'LEAVE'">
            <el-descriptions-item label="请假类型">{{ detailJson.leaveType }}</el-descriptions-item>
            <el-descriptions-item label="起止日期">{{ detailJson.startDate }} ~ {{ detailJson.endDate }}</el-descriptions-item>
            <el-descriptions-item label="事由">{{ detailJson.reason }}</el-descriptions-item>
          </template>
          <template v-if="detail.formType === 'VENUE'">
            <el-descriptions-item label="场地">{{ detailJson.venueName }}</el-descriptions-item>
            <el-descriptions-item label="使用日期">{{ detailJson.useDate }}<template v-if="detailJson.startTime"> {{ detailJson.startTime }}-{{ detailJson.endTime }}</template></el-descriptions-item>
            <el-descriptions-item label="事由">{{ detailJson.reason }}</el-descriptions-item>
          </template>
          <!-- 批43① 采购：基础信息+明细+附件数+库存参考+抄送人 -->
          <template v-if="detail.formType === 'PURCHASE'">
            <el-descriptions-item label="期望交付">{{ detailJson.expectDate }}</el-descriptions-item>
            <el-descriptions-item label="交付地点">{{ detailJson.place }}</el-descriptions-item>
            <el-descriptions-item label="事由">{{ detailJson.reason }}</el-descriptions-item>
            <el-descriptions-item label="附件">{{ detail.photoUrls?.length ? detail.photoUrls.length + ' 张' : '无' }}</el-descriptions-item>
            <el-descriptions-item v-for="(it, i) in detailJson.items" :key="i" :label="`物品 ${i + 1}`">
              {{ it.name }}｜{{ it.spec }}｜数量 {{ it.qty }}<template v-if="it.note">｜{{ it.note }}</template>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.stockMatches?.length" label="库存参考">
              <div v-for="(m, i) in detail.stockMatches" :key="i" class="stock-line">
                {{ m.name }}（{{ m.stock }} {{ m.unit }}<template v-if="m.location"> · {{ m.location }}</template>）匹配「{{ m.item }}」
              </div>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.ccNames?.length" label="抄送人">{{ detail.ccNames.join('、') }}</el-descriptions-item>
          </template>
          <el-descriptions-item v-for="(l, i) in detailLines" :key="i" :label="`物资 ${i + 1}`">
            {{ l.name }} × {{ l.qty }} {{ l.unit }}（{{ l.location || '地点未填' }}）
          </el-descriptions-item>
          <el-descriptions-item label="申请人">{{ detail.applicantName }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ stLabel(detail.status) }}<template v-if="detail.status === 'PENDING'">（当前：{{ detail.nodeName }}，共 {{ detail.levels }} 级）</template>
          </el-descriptions-item>
        </el-descriptions>
        <div class="logs">
          <p v-for="(l, i) in detail.logs" :key="i" class="log">
            <b>{{ l.nodeName }}</b> · {{ l.operatorName }} {{ actLabel(l.action) }}
            <span v-if="l.note">「{{ l.note }}」</span>
            <small>{{ l.createTime }}</small>
          </p>
        </div>
        <div v-if="detail.status === 'PENDING'" class="acts">
          <el-input v-model="opinion" placeholder="代审意见（可选）" style="width: 240px" />
          <el-button type="danger" plain @click="handle('REJECT')">驳回</el-button>
          <el-button type="primary" @click="handle('AGREE')">通过（代审）</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api/http'

const teachers = ref<any[]>([])
// 批52① 公章三级全部多人（一级=行政候选池；二级/三级或签）
const seal = ref<number[][]>([[], [], []])
const goods = ref<(number | undefined)[]>([undefined, undefined, undefined])
const venue = ref<(number | undefined)[]>([undefined, undefined, undefined])
const goodsLevels = ref(1)
const venueLevels = ref(1)
// 批43① 采购：五级或签（每级多人）+ 抄送人
const purchase = ref<number[][]>([[], [], [], [], []])
const purchaseCc = ref<number[]>([])
const saving = ref(false)

const qType = ref('')
const qStatus = ref('')
const rows = ref<any[]>([])
const dlg = ref(false)
const detail = ref<any>(null)
const opinion = ref('')

const detailJson = computed<any>(() => {
  if (!detail.value) return {}
  try { return JSON.parse(detail.value.detail) } catch { return {} }
})
const detailLines = computed(() => (detail.value?.formType === 'GOODS' ? detailJson.value : []))

async function loadCfg() {
  const c = await api<{ sealApprovers: { id: number; name: string }[][]; goodsApprovers: ({ id: number; name: string } | null)[]; venueApprovers: ({ id: number; name: string } | null)[]; goodsLevels: number; venueLevels: number; purchaseApprovers: { id: number; name: string }[][]; purchaseCc: { id: number; name: string }[] }>('/api/admin/oa/config')
  seal.value = [0, 1, 2].map((i) => (c.sealApprovers?.[i] ?? []).map((x) => x.id))
  goods.value = (c.goodsApprovers || []).map((x) => x?.id)
  venue.value = (c.venueApprovers || []).map((x) => x?.id)
  goodsLevels.value = c.goodsLevels || 1
  venueLevels.value = c.venueLevels || 1
  purchase.value = [0, 1, 2, 3, 4].map((i) => (c.purchaseApprovers?.[i] ?? []).map((x) => x.id))
  purchaseCc.value = (c.purchaseCc || []).map((x) => x.id)
}

async function saveCfg() {
  if (seal.value.some((lv) => !lv.length)) { ElMessage.warning('公章三级须每级至少配一人（一级=行政候选名单）'); return }
  if (goods.value.slice(0, goodsLevels.value).some((v) => !v)) { ElMessage.warning('物资审批人须按级数配齐'); return }
  if (venue.value.slice(0, venueLevels.value).some((v) => !v)) { ElMessage.warning('场地审批人须按级数配齐'); return }
  if (purchase.value.some((lv) => !lv.length)) { ElMessage.warning('采购五级审批人须每级至少配一人'); return }
  saving.value = true
  try {
    await api('/api/admin/oa/config', {
      method: 'PUT',
      json: {
        sealApprovers: seal.value, goodsApprovers: goods.value, goodsLevels: goodsLevels.value,
        venueApprovers: venue.value, venueLevels: venueLevels.value,
        purchaseApprovers: purchase.value, purchaseCc: purchaseCc.value,
      },
    })
    ElMessage.success('已保存（教师端立即生效）')
  } finally { saving.value = false }
}

function trimGoods() {
  // 级数调小后多余的选择仅不保存（服务端按级数取前 N 个）
}

async function load() {
  const p = new URLSearchParams()
  if (qType.value) p.set('formType', qType.value)
  if (qStatus.value) p.set('status', qStatus.value)
  rows.value = await api<any[]>(`/api/admin/oa/list?${p}`)
}

async function open(id: number) {
  opinion.value = ''
  detail.value = await api<any>(`/api/oa/${id}`)
  dlg.value = true
}

async function handle(action: string) {
  if (!detail.value) return
  await api(`/api/oa/${detail.value.id}/handle`, { method: 'POST', json: { action, note: opinion.value.trim() } })
  ElMessage.success(action === 'AGREE' ? '已通过' : '已驳回')
  dlg.value = false
  await load()
}

function fmtTime(t?: string) {
  return t ? t.slice(0, 16).replace('T', ' ') : '—'
}
function stLabel(s: string) {
  return ({ PENDING: '待审', APPROVED: '已通过', REJECTED: '已驳回', REVOKED: '已撤回' } as Record<string, string>)[s] || s
}
function tagType(s: string) {
  return ({ PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', REVOKED: 'info' } as Record<string, string>)[s] || 'info'
}
function actLabel(a: string) {
  return ({ SUBMIT: '提交了申请', AGREE: '通过', REJECT: '驳回', REVOKE: '撤回' } as Record<string, string>)[a] || a
}

onMounted(async () => {
  // 全量拉取（服务端 size 上限 1000；排除停用账号——停用者配成审批人会让单据卡死）
  const d = await api<{ records: any[] }>('/api/admin/user/list?page=1&size=1000')
  teachers.value = d.records.filter((u: any) => u.status !== 0)
  await Promise.all([loadCfg(), load()])
})
</script>

<style scoped>
.cfg { border: 1px solid #e8ecf5; border-radius: 10px; padding: 12px 14px; margin: 10px 0; }
.cfg-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 6px 0; }
.lbl { font-size: 13px; color: #606266; font-weight: 600; }
.lv { font-size: 12px; color: #909399; }
/* 批43① 采购五级+抄送 / 批52① 公章三级（多人或签，逐级竖排避免挤成一行） */
.purchase-cfg, .seal-cfg { flex-direction: column; align-items: stretch; border-top: 1px dashed #e8ecf5; padding-top: 10px; }
.purchase-cfg .lbl { flex: none; }
.lv-box { display: flex; align-items: center; gap: 8px; margin: 4px 0; }
.lv-box .lv { flex: none; width: 120px; text-align: right; }
.stock-line { font-size: 12px; color: #67c23a; line-height: 1.8; }
.bar { display: flex; align-items: center; gap: 10px; margin: 12px 0 8px; }
.logs { margin-top: 12px; }
.log { margin: 0; padding: 7px 0; font-size: 13px; border-bottom: 1px dashed #e8ecf5; }
.log b { font-weight: 600; }
.log small { color: #909399; margin-left: 8px; }
.acts { display: flex; gap: 10px; margin-top: 14px; }
</style>
