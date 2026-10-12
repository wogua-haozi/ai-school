<template>
  <div class="sys-param">
    <el-alert type="info" :closable="false" show-icon
      title="常用系统参数（保存后即时生效，无需重启）"
      description="当前开放：学生请假分级审批的天数阈值、生活老师登记时段、文明班评比口径切换日。" />

    <el-form label-width="150px" class="form" v-loading="loading">
      <div class="sec">学生请假分级审批</div>
      <el-form-item label="一级审批上限（天）">
        <el-input-number v-model="p.leaveLevel1Days" :min="1" :max="364" />
        <span class="hint">不超过该天数：级长或级部主任一级审批</span>
      </el-form-item>
      <el-form-item label="二级审批上限（天）">
        <el-input-number v-model="p.leaveLevel2Days" :min="2" :max="365" />
        <span class="hint">超过一级且不超过该天数：级长/级部主任 → 学成中心 二级审批</span>
      </el-form-item>
      <el-form-item label="系统受理上限（天）">
        <el-input-number v-model="p.leaveMaxDays" :min="2" :max="365" />
        <span class="hint">超过二级且不超过该天数：级长/级部主任 → 学成中心 → 书记 三级审批；再长请走纸质申请</span>
      </el-form-item>
      <el-form-item label="生活老师登记时段">
        <el-input v-model="p.leaveDormWindows" placeholder="如 12:00-14:15,21:30-07:30" style="width: 240px" />
        <span class="hint">生活老师仅可在此时段登记请假（多段英文逗号分隔，跨午夜如 21:30-07:30；清空 = 不限时段）</span>
      </el-form-item>

      <div class="sec">文明班评比</div>
      <el-form-item label="口径切换日">
        <el-date-picker v-model="p.civilityEvalFrom" type="date" value-format="YYYY-MM-DD"
          placeholder="留空 = 仍按旧打分口径" clearable style="width: 180px" />
        <span class="hint">自该日起：文明班分 = 每日基础 120 + 素养评价分直加；此前的历史打分仍计入排名</span>
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        <span class="hint">须满足 1 ≤ 一级审批天数 &lt; 二级审批天数 ≤ 上限天数 ≤ 365</span>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api/http'

const loading = ref(false)
const saving = ref(false)
const p = ref<any>({})

async function load() {
  loading.value = true
  try {
    p.value = await api<any>('/api/admin/sys-param')
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    p.value = await api<any>('/api/admin/sys-param', {
      method: 'PUT',
      json: {
        leaveLevel1Days: p.value.leaveLevel1Days,
        leaveLevel2Days: p.value.leaveLevel2Days,
        leaveMaxDays: p.value.leaveMaxDays,
        leaveDormWindows: p.value.leaveDormWindows || '',
        civilityEvalFrom: p.value.civilityEvalFrom || '',
      },
    })
    ElMessage.success('已保存，即时生效')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.sys-param { max-width: 720px; }
.sec { margin: 14px 0 10px; padding-left: 8px; border-left: 3px solid var(--el-color-primary); font-weight: 600; }
.hint { margin-left: 10px; font-size: 12px; color: var(--el-text-color-secondary); }
.form { margin-top: 4px; }
</style>
