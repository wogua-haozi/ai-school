<template>
  <div class="app-page l-approve">
    <van-pull-refresh v-model="refreshing" @refresh="reload" success-text="已刷新">
    <div class="app-sec">待审批 · {{ rows.length }} 项</div>

    <van-skeleton v-if="loading" :row="6" style="padding: 14px" />
    <div v-else-if="!rows.length" class="app-card empty">
      暂无待审批申请<br><span>新建管理员/领导账号或教师升级时，将在此审批</span>
    </div>

    <!-- 每条一卡（tex 纹理轮换，第一版卡片语言；批2-5 双人审批） -->
    <div v-for="(r, i) in rows" :key="r.id" class="app-card req" :class="tex(i)">
      <div class="r-head">
        <span class="r-avatar">{{ r.realName?.charAt(0) ?? '?' }}</span>
        <div class="r-info">
          <p class="r-name">
            {{ r.realName }}
            <span class="app-chip" :class="r.reqType === 'CREATE' ? 'c-create' : 'c-up'">
              {{ r.reqType === 'CREATE' ? '新账号' : '角色升级' }}
            </span>
          </p>
          <p class="r-meta">{{ r.username }} · {{ r.requesterName }} 发起</p>
        </div>
      </div>
      <p class="r-target">
        {{ r.reqType === 'CREATE' ? `新账号 · ${r.targetRoleName}` : `${r.fromRoleName} → ${r.targetRoleName}` }}
        <span class="r-time">{{ fmtTime(r.createTime) }}</span>
      </p>
      <div class="r-acts">
        <van-button size="small" color="#A8232B" :loading="acting === r.id" @click="approve(r)">通过</van-button>
        <van-button size="small" plain color="#A8232B" @click="reject(r)">拒绝</van-button>
      </div>
    </div>

    <p class="app-foot">石实实验学校 · 石实SHINE</p>
    </van-pull-refresh>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { showConfirmDialog, showSuccessToast, showToast } from 'vant'
import { api } from '../../api/http'
import { texAt } from '../../utils/tex'

interface ReqRow {
  id: number; userId: number; username: string; realName: string
  reqType: 'CREATE' | 'UPGRADE'; targetRoleName: string; fromRoleName: string | null
  requesterName: string; createTime: string
}

const rows = ref<ReqRow[]>([])
const loading = ref(true)
const refreshing = ref(false)
const acting = ref<number | null>(null)

// 批52③ 37 槽轮换池：本页基准 29（无静态位，轮换从基准起）
const tex = (i: number) => texAt(29 + i)
function fmtTime(t: string) {
  return t.slice(0, 16).replace('T', ' ')
}

async function load() {
  loading.value = true
  try { rows.value = await api<ReqRow[]>('/api/role-request/list') }
  finally { loading.value = false }
}

async function reload() {
  try { await load() } finally { refreshing.value = false }
}

/** 通过：新账号启用 / 升级生效；发起人不能自批（全校唯一审批人时例外，后端校验） */
async function approve(r: ReqRow) {
  try {
    await showConfirmDialog({
      title: '确认审批',
      message: `通过「${r.realName}」的${r.reqType === 'CREATE' ? '新账号' : '升级为' + r.targetRoleName}申请？`,
    })
  } catch { return /* 取消 */ }
  acting.value = r.id
  try {
    await api(`/api/role-request/${r.id}/approve`, { method: 'PUT' })
    showSuccessToast('已通过')
    await load()
  } catch (e: any) {
    showToast(e?.message || '操作失败')
  } finally { acting.value = null }
}

/** 拒绝：新号删除 / 升级角色不动（自动还原） */
async function reject(r: ReqRow) {
  try {
    await showConfirmDialog({
      title: '拒绝申请',
      message: `拒绝「${r.realName}」的申请？${r.reqType === 'CREATE' ? '该账号将被删除。' : '该教师角色保持不变。'}`,
    })
  } catch { return /* 取消 */ }
  acting.value = r.id
  try {
    await api(`/api/role-request/${r.id}/reject`, { method: 'PUT', json: { note: null } })
    showSuccessToast('已拒绝')
    await load()
  } catch (e: any) {
    showToast(e?.message || '操作失败')
  } finally { acting.value = null }
}

onMounted(load)
</script>

<style scoped>
.empty { padding: 30px 0; text-align: center; color: var(--app-text-3); font-size: 13px; line-height: 1.8; }
.empty span { font-size: 11px; }

.req { padding: 14px 16px; }
.r-head { display: flex; align-items: center; gap: 10px; }
.r-avatar { flex: none; width: 40px; height: 40px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: var(--shine-navy); color: var(--shine-gold, #C9A227); font-size: 17px; font-weight: 700; }
.r-info { flex: 1; min-width: 0; }
.r-name { margin: 0; font-size: 15px; font-weight: 600; color: var(--app-text-1); }
.c-create { background: rgba(31, 42, 68, .08); color: var(--shine-navy, #1F2A44); }
.c-up { background: var(--shine-red-soft, rgba(168, 35, 43, .08)); color: var(--shine-red, #A8232B); }
.r-meta { margin: 4px 0 0; font-size: 12px; color: var(--app-text-3); }
.r-target { margin: 10px 0 0; font-size: 13px; color: var(--app-text-2);
  background: var(--shine-bg, #F7F5F0); border-radius: 8px; padding: 8px 10px; }
.r-time { float: right; font-size: 11px; color: var(--app-text-3); }
.r-acts { display: flex; justify-content: flex-end; gap: 10px; margin-top: 12px; }
</style>
