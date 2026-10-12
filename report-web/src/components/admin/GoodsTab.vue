<template>
  <div>
    <h4>物资与出入库（教师端「行政办公-物资申领」从此处选取；出库流水=谁、什么时候、在哪里、拿走了什么）</h4>
    <div class="bar">
      <el-button type="primary" @click="openEdit()">新增物资</el-button>
    </div>
    <el-table :data="rows" size="small">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="unit" label="单位" width="70" />
      <el-table-column prop="stock" label="库存" width="80" />
      <el-table-column prop="location" label="存放地点" min-width="130">
        <template #default="{ row }">{{ row.location || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '可申领' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openStock(row)">入库</el-button>
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link :type="row.status === 1 ? 'danger' : 'success'" size="small" @click="toggle(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑 -->
    <el-dialog v-model="editDlg" :title="editForm.id ? '编辑物资' : '新增物资'" width="420px">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="editForm.name" placeholder="例如：A4 纸" /></el-form-item>
        <el-form-item label="单位"><el-input v-model="editForm.unit" placeholder="件/包/盒（默认件）" /></el-form-item>
        <el-form-item label="存放地点"><el-input v-model="editForm.location" placeholder="例如：德育处仓库" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDlg = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 入库 -->
    <el-dialog v-model="stockDlg" :title="`入库：${stockTarget?.name ?? ''}`" width="420px">
      <el-form label-width="80px">
        <el-form-item label="数量"><el-input-number v-model="stockQty" :min="1" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="stockNote" placeholder="例如：9 月采购入库" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stockDlg = false">取消</el-button>
        <el-button type="primary" @click="stockIn">确认入库</el-button>
      </template>
    </el-dialog>

    <!-- 流水 -->
    <div class="sec">出入库流水（最近 200 条）</div>
    <el-table :data="flows" size="small">
      <el-table-column prop="createTime" label="时间" width="160" />
      <el-table-column label="方向" width="70">
        <template #default="{ row }">
          <el-tag :type="row.direction === 'IN' ? 'success' : 'warning'" size="small">{{ row.direction === 'IN' ? '入库' : '出库' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="物资" min-width="130">
        <template #default="{ row }">{{ row.goodsName }} × {{ row.qty }}{{ row.unit }}</template>
      </el-table-column>
      <el-table-column prop="applicantName" label="申领人" width="90">
        <template #default="{ row }">{{ row.applicantName || '—' }}</template>
      </el-table-column>
      <el-table-column prop="operatorName" label="经手人" width="90" />
      <el-table-column prop="location" label="地点" min-width="110">
        <template #default="{ row }">{{ row.location || '—' }}</template>
      </el-table-column>
      <el-table-column prop="note" label="备注" min-width="120">
        <template #default="{ row }">{{ row.note || '—' }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api/http'

const rows = ref<any[]>([])
const flows = ref<any[]>([])
const editDlg = ref(false)
const editForm = ref<{ id?: number; name: string; unit: string; location: string }>({ name: '', unit: '', location: '' })
const stockDlg = ref(false)
const stockTarget = ref<any>(null)
const stockQty = ref(1)
const stockNote = ref('')

async function load() {
  rows.value = await api<any[]>('/api/admin/goods')
  flows.value = await api<any[]>('/api/admin/goods/flow')
}

function openEdit(row?: any) {
  editForm.value = row
    ? { id: row.id, name: row.name, unit: row.unit, location: row.location }
    : { name: '', unit: '', location: '' }
  editDlg.value = true
}

async function save() {
  if (!editForm.value.name.trim()) { ElMessage.warning('请填写名称'); return }
  await api('/api/admin/goods', { method: 'POST', json: editForm.value })
  ElMessage.success('已保存')
  editDlg.value = false
  await load()
}

async function toggle(row: any) {
  await api(`/api/admin/goods/${row.id}/status`, { method: 'PUT' })
  await load()
}

function openStock(row: any) {
  stockTarget.value = row
  stockQty.value = 1
  stockNote.value = ''
  stockDlg.value = true
}

async function stockIn() {
  await api('/api/admin/goods/stock', {
    method: 'POST',
    json: { goodsId: stockTarget.value.id, qty: stockQty.value, note: stockNote.value.trim() || null },
  })
  ElMessage.success('已入库')
  stockDlg.value = false
  await load()
}

onMounted(load)
</script>

<style scoped>
.bar { display: flex; align-items: center; gap: 10px; margin: 10px 0; }
.sec { margin: 18px 0 8px; font-size: 14px; font-weight: 600; color: #303133; }
</style>
