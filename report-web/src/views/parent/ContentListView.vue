<template>
  <div class="app-page p-contents" :class="{ 'tab-page': isTabView }">
    <van-pull-refresh v-model="refreshing" @refresh="reload" success-text="已刷新">
    <div class="app-sec list-head">
      <span>{{ typeLabel }} · {{ rows.length }} 条</span>
      <van-button v-if="!isParent" size="small" round type="primary" @click="openPublish">发布通知</van-button>
    </div>

    <van-skeleton v-if="loading" :row="6" style="padding: 14px" />
    <div v-else-if="!rows.length" class="app-card empty">暂无通知公告</div>

    <!-- 内容卡（第一版卡片语言：封面通栏图 + 标题 + 摘要，tex 纹理轮换） -->
    <div v-for="(it, i) in rows" :key="it.id" class="app-card item" :class="tex(i)"
      @click="$router.push(isParent ? `/p/content/${it.id}` : `/notices/${it.id}`)">
      <img v-if="coverCache[it.id]" class="cover" :src="coverCache[it.id]" alt="" />
      <div class="body">
        <p class="title">
          {{ it.title }}
          <span v-if="it.scope === 'CLASS'" class="app-chip cls">{{ it.className ?? '本班' }}</span>
          <span v-else-if="it.scope === 'GRADE'" class="app-chip cls">{{ it.gradeName || '年级' }}</span>
          <span v-else-if="it.scope === 'STAFF'" class="app-chip cls">教师</span>
          <span v-if="it.mine" class="app-chip mine-chip">我发布</span>
        </p>
        <p v-if="it.content" class="brief">{{ it.content }}</p>
        <p class="meta">{{ fmtDate(it.publishTime) }}</p>
      </div>
    </div>

    <p class="app-foot">石实实验学校 · 石实SHINE</p>
    </van-pull-refresh>

    <!-- 批66② 校园通知发布（全体教职工）：标题+正文+范围（全体教师/指定年级） -->
    <van-popup v-model:show="pubOpen" position="bottom" round :style="{ maxHeight: '86%' }" class="pub-pop">
      <div class="pub-head">发布校园通知</div>
      <van-cell-group inset>
        <van-field v-model="pubForm.title" label="标题" placeholder="如：七年级一检表彰会安排" maxlength="200" />
        <van-field v-model="pubForm.content" label="正文" type="textarea" rows="4" autosize
          placeholder="支持换行；老师们会在消息中心收到提醒" />
        <van-field name="scope" label="范围">
          <template #input>
            <van-radio-group v-model="pubForm.scope" direction="horizontal">
              <van-radio name="STAFF">全体教师</van-radio>
              <van-radio name="GRADE">指定年级</van-radio>
            </van-radio-group>
          </template>
        </van-field>
        <van-field v-if="pubForm.scope === 'GRADE'" is-link readonly :model-value="gradeLabel"
          label="年级" placeholder="选择年级" @click="gradeOpen = true" />
      </van-cell-group>
      <div class="pub-foot">
        <van-button block round type="primary" :loading="pubSaving" :disabled="!canPublish" @click="submitPublish">
          发布（可见老师即时收到提醒）
        </van-button>
      </div>
    </van-popup>

    <van-popup v-model:show="gradeOpen" position="bottom" round>
      <van-picker title="选择年级" :columns="gradeColumns" @confirm="onPickGrade" @cancel="gradeOpen = false" />
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { showSuccessToast } from 'vant'
import { api, fetchBlob } from '../../api/http'
import { useAuthStore } from '../../stores/auth'
import { texAt } from '../../utils/tex'

/* 通知公告列表页（批40a 内容型扬长课程下线，type 恒 NOTICE）；
   批37 教师端入口同组件双轨：家长走 /api/parent（含班级范围），其他角色走 /api/content/notices（全校范围）；
   批37 追加：家长端页升底部 Tab（ptab 壳无标题栏，自补状态栏安全间距）；
   批66② 教师侧可发布校园通知（全体教师/指定年级），mine=自己发布的带标记 */
const props = defineProps<{ type: 'NOTICE' }>()
const isParent = useAuthStore().role === 'PARENT'
const isTabView = useRoute().meta.layout === 'ptab'
const typeLabel = '通知公告'

interface Item {
  id: number; title: string; coverUrl: string | null; videoUrl: string | null
  content: string | null; scope: string; className: string | null; gradeName: string | null
  publishTime: string | null; mine?: boolean
}

