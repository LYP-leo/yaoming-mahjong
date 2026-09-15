<script setup lang="ts">
import { computed, ref } from 'vue'
import TileView from './TileView.vue'
import type { Rules, RuleId } from './types'
import { minimumFanOf, ruleChoices } from './ruleProfiles'
const props = defineProps<{ rules: Rules | null; ruleId?: RuleId; choices?: Rules[] }>()
const selectedRuleId = computed(() => props.ruleId || props.rules?.id || 'yaoming-3p')
const shownRules = computed(() => (props.rules?.id || 'yaoming-3p') === selectedRuleId.value ? props.rules : null)
const four = computed(() => selectedRuleId.value === 'yaoming-4p')
const minimumFan = computed(() => minimumFanOf({ ruleId: selectedRuleId.value }))
const choices = computed(() => props.choices?.length ? props.choices : ruleChoices)
const emit = defineEmits<{ close: []; select: [ruleId: RuleId] }>()
function selectRule(event: Event) { emit('select', (event.target as HTMLSelectElement).value as RuleId) }
const query = ref('')
const filtered = computed(() => shownRules.value?.fans.filter(f => (f.name + f.description).includes(query.value.trim())) || [])
const suits = computed(() => [ ['CHARACTERS', four.value ? '万子 · 一至九' : '万子 · 只用一、五、九'], ['BAMBOO', '条子 · 一至九'], ['DOTS', '筒子 · 一至九'], ['HONORS', four.value ? '字牌 · 东南西北、中发白' : '字牌 · 东南西、中发白'] ])
const uniqueTiles = computed(() => [...new Map(shownRules.value?.tiles.map(t => [`${t.suit}-${t.rank}`, t]) || []).values()])
</script>
<template>
  <section class="ym-rules-page">
    <div class="ym-page-heading"><div><span class="ym-overline">THE RULEBOOK · {{ shownRules?.version || '26.9 LTS' }}</span><h1>先懂规则，再来一局。</h1><p>{{ four ? '要命麻将 · 四人实验性规则 · 4 人对局' : '要命麻将 · 朴素规则 · 3 人对局' }}</p></div><button class="ym-button" @click="$emit('close')">← 返回{{ '牌桌 / 大厅' }}</button></div>
    <label class="ym-rule-selector">选择规则<select aria-label="选择规则手册" :value="selectedRuleId" @change="selectRule"><option v-for="choice in choices" :key="choice.id" :value="choice.id">{{ choice.name }}</option></select></label>
    <p v-if="shownRules?.notes.length" class="ym-rule-revision">{{ shownRules.notes[0] }}</p>
    <div class="ym-rule-overview">
      <article><strong>{{ shownRules?.tileCount || (four ? 136 : 108) }}<span> 张牌</span></strong><p>{{ four ? '34 种，每种 4 张。含北风，无花、无红宝牌。' : '27 种，每种 4 张。无北风、无花、无红宝牌。' }}</p></article>
      <article><strong>{{ minimumFan }}<span> 番起和</span></strong><p>{{ four ? '8 番封顶。四面子一雀头或全不靠，不承认七对子。' : '8 番封顶。四面子一雀头或风龙，不承认七对子。' }}</p></article>
      <article><strong>10<span> 点起步</span></strong><p>{{ four ? '自摸另外三家各支付番数点；点和由放铳者支付 4 倍番数。' : '自摸每家支付番数点；点和由放铳者支付 3 倍番数。' }}</p></article>
      <article><strong>{{ shownRules?.totalRounds || (four ? 8 : 6) }}<span> 局轮庄</span></strong><p>{{ four ? '东一至南四，不连庄。任意一家归零立即终场。' : '东一至南三，不连庄。任意一家归零立即终场。' }}</p></article>
    </div>
    <section class="ym-rule-section"><h2>这副牌里有什么</h2><p>{{ four ? '全部数牌使用相邻三张顺子；一万、五万、九万不能组成顺子。' : '一万、五万、九万可以组成一个顺子。其余数牌照常组成相邻三张顺子。' }}</p>
      <div v-for="[suit, label] in suits" :key="suit" class="ym-tile-catalog"><span>{{ label }}</span><div><TileView v-for="tile in uniqueTiles.filter(t => t.suit === suit)" :key="`${tile.suit}-${tile.rank}`" :tile="tile" /></div></div>
      <p v-if="!shownRules">规则正在加载，请稍候…</p>
    </section>
    <section class="ym-rule-section"><div class="ym-row"><div><h2>番种速查 <small>{{ shownRules?.fans.length || 20 }} 项</small></h2><p>按服务器实际计番规则展示；有多个拆分时取最高番的合法组合。</p></div><input v-model="query" aria-label="搜索番种" placeholder="搜索番种或条件…" /></div>
      <div class="ym-fan-grid"><article v-for="fan in filtered" :key="fan.id"><div><h3>{{ fan.name }}</h3><strong>{{ fan.fan }} <small>番</small></strong></div><p>{{ fan.description }}</p></article></div>
      <p v-if="shownRules && !filtered.length">没有找到对应番种。</p>
    </section>
    <section class="ym-rule-section ym-rule-notes"><h2>行动与结算</h2><p>仅下家可以吃。点和优先，其次碰 / 杠，最后吃；多人点和时由距离出牌者最近的一家截和。杠牌从牌墙尾补牌，不启用抢杠和。</p><p>要命麻将没有舍牌振听或过和振听：自己打过、曾放过的同种牌，在后续响应窗口仍可点和，无需等自己摸牌。点和仍须符合合法和牌结构并达到 {{ minimumFan }} 番。</p><p>牌墙耗尽即荒牌流局，无罚符。每局所有真人确认结算后继续，最多等待 60 秒；终场确认后各自离开，不会自动退出。实际支付不超过余额，点数总和始终为 {{ four ? 40 : 30 }}。</p><p>摸牌限时 15 秒、出牌 30 秒、吃碰和响应 20 秒。摸牌或出牌超时后会自动托管，请点击“收回控制”重新接手；刷新页面或查看规则不会重置计时。</p><p>默认先点选手牌，再次点击同一张牌或使用出牌按钮确认；开启“快捷出牌”后，单击立即打出。新摸到的牌独立放在右侧并默认选中；A/D 改选，空格或回车确认出牌；响应阶段空格表示过。</p><p v-for="note in shownRules?.notes.slice(1) || []" :key="note">{{ note }}</p></section>
    <section class="ym-rule-section ym-rule-notes"><h2>操作设置</h2><p>默认开启自动摸牌：轮到自己且允许摸牌时自动摸一张，不会自动出牌、吃碰、和牌或确认结算。可在顶部“设置”关闭，恢复手动摸牌；设置保存在这台设备。</p><p>查看规则、牌谱、结算或打开设置时暂停自动摸牌，但服务器倒计时继续。返回牌桌后恢复；若自动请求失败，可按“摸牌”手动重试。</p><p>和牌结算默认展示赢家完整手牌、副露、和牌张标记与逐项番型依据；番数和支付始终采用服务器结算。已完成的结算和旧牌谱保留当时记录，不按新版规则重算。</p></section>
  </section>
</template>
