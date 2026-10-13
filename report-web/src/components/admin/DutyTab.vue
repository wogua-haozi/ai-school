<template>
  <div>
    <h4>值日排班（当日值日老师可记「班级记分」任意班；管理员/领导不受限。素养评价已放开为全体教职工）</h4>
    <div class="bar">
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" range-separator="至"
        start-placeholder="开始日" end-placeholder="结束日" style="width: 260px" :clearable="false" />
      <el-select v-model="teacherIds" multiple filterable collapse-tags collapse-tags-tooltip
        placeholder="选择教师（可多选）" style="min-width: 220px; flex: 1; max-width: 360px">
        <el-option v-for="t in teachers" :key="t.id" :label="t.realName" :value="t.id" />
      </el-select>
      <el-input v-model="note" placeholder="备注（选填，全程同一备注）" style="width: 190px" />
      <el-button type="primary" :disabled="!teacherIds.length" @click="add">批量加入值班</el-button>
    </div>
    <div class="bar">
      <span class="lbl">按日查看</span>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width: 150px" @change="load" />
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
const todayStr = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
const date = ref(todayStr)
/** 排班日期范围，默认今天~今天（选一周即一次排 7 天） */
const range = ref<[string, string]>([todayStr, todayStr])
const teacherIds = ref<number[]>([])
const note = ref('')
const teachers = ref<any[]>([])
const rows = ref<any[]>([])

async function load() {
  rows.value = await api<any[]>(`/api/admin/duty/list?date=${date.value}`)
}

async function add() {
  if (!teacherIds.value.length || !range.value?.[0]) return
  const r = await api<{ added: number; skipped: number }>('/api/admin/duty', {
    method: 'POST',
    json: { dutyDate: range.value[0], dateEnd: range.value[1], teacherIds: teacherIds.value, note: note.value || null },
  })
  ElMessage.success(r.skipped ? `已排 ${r.added} 人日，跳过已排 ${r.skipped} 人日` : `已排 ${r.added} 人日`)
  note.value = ''
  date.value = range.value[0]
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
