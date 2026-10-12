<template>
  <div class="app-page rules">
    <!-- 口径说明：文明班评比口径，勿与个人操行分等第混淆 -->
    <div class="app-sec">量化考核标准</div>
    <div class="app-card tex-07 basis">
      <b>《中学生日常行为规范》量化考核标准</b>
      <p class="basis-txt">{{ CONDUCT_BASIS }}</p>
      <p class="note">{{ CONDUCT_NOTE }}</p>
    </div>

    <!-- 12 大项细则：折叠面板，默认展开第一项 -->
    <div class="app-sec">十二项细则</div>
    <div class="app-card tl tex-08 pane">
      <van-collapse v-model="active">
        <van-collapse-item v-for="(s, i) in CONDUCT_SECTIONS" :key="i" :name="i">
          <template #title>
            <span class="s-title">{{ s.full }}</span>
            <span class="s-sub">{{ itemCount(s) }} 条</span>
          </template>
          <div v-for="(g, gi) in s.groups" :key="gi" class="grp">
            <div v-if="g.label" class="g-label">{{ g.label }}</div>
            <div v-for="(it, ii) in g.items" :key="ii" class="item">
              <span class="i-text">{{ it.text }}</span>
              <span v-if="it.delta" class="i-delta" :class="it.delta.startsWith('+') ? 'pos' : 'neg'">
                {{ it.delta }} 分<template v-if="it.unit"> / {{ it.unit }}</template>
              </span>
            </div>
          </div>
        </van-collapse-item>
      </van-collapse>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { CONDUCT_BASIS, CONDUCT_NOTE, CONDUCT_SECTIONS, type ConductSection } from '../data/conductRules'

const active = ref<number[]>([0])

function itemCount(s: ConductSection) {
  return s.groups.reduce((n, g) => n + g.items.length, 0)
}
</script>

<style scoped>
.basis { padding: 14px 16px; }
.basis b { font-size: 15px; color: var(--app-text-1); }
.basis-txt { margin: 8px 0 0; font-size: 13px; line-height: 1.7; color: var(--app-text-2); }
.note { margin: 8px 0 0; font-size: 12px; line-height: 1.6; color: var(--app-text-2); }

.pane { padding: 0 4px; }
.pane :deep(.van-collapse-item) { border-bottom: 1px solid var(--app-card-border); }
.pane :deep(.van-collapse-item:last-child) { border-bottom: none; }
.pane :deep(.van-cell) { padding: 13px 12px; }
.pane :deep(.van-collapse-item__content) { padding: 2px 12px 12px; }
.s-title { font-size: 15px; font-weight: 600; color: var(--app-text-1); }
.s-sub { margin-left: 8px; font-size: 12px; font-weight: 400; color: var(--app-text-3); }

.g-label { margin: 10px 0 4px; font-size: 13px; font-weight: 600; color: var(--app-blue); }
.item { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; padding: 6px 0; }
.i-text { flex: 1; font-size: 13px; line-height: 1.6; color: var(--app-text-1); }
.i-delta { flex: none; font-size: 12px; font-weight: 600; white-space: nowrap; }
.i-delta.neg { color: var(--app-blue); }
.i-delta.pos { color: var(--app-gold); }
</style>
