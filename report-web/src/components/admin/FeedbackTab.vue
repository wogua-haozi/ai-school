<template>
  <div>
    <div class="toolbar">
      <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="() => { page = 1; load() }">
        <el-option label="待处理" :value="0" />
        <el-option label="已处理" :value="1" />
      </el-select>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table :data="records" size="small">
      <el-table-column label="提交人" width="150">
        <template #default="{ row }">
          {{ row.submitterName }}
          <el-tag size="small" type="info">{{ roleLabel(row.role) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="账号 / 联系方式" width="180">
        <template #default="{ row }">
          <div>{{ row.submitterAccount ?? '—' }}</div>
          <div class="sub">{{ row.contact ?? '无联系方式' }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="content" label="反馈内容" min-width="260" show-overflow-tooltip />
      <el-table-column label="时间" width="140">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'warning'" size="small">
            {{ row.status === 1 ? '已处理' : '待处理' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button link type="primary" @click="openHandle(row)">{{ row.status === 1 ? '查看' : '处理' }}</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="total > 20" layout="prev, pager, next" :total="total" :page-size="20"
      :current-page="page" @current-change="(p: number) => { page = p; load() }" style="margin-top: 10px" />

    <!-- 处理/查看 -->
    <el-dialog v-model="dlg" :title="viewing?.status === 1 ? '反馈详情' : '处理反馈'" width="480px">
      <template v-if="viewing">
        <el-descriptions :column="1" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="提交人">{{ viewing.submitterName }}（{{ roleLabel(viewing.role) }}）{{ viewing.submitterAccount ? ' · ' + viewing.submitterAccount : '' }}</el-descriptions-item>
          <el-descriptions-item label="联系方式">{{ viewing.contact ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="内容">{{ viewing.content }}</el-descriptions-item>
          <el-descriptions-item v-if="viewing.handleNote" label="处理说明">{{ viewing.handleNote }}</el-descriptions-item>
        </el-descriptions>
        <template v-if="viewing.status !== 1">
          <el-input v-model="handleNote" type="textarea" :rows="3" placeholder="处理说明（对提交人可见，必填）" />
          <div class="dlg-btns">
            <el-button size="small" @click="handle(0)">退回待处理</el-button>
            <el-button size="small" type="primary" @click="handle(1)">标记已处理</el-button>
          </div>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api/http'

const emit = defineEmits<{ (e: 'handled'): void }>()

const records = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const statusFilter = ref<number | ''>('')
const dlg = ref(false)
const viewing = ref<any>(null)
const handleNote = ref('')

function roleLabel(r: string) {
  return ({ ADMIN: '管理员', LEADER: '领导', HEAD_TEACHER: '班主任', TEACHER: '教师', PARENT: '家长' } as any)[r] ?? r
}

function fmtTime(t?: string) {
  return t ? t.slice(0, 16).replace('T', ' ') : ''
}

async function load() {
  const qs = new URLSearchParams({ page: String(page.value), size: '20' })
  if (statusFilter.value !== '') qs.set('status', String(statusFilter.value))
  const d = await api<{ total: number; records: any[] }>(`/api/admin/feedback/list?${qs}`)
  total.value = d.total
  records.value = d.records
}

function openHandle(row: any) {
  viewing.value = row
  handleNote.value = row.handleNote ?? ''
  dlg.value = true
}

async function handle(status: number) {
  if (status === 1 && !handleNote.value.trim()) {
    ElMessage.warning('处理说明不能为空')
    return
  }
  await api(`/api/admin/feedback/${viewing.value.id}/handle`, {
    method: 'PUT',
    json: { status, handleNote: handleNote.value },
  })
  ElMessage.success(status === 1 ? '已处理' : '已退回待处理')
  dlg.value = false
  await load()
  emit('handled')
}

async function remove(row: any) {
  await ElMessageBox.confirm(`删除「${row.submitterName}」的这条反馈？不可恢复`, '删除反馈', { type: 'warning' })
  await api(`/api/admin/feedback/${row.id}`, { method: 'DELETE' })
  ElMessage.success('已删除')
  await load()
  emit('handled')
}

onMounted(load)
</script>

<style scoped>
.sub { font-size: 12px; color: var(--el-text-color-secondary); }
.dlg-btns { display: flex; justify-content: flex-end; gap: 8px; margin-top: 10px; }
</style>
