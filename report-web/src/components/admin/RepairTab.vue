<template>
  <div>
    <!-- 批65：授权白名单自助维护（批63b 上线时 SQL 直配、学校改不了，现开放编辑；保存即生效） -->
    <el-card class="wl" shadow="never">
      <template #header>
        <b>授权白名单</b>
        <span class="wl-hint">维修工可接单维修；后勤审批人负责审批；完成后自动通知抄送人</span>
      </template>
      <el-form label-width="90px" size="small" v-loading="wlLoading">
        <el-form-item label="后勤审批">
          <el-select v-model="wl.logistics" multiple filterable clearable placeholder="选择后勤审批人"
            style="width: 100%">
            <el-option v-for="t in teachers" :key="t.id" :label="`${t.realName}（${t.id}）`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="维修工">
          <el-select v-model="wl.repairer" multiple filterable clearable placeholder="选择维修工（可接单维修）"
            style="width: 100%">
            <el-option v-for="t in teachers" :key="t.id" :label="`${t.realName}（${t.id}）`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="完成抄送">
          <el-select v-model="wl.cc" multiple filterable clearable placeholder="选择抄送人（维修完成通知）"
            style="width: 100%">
            <el-option v-for="t in teachers" :key="t.id" :label="`${t.realName}（${t.id}）`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="wlSaving" @click="saveWl">保存白名单</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <div class="bar">
      <el-select v-model="qStatus" clearable placeholder="状态" style="width: 130px" @change="load">
        <el-option value="PENDING" label="待审批" />
        <el-option value="APPROVED" label="待接单" />
        <el-option value="CLAIMED" label="维修中" />
        <el-option value="DONE" label="已完成" />
        <el-option value="REJECTED" label="不予受理" />
        <el-option value="CANCELED" label="已撤回" />
      </el-select>
    </div>
    <el-table :data="rows" size="small">
      <el-table-column prop="id" label="单号" width="70" />
      <el-table-column prop="location" label="地点/设施" min-width="150" show-overflow-tooltip />
      <el-table-column prop="description" label="故障描述" min-width="200" show-overflow-tooltip />
      <el-table-column prop="reporterName" label="报修人" width="90" />
      <el-table-column label="审批人" width="90">
        <template #default="{ row }">{{ row.approverName || '—' }}</template>
      </el-table-column>
      <el-table-column label="接单人" width="90">
        <template #default="{ row }">{{ row.claimerName || '—' }}</template>
      </el-table-column>
      <el-table-column label="凭证" width="60">
        <template #default="{ row }">{{ row.photoCount || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="tagType(row.status)" size="small">{{ stLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="报修时间" width="140">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" :width="qStatus === 'PENDING' || !qStatus ? 170 : 120">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="open(row.id)">详情</el-button>
          <el-button v-if="row.status === 'PENDING'" link type="success" size="small" @click="open(row.id)">处理</el-button>
          <el-button v-if="row.status === 'CANCELED'" link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 详情/处理 -->
    <el-dialog v-model="dlg" title="报修详情" width="560px">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="地点/设施">{{ detail.location }}</el-descriptions-item>
          <el-descriptions-item label="故障描述">{{ detail.description }}</el-descriptions-item>
          <el-descriptions-item label="报修人">{{ detail.reporterName }}</el-descriptions-item>
          <el-descriptions-item label="报修时间">{{ fmtTime(detail.createTime) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.status !== 'PENDING'" label="流转进度">
            <template v-if="detail.approverName">审批：{{ detail.approverName }} · {{ fmtTime(detail.approveTime) }}<template v-if="detail.approveNote">（{{ detail.approveNote }}）</template><br /></template>
            <template v-if="detail.claimerName">接单：{{ detail.claimerName }} · {{ fmtTime(detail.claimTime) }}<br /></template>
            <template v-if="detail.status === 'DONE' || detail.status === 'REJECTED'">结果：{{ stLabel(detail.status) }} · {{ detail.handlerName }} · {{ fmtTime(detail.handleTime) }}
              <template v-if="detail.handleNote">（{{ detail.handleNote }}）</template>
            </template>
            <template v-else>当前：{{ stLabel(detail.status) }}</template>
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="imgs.length" class="shots">
          <el-image v-for="(u, i) in imgs" :key="i" :src="u" fit="cover"
            :preview-src-list="imgs" :initial-index="i" class="shot" />
        </div>
        <div v-if="detail.status === 'PENDING'" class="acts">
          <el-input v-model="note" placeholder="处理说明（可选）" style="width: 240px" />
          <el-button type="warning" plain @click="handle('REJECTED')">不予受理</el-button>
          <el-button type="primary" @click="handle('DONE')">标记完成</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fetchBlob } from '../../api/http'

const qStatus = ref('')
const rows = ref<any[]>([])
const dlg = ref(false)
const detail = ref<any>(null)
const note = ref('')
const imgs = ref<string[]>([])

const teachers = ref<{ id: number; realName: string }[]>([])
const wl = ref<{ logistics: number[]; repairer: number[]; cc: number[] }>({ logistics: [], repairer: [], cc: [] })
const wlLoading = ref(false)
const wlSaving = ref(false)

/** uid 逗号串 → 数字数组（空串=空组） */
function splitUids(s?: string) {
  return (s ?? '').split(',').map((x) => Number(x.trim())).filter((x) => Number.isFinite(x) && x > 0)
}

async function loadWl() {
  wlLoading.value = true
  try {
    const d = await api<{ logisticsUids: string; repairerUids: string; ccUids: string }>(
      '/api/admin/repair/whitelist')
    wl.value = { logistics: splitUids(d.logisticsUids), repairer: splitUids(d.repairerUids), cc: splitUids(d.ccUids) }
  } finally {
    wlLoading.value = false
  }
}

/** 保存白名单（后端校验 uid 均为在职教职工，错号整单拒绝） */
async function saveWl() {
  wlSaving.value = true
  try {
    const d = await api<{ logisticsUids: string; repairerUids: string; ccUids: string }>(
      '/api/admin/repair/whitelist', {
        method: 'PUT',
        json: {
          logisticsUids: wl.value.logistics.join(','),
          repairerUids: wl.value.repairer.join(','),
          ccUids: wl.value.cc.join(','),
        },
      })
    wl.value = { logistics: splitUids(d.logisticsUids), repairer: splitUids(d.repairerUids), cc: splitUids(d.ccUids) }
    ElMessage.success('白名单已保存，即时生效')
  } finally {
    wlSaving.value = false
  }
}

async function load() {
  const p = new URLSearchParams()
  if (qStatus.value) p.set('status', qStatus.value)
  rows.value = await api<any[]>(`/api/admin/repair/list?${p}`)
}

async function open(id: number) {
  note.value = ''
  detail.value = await api<any>(`/api/admin/repair/${id}`)
  imgs.value = []
  dlg.value = true
  for (const u of detail.value.photoUrls) {
    try {
      const blob = await fetchBlob(u)
      imgs.value.push(URL.createObjectURL(blob))
    } catch { /* 单张缺失不阻塞 */ }
  }
}

/** 批67 B4：删除已撤回的报修单（仅 CANCELED 行可见；在办/办结单据留痕不可删） */
async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除报修单 #${row.id}（${row.location}）？删除后不可恢复。`, '删除报修单', { type: 'warning' })
  await api(`/api/admin/repair/${row.id}`, { method: 'DELETE' })
  ElMessage.success('已删除')
  await load()
}

