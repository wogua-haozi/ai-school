<template>
  <div class="app-page feedback">
    <!-- 提交卡 -->
    <div class="app-card tl tex-16 form">
      <div class="app-sec" style="margin: 0 0 10px">意见反馈</div>
      <p class="lead">使用中遇到问题、或有改进建议，欢迎告诉我们；也可在这里报数据错误（如联系方式变更未生效）。</p>
      <van-field v-model="content" type="textarea" rows="3" maxlength="1000" show-word-limit
        placeholder="请描述遇到的问题或建议（最多 1000 字）" class="ta" />
      <van-field v-model="contact" label="联系方式" placeholder="默认取账号手机号，可改" />
      <van-button type="primary" round block class="btn" :loading="sending" :disabled="!content.trim()" @click="submit">
        提交反馈
      </van-button>
    </div>

    <!-- 我的反馈 -->
    <div class="app-card tex-17 list">
      <div class="app-sec" style="margin: 0 0 6px">我的反馈</div>
      <van-empty v-if="!items.length" description="暂无反馈记录" />
      <div v-for="f in items" :key="f.id" class="fb">
        <div class="fb-head">
          <span class="tag" :class="f.status === 1 ? 'done' : 'wait'">{{ f.status === 1 ? '已处理' : '待处理' }}</span>
          <span class="time">{{ fmtTime(f.createTime) }}</span>
        </div>
        <p class="fb-content">{{ f.content }}</p>
        <p v-if="f.status === 1 && f.handleNote" class="fb-note">回复：{{ f.handleNote }}</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { showFailToast, showSuccessToast } from 'vant'
import { api } from '../api/http'

const content = ref('')
const contact = ref('')
const sending = ref(false)
const items = ref<{ id: number; content: string; status: number; handleNote?: string; createTime?: string }[]>([])

async function load() {
  try {
    items.value = await api<any>('/api/feedback/mine')
  } catch { /* 静默 */ }
}

async function submit() {
  if (!content.value.trim()) return
  sending.value = true
  try {
    await api('/api/feedback', { method: 'POST', json: { content: content.value, contact: contact.value || undefined } })
    showSuccessToast('已提交，感谢反馈')
    content.value = ''
    contact.value = ''
    await load()
  } catch (e: any) {
    showFailToast(e?.message || '提交失败')
  } finally {
    sending.value = false
  }
}

function fmtTime(t?: string) {
  return t ? t.slice(0, 16).replace('T', ' ') : ''
}

onMounted(load)
</script>

<style scoped>
.feedback { padding-bottom: 24px; }
.form, .list { margin-top: 12px; padding: 14px; }
.lead { margin: 0 0 10px; font-size: 12.5px; line-height: 1.7; color: var(--app-text-3); }
.ta { border: 1px solid var(--app-card-border); border-radius: 10px; margin-bottom: 10px; }
.btn { margin-top: 12px; height: 40px; font-weight: 600; }
.list { padding-bottom: 8px; }
.fb { padding: 10px 0; border-bottom: 1px dashed var(--app-card-border); }
.fb:last-child { border-bottom: none; }
.fb-head { display: flex; align-items: center; gap: 8px; }
.tag { padding: 1px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; }
.tag.wait { background: #FDF1E2; color: #B45309; }
.tag.done { background: #E7F6EE; color: #059669; }
.time { font-size: 11px; color: var(--app-text-3); }
.fb-content { margin: 6px 0 0; font-size: 13.5px; line-height: 1.6; color: var(--app-text-1); white-space: pre-wrap; }
.fb-note { margin: 6px 0 0; padding: 8px 10px; border-radius: 8px; background: var(--app-paper, #FAF7F0);
  font-size: 12.5px; line-height: 1.6; color: var(--app-text-2); }
</style>
