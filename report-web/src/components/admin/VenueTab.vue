<template>
  <div>
    <h4>场地管理（教师端「场地预约」从此处选取；同场地同日同时段仅放行一张申请单，停用场地不可再预约）</h4>
    <div class="bar">
      <el-button type="primary" @click="openEdit()">新增场地</el-button>
    </div>
    <el-table :data="rows" size="small">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column label="照片" width="76">
        <template #default="{ row }">
          <el-image v-if="imgOf(row.id)" :src="imgOf(row.id)" fit="cover"
            :preview-src-list="[imgOf(row.id)!]" preview-teleported style="width: 52px; height: 36px; border-radius: 6px" />
          <span v-else class="no-photo">未上传</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="位置说明" min-width="130">
        <template #default="{ row }">{{ row.location || '—' }}</template>
      </el-table-column>
      <el-table-column label="容纳人数" width="90">
        <template #default="{ row }">{{ row.capacity ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="设备" min-width="150">
        <template #default="{ row }">{{ row.equip || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '可申请' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link :type="row.status === 1 ? 'danger' : 'success'" size="small" @click="toggle(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editDlg" :title="editForm.id ? '编辑场地' : '新增场地'" width="440px">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="editForm.name" placeholder="例如：学术报告厅" /></el-form-item>
        <el-form-item label="位置说明"><el-input v-model="editForm.location" placeholder="例如：弘毅楼一楼" /></el-form-item>
        <el-form-item label="容纳人数"><el-input-number v-model="editForm.capacity" :min="1" /></el-form-item>
        <el-form-item label="设备">
          <el-select v-model="editForm.equips" multiple filterable allow-create default-first-option
            placeholder="选择或输入设备（可多选）" style="width: 100%">
            <el-option v-for="e in EQUIPS" :key="e" :label="e" :value="e" />
          </el-select>
        </el-form-item>
        <!-- 照片仅已有场地可传（新增先保存再传）；上传即生效 -->
        <el-form-item v-if="editForm.id" label="照片">
          <div class="photo-box">
            <el-image v-if="imgOf(editForm.id)" :src="imgOf(editForm.id)" fit="cover"
              :preview-src-list="[imgOf(editForm.id)!]" preview-teleported class="cur" />
            <el-button size="small" :loading="uploading" @click="fileInput?.click()">
              {{ imgOf(editForm.id) ? '更换照片' : '上传照片' }}
            </el-button>
            <input ref="fileInput" type="file" accept="image/jpeg,image/png" hidden @change="onPhoto" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDlg = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, apiForm, fetchBlob } from '../../api/http'

/** 设备清单（批53③ 参考图口径，可自行输入其他设备） */
const EQUIPS = ['投影仪', '投影幕布', '智能电视', '麦克风', '激光笔', '黑板', '饮水机']

const rows = ref<any[]>([])
const editDlg = ref(false)
const editForm = ref<{ id?: number; name: string; location: string; capacity: number | null; equips: string[] }>(
  { name: '', location: '', capacity: null, equips: [] })
const imgs = ref<Record<number, string>>({})
const fileInput = ref<HTMLInputElement>()
const uploading = ref(false)

const imgOf = (id?: number) => (id ? imgs.value[id] : '')

async function load() {
  rows.value = await api<any[]>('/api/admin/venue')
  await Promise.all(rows.value.filter((r) => r.photoUrl && !imgs.value[r.id]).map(async (r) => {
    try { imgs.value[r.id] = URL.createObjectURL(await fetchBlob(r.photoUrl)) } catch { /* 忽略单张 */ }
  }))
}

function openEdit(row?: any) {
  editForm.value = row
    ? { id: row.id, name: row.name, location: row.location, capacity: row.capacity,
        equips: (row.equip || '').split(/[、,，]/).map((s: string) => s.trim()).filter(Boolean) }
    : { name: '', location: '', capacity: null, equips: [] }
  editDlg.value = true
}

async function save() {
  if (!editForm.value.name.trim()) { ElMessage.warning('请填写名称'); return }
  const { equips, ...rest } = editForm.value
  await api('/api/admin/venue', { method: 'POST', json: { ...rest, equip: equips.join('、') } })
  ElMessage.success('已保存')
  editDlg.value = false
  await load()
}

/** 照片选图即传（jpg/png ≤10MB，上传成功即更新场地照片）。
    photoUrl 地址不变（/api/venue/photo/{id}），须先清本地 blob 缓存再重拉，否则预览停在旧图 */
async function onPhoto(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  ;(e.target as HTMLInputElement).value = ''
  if (!f || !editForm.value.id) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('photo', f)
    await apiForm(`/api/admin/venue/${editForm.value.id}/photo`, fd)
    ElMessage.success('照片已更新')
    delete imgs.value[editForm.value.id]
    await load()
  } finally { uploading.value = false }
}

async function toggle(row: any) {
  await api(`/api/admin/venue/${row.id}/status`, { method: 'PUT' })
  await load()
}

onMounted(load)
</script>

<style scoped>
.bar { display: flex; align-items: center; gap: 10px; margin: 10px 0; }
.no-photo { font-size: 12px; color: var(--el-text-color-secondary); }
.photo-box { display: flex; align-items: center; gap: 10px; }
.photo-box .cur { width: 96px; height: 64px; border-radius: 6px; }
</style>