async function handle(status: string) {
  if (!detail.value) return
  await api(`/api/admin/repair/${detail.value.id}/handle`, { method: 'PUT', json: { status, note: note.value.trim() } })
  ElMessage.success(status === 'DONE' ? '已标记完成' : '已标记不予受理')
  dlg.value = false
  await load()
}

function fmtTime(t?: string) {
  return t ? t.slice(0, 16).replace('T', ' ') : '—'
}
function stLabel(s: string) {
  return ({ PENDING: '待审批', APPROVED: '待接单', CLAIMED: '维修中', DONE: '已完成',
    REJECTED: '不予受理', CANCELED: '已撤回' } as Record<string, string>)[s] || s
}
function tagType(s: string) {
  return ({ PENDING: 'warning', APPROVED: 'warning', CLAIMED: '', DONE: 'success',
    REJECTED: 'info', CANCELED: 'info' } as Record<string, string>)[s] || 'info'
}

onMounted(() => {
  load()
  loadWl()
  api<{ id: number; realName: string }[]>('/api/meta/teachers').then((ts) => { teachers.value = ts }).catch(() => { /* 下拉加载失败不阻塞报修列表 */ })
})
</script>

<style scoped>
.wl { margin-bottom: 14px; }
.wl-hint { margin-left: 10px; font-size: 12px; font-weight: 400; color: var(--el-text-color-secondary); }
.bar { display: flex; align-items: center; gap: 10px; margin: 12px 0 8px; }
.shots { display: flex; gap: 10px; margin-top: 14px; flex-wrap: wrap; }
.shot { width: 120px; height: 90px; border-radius: 8px; }
.acts { display: flex; gap: 10px; margin-top: 14px; }
</style>
