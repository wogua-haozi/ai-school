<template>
  <!-- 批62 微光多图：1 张=大图，2-3 张=一行等分，4 张起=三列宫格；
       逐张 JWT 拉流 → objectURL，点击上抛 (srcs, idx) 供父层 PhotoPreview 全屏轮播 -->
  <div class="m-photos" :data-n="Math.max(urls.length, 1)">
    <div v-for="(s, i) in cells" :key="i" class="cell" @click="tap(i)">
      <img v-if="s" :src="s" alt="微光照片">
      <div v-else class="cell ph"><van-icon name="photo-o" /></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { fetchBlob } from '../api/http'

const props = defineProps<{ urls: string[] }>()
const emit = defineEmits<{ (e: 'tap', srcs: string[], idx: number): void }>()

const srcs = ref<string[]>(props.urls.map(() => ''))
const cells = computed(() => (srcs.value.length ? srcs.value : ['']))

let objectUrls: string[] = []

onMounted(async () => {
  for (let i = 0; i < props.urls.length; i++) {
    try {
      const blob = await fetchBlob(props.urls[i])
      const u = URL.createObjectURL(blob)
      objectUrls.push(u)
      srcs.value[i] = u
    } catch { /* 加载失败保持占位 */ }
  }
})

function tap(i: number) {
  const loaded = srcs.value.filter(Boolean)
  if (loaded.length) emit('tap', loaded, Math.min(i, loaded.length - 1))
}

onUnmounted(() => objectUrls.forEach((u) => URL.revokeObjectURL(u)))
</script>

<style scoped>
.m-photos { display: grid; gap: 4px; }
.m-photos[data-n="1"] { grid-template-columns: 1fr; }
.m-photos[data-n="1"] .cell img { width: 100%; max-height: 340px; object-fit: cover; display: block; }
.m-photos[data-n="2"] { grid-template-columns: 1fr 1fr; }
.m-photos[data-n="3"] { grid-template-columns: 1fr 1fr 1fr; }
.m-photos[data-n="4"], .m-photos[data-n="5"], .m-photos[data-n="6"],
.m-photos[data-n="7"], .m-photos[data-n="8"], .m-photos[data-n="9"] { grid-template-columns: 1fr 1fr 1fr; }
.cell { aspect-ratio: 1; overflow: hidden; border-radius: 6px; cursor: pointer; }
.m-photos[data-n="1"] .cell { aspect-ratio: auto; }
.cell img { width: 100%; height: 100%; object-fit: cover; display: block; }
.cell.ph { display: flex; align-items: center; justify-content: center;
  background: #EEF2F8; color: #B9C3D4; font-size: 20px; }
</style>