const rows = ref<Item[]>([])
const loading = ref(true)
const refreshing = ref(false)
const coverCache = reactive<Record<number, string>>({})

// 批52③ 37 槽轮换池：本页基准 7（无静态位，轮换从基准起）
const tex = (i: number) => texAt(7 + i)
function fmtDate(t?: string | null) {
  return t ? t.slice(0, 10) : ''
}

async function load() {
  loading.value = true
  try {
    rows.value = await api<Item[]>(isParent ? `/api/parent/contents?type=${props.type}` : `/api/content/notices?type=${props.type}`)
    for (const it of rows.value) {
      if (it.coverUrl && !coverCache[it.id]) {
        try {
          const blob = await fetchBlob(`/api/content/file/${it.id}`)
          coverCache[it.id] = URL.createObjectURL(blob)
        } catch { /* 封面缺失不阻塞列表 */ }
      }
    }
  } finally {
    loading.value = false
  }
}

async function reload() {
  try { await load() } finally { refreshing.value = false }
}

/* 两路由共用本组件，vue-router 切换时复用实例（onMounted 不重跑）——type 变化须重拉 */
watch(() => props.type, () => load())
onMounted(load)

/* ───────── 批66② 发布校园通知（教师侧） ───────── */
const pubOpen = ref(false)
const pubSaving = ref(false)
const gradeOpen = ref(false)
const grades = ref<{ id: number; name: string }[]>([])
const pubForm = ref<{ title: string; content: string; scope: 'STAFF' | 'GRADE'; gradeId?: number }>({
  title: '', content: '', scope: 'STAFF',
})
const gradeColumns = computed(() => grades.value.map((g) => ({ text: g.name, value: g.id })))
const gradeLabel = computed(() =>
  grades.value.find((g) => g.id === pubForm.value.gradeId)?.name ?? '')
const canPublish = computed(() => pubForm.value.title.trim() &&
  (pubForm.value.scope !== 'GRADE' || pubForm.value.gradeId))

function openPublish() {
  pubForm.value = { title: '', content: '', scope: 'STAFF' }
  pubOpen.value = true
  if (!grades.value.length) {
    api<{ id: number; name: string }[]>('/api/meta/grades').then((gs) => (grades.value = gs)).catch(() => {})
  }
}

function onPickGrade(g: { selectedValues?: number[]; selectedOptions?: { value: number }[] }) {
  const picked = g.selectedOptions?.[0]?.value ?? g.selectedValues?.[0]
  if (picked) pubForm.value.gradeId = picked
  gradeOpen.value = false
}

async function submitPublish() {
  pubSaving.value = true
  try {
    await api('/api/content/notice', {
      method: 'POST',
      json: {
        title: pubForm.value.title.trim(),
        content: pubForm.value.content.trim() || null,
        scope: pubForm.value.scope,
        gradeId: pubForm.value.scope === 'GRADE' ? pubForm.value.gradeId : null,
      },
    })
    pubOpen.value = false
    showSuccessToast('已发布，可见老师已收到提醒')
    await load()
  } finally {
    pubSaving.value = false
  }
}
</script>

<style scoped>
/* C 风格页面：结构复用第一版全局类，点缀色 C 令牌 */
.empty { text-align: center; color: var(--app-text-2); font-size: 13px; padding: 26px 0; }

.item { padding: 0; overflow: hidden; margin-bottom: 12px; cursor: pointer; }
.item:active { opacity: .85; }
.cover { display: block; width: 100%; aspect-ratio: 16 / 9; object-fit: cover; }
.body { padding: 12px 14px 10px; }
.title { margin: 0; font-size: 15px; font-weight: 600; color: var(--app-text-1);
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.title .app-chip { flex: none; }
.cls { color: var(--shine-navy); background: #fff; border: 1px solid var(--shine-line); }
.mine-chip { color: #9A6B00; background: #FFF7E6; border: 1px solid #F3D19E; }
.brief { margin: 6px 0 0; font-size: 12px; color: var(--app-text-3);
  line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.meta { margin: 8px 0 0; font-size: 11px; color: var(--app-text-3); }
/* 底部 Tab 形态（ptab 壳无标题栏）：自补状态栏安全间距 */
.tab-page { padding-top: calc(6px + var(--sat)); }

.list-head { display: flex; align-items: center; justify-content: space-between; }
.pub-head { padding: 16px 16px 8px; font-size: 16px; font-weight: 700; color: var(--app-text-1); }
.pub-foot { padding: 10px 16px calc(14px + var(--sab, 0px)); }
</style>
