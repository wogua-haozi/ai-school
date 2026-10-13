<template>
  <div>
    <h4>值日排班（当日值日老师可记「班级记分」任意班；管理员/领导不受限。素养评价已放开为全体教职工）</h4>
    <div class="bar">
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width: 150px" @change="load" />
      <el-select v-model="teacherId" filterable placeholder="选择教师" style="width: 200px">
        <el-option v-for="t in teachers" :key="t.id" :label="t.realName" :value="t.id" />
      </el-select>
      <el-input v-model="note" placeholder="备注（选填）" style="width: 180px" />
      <el-button type="primary" :disabled="!teacherId" @click="add">加入当日值班</el-button>
    </div>
    <el-table :data="rows" size="small">
      <el-table-column prop="dutyDate" label="值班日" width="120" />
      <el-table-column prop="teacherName" label="教师" min-width="120" />
      <el-table-column prop="note" label="备注" min-width="160">
        <template #default="{ row }">{{ row.note ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="danger" size="small" @click="remove(row)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <p class="hint" style="margin-top: 10px">
      口径=操作发生日：教师在当天值日即可记班级记分（记分日期可补录近 31 天，权限按当天是否值日判定）。
    </p>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api/http'

// 本地时区的今天（toISOString 是 UTC，早 8 点前"今天"会落到昨天）
const today = new Date()
const date = ref(`${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`)
const teacherId = ref<number>()
const note = ref('')
const teachers = ref<any[]>([])
const rows = ref<any[]>([])

async function load() {
  rows.value = await api<any[]>(`/api/admin/duty/list?date=${date.value}`)
}

async function add() {
  if (!teacherId.value) return
  await api('/api/admin/duty', { method: 'POST', json: { dutyDate: date.value, teacherId: teacherId.value, note: note.value || null } })
  ElMessage.success('已加入值班名单')
  note.value = ''
  await load()
}

async function remove(row: any) {
  await api(`/api/admin/duty/${row.id}`, { method: 'DELETE' })
  ElMessage.success('已移除')
  await load()
}

onMounted(async () => {
  const d = await api<{ records: any[] }>('/api/admin/user/list?page=1&size=200')
  teachers.value = d.records
  await load()
})
</script>

<style scoped>
.bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 10px 0; }
.lbl { font-size: 13px; color: #606266; }
</style>
