<template>
  <div>
    <h4>微光时刻管理（教师发布的班级墙记录；家长上传的不进班级墙，归孩子成长档案）</h4>
    <div class="bar">
      <el-select v-model="classId" filterable placeholder="选择班级" style="width: 180px" @change="load">
        <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <span class="tip">最近 50 条</span>
    </div>
    <el-table :data="rows" size="small">
      <el-table-column label="学生" min-width="120">
        <template #default="{ row }">{{ row.students.map((s: any) => s.name).join('、') || '—' }}</template>
      </el-table-column>
      <el-table-column label="场景标签" width="140">
        <template #default="{ row }">
          <el-tag v-for="tg in String(row.sceneTag).split('/')" :key="tg" size="small" style="margin-right: 4px">{{ tg }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="teacherName" label="记录人" width="100" />
      <el-table-column label="备注" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.note || '—' }}</template>
      </el-table-column>
      <el-table-column label="发布时间" width="140">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="70">
        <template #default="{ row }">
          <!-- 批72e：管理员删除不限时（清测试数据/应教师请求撤回）；照片与已发扬长币由后端连带处理 -->
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>{{ classId ? '本班暂无教师发布的微光' : '请先选择班级' }}</template>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api/http'

const classes = ref<any[]>([])
const classId = ref<number>()
const rows = ref<any[]>([])

async function load() {
  rows.value = classId.value
    ? await api<any[]>(`/api/moment/class?classId=${classId.value}&limit=50`)
    : []
}

/** 批72e：管理员删除（复用 DELETE /api/moment/{id}，服务端 ADMIN 不限 30 分钟） */
async function remove(row: any) {
  const names = row.students.map((s: any) => s.name).join('、') || '（无学生）'
  await ElMessageBox.confirm(
    `确定删除 ${row.teacherName} 发布的这条微光（${names}）？照片与已发的扬长币将一并撤销，不可恢复。`,
    '删除微光', { type: 'warning' })
  await api(`/api/moment/${row.id}`, { method: 'DELETE' })
  ElMessage.success('已删除')
  await load()
}

function fmtTime(t?: string) {
  return t ? t.slice(0, 16).replace('T', ' ') : '—'
}

onMounted(async () => {
  classes.value = await api<any[]>('/api/admin/class/list').catch(() => [])
  classId.value = classes.value[0]?.id
  await load()
})
</script>

<style scoped>
.bar { display: flex; align-items: center; gap: 10px; margin: 10px 0 8px; }
.tip { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
