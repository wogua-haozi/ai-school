<template>
  <div class="app-page thlist" :class="{ tall: filtered.length }">
    <!-- 教师筛选条（金色荣誉主题，同微光列表顶卡） -->
    <div class="app-card tl gold tex-27 top">
      <div class="chips">
        <button class="chip" :class="{ on: teacherId === undefined }" type="button"
          @click="teacherId = undefined">全部<span class="cnt">{{ honors.length }}</span></button>
        <button v-for="t in teachers" :key="t.teacherId" class="chip"
          :class="{ on: teacherId === t.teacherId }" type="button" @click="teacherId = t.teacherId">
          <span class="chip-ava" :style="{ background: avaColor(t.realName) }">
            <img v-if="avatars[t.teacherId]" :src="avatars[t.teacherId]" alt="">
            <template v-else>{{ t.realName.charAt(0) }}</template>
          </span>
          {{ t.realName }}<span class="cnt">{{ t.count }}</span>
        </button>
      </div>
    </div>

    <!-- 成就墙：2 列证书卡，图上级别胶囊，图下奖项/教师/日期 -->
    <div v-if="filtered.length" class="wall">
      <div v-for="h in filtered" :key="h.id" class="cell app-card">
        <div class="ph">
          <MomentPhoto :url="h.photoUrl" @tap="(src) => showImagePreview({ images: [src] })" />
          <span v-if="h.level" class="p-tag">{{ h.level }}</span>
        </div>
        <div class="info">
          <p class="name">{{ h.name }}</p>
          <p class="meta">{{ h.teacherName }}<template v-if="h.honorDate"> · {{ h.honorDate }}</template></p>
          <p v-if="h.issuer" class="issuer">{{ h.issuer }}</p>
        </div>
      </div>
    </div>
    <div v-else-if="!loading" class="app-card empty-card">
      <van-empty image-size="88" description="还没有教师成就" />
      <van-button round type="primary" class="go-add" @click="$router.push('/teacher-honor/new')">
        <van-icon name="plus" /> 记下第一份成就
      </van-button>
    </div>

    <!-- 吸底记录钮 -->
    <button v-if="filtered.length" class="fab-add" type="button" @click="$router.push('/teacher-honor/new')">
      <van-icon name="plus" /> 记录成就
    </button>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { showImagePreview } from 'vant'
import { api, fetchBlob } from '../api/http'
import MomentPhoto from '../components/MomentPhoto.vue'

interface HonorItem {
  id: number; teacherId: number; teacherName?: string; name: string
  level?: string; issuer?: string; honorDate?: string; photoUrl: string
}
interface TeacherStat { teacherId: number; realName: string; photoUrl?: string; count: number }

const honors = ref<HonorItem[]>([])
const teachers = ref<TeacherStat[]>([])
const teacherId = ref<number>()
const loading = ref(true)
const avatars = ref<Record<number, string>>({})
let avatarUrls: string[] = []

const filtered = computed(() =>
  teacherId.value == null ? honors.value : honors.value.filter((h) => h.teacherId === teacherId.value))

const palette = ['#A8232B', '#7C4DD8', '#0D9467', '#B07A1C', '#D6567A', '#3A7CA5']
function avaColor(name?: string) {
  if (!name) return palette[0]
  let h = 0
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) % 997
  return palette[h % palette.length]
}

/** 筛选条头像：教师档案照片 JWT 拉流 → objectURL（失败用首字圆） */
async function loadAvatars() {
  for (const t of teachers.value) {
    if (!t.photoUrl) continue
    try {
      const blob = await fetchBlob(t.photoUrl)
      const url = URL.createObjectURL(blob)
      avatarUrls.push(url)
      avatars.value = { ...avatars.value, [t.teacherId]: url }
    } catch { /* 照片拉取失败保持首字圆 */ }
  }
}

onMounted(async () => {
  try {
    ;[honors.value, teachers.value] = await Promise.all([
      api<HonorItem[]>('/api/teacher-honor/list?limit=200'),
      api<TeacherStat[]>('/api/teacher-honor/teachers'),
    ])
    loadAvatars()
  } finally {
    loading.value = false
  }
})

onUnmounted(() => avatarUrls.forEach((u) => URL.revokeObjectURL(u)))
</script>

<style scoped>
.top { padding: 12px 0; }
/* 横滑筛选条：头像+姓名+条数 */
.chips { display: flex; gap: 8px; padding: 0 14px; overflow-x: auto; scrollbar-width: none; }
.chips::-webkit-scrollbar { display: none; }
.chip { flex: none; display: flex; align-items: center; gap: 6px; padding: 6px 14px 6px 7px;
  border: 1px solid var(--app-card-border); border-radius: 999px; background: #fff;
  color: var(--app-text-2); font-size: 12px; cursor: pointer;
  -webkit-tap-highlight-color: transparent; }
.chip.on { border-color: #B07A1C; background: #FBF3DF; color: #B07A1C; font-weight: 600; }
.chip-ava { display: flex; align-items: center; justify-content: center; width: 24px; height: 24px;
  border-radius: 50%; overflow: hidden; color: #fff; font-size: 11px; font-weight: 600; }
.chip-ava img { width: 100%; height: 100%; object-fit: cover; display: block; }
.cnt { padding: 0 7px; border-radius: 999px; background: #FDEEE2; color: #EA580C;
  font-size: 11px; font-weight: 600; }
.chip.on .cnt { background: #fff; color: #B07A1C; }

/* 2 列成就墙（同微光列表墙） */
.wall { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 12px; }
.cell { overflow: hidden; cursor: default; }
.ph { position: relative; aspect-ratio: 3/4; background: #F2F5FB; }
.ph :deep(img) { width: 100%; height: 100%; object-fit: cover; }
.p-tag { position: absolute; left: 8px; bottom: 8px; padding: 2px 9px; border-radius: 999px;
  background: rgba(13,22,50,.55); color: #fff; font-size: 10px; backdrop-filter: blur(4px); }
.info { padding: 9px 11px 10px; }
.name { margin: 0; font-size: 13px; font-weight: 600; color: var(--app-text-1);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.meta { margin: 3px 0 0; font-size: 11px; color: var(--app-text-3); }
.issuer { margin: 3px 0 0; font-size: 11px; color: var(--app-text-3);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 空态 + 吸底记录（悬浮钮遮挡最后一行 → 有数据时底部留白） */
.thlist.tall { padding-bottom: 88px; }
.empty-card { display: flex; flex-direction: column; align-items: center; gap: 6px;
  margin-top: 12px; padding-bottom: 18px; }
.go-add { height: 40px; padding: 0 22px; border: none; font-size: 14px;
  background: linear-gradient(150deg, #B07A1C, #8F5E10); }
.fab-add { position: fixed; right: 16px; bottom: calc(20px + var(--sab));
  display: flex; align-items: center; gap: 5px; padding: 12px 18px; border: none;
  border-radius: 999px; background: linear-gradient(150deg, #B07A1C, #8F5E10);
  color: #fff; font-size: 14px; font-weight: 600; cursor: pointer;
  box-shadow: 0 6px 18px rgba(176,122,28,.35); }
</style>
