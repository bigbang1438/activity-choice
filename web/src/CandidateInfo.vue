<script setup lang="ts">
import { ruleDisplay } from "./behavior";
import type { PublicCandidate } from "./types";
defineProps<{ candidate: PublicCandidate }>();
const rules = [
  { label: "开放安排", text: "openingText", status: "openingStatus" },
  { label: "费用", text: "feeText", status: "feeStatus" },
  { label: "预约", text: "reservationText", status: "reservationStatus" },
] as const;
</script>
<template>
  <dl class="place-details">
    <div>
      <dt>地点</dt>
      <dd>{{ candidate.address || "地点待补充" }}</dd>
    </div>
    <div>
      <dt>活动时段</dt>
      <dd>{{ candidate.timeWindow || "时段待商量" }}</dd>
    </div>
    <div v-for="rule in rules" :key="rule.text">
      <dt>{{ rule.label }}</dt>
      <dd
        :class="{
          unknown: !ruleDisplay(candidate[rule.text], candidate[rule.status])
            .verified,
        }"
      >
        {{ ruleDisplay(candidate[rule.text], candidate[rule.status]).text }}
      </dd>
    </div>
  </dl>
  <p class="source-line" v-if="candidate.sourceUrl">
    <a :href="candidate.sourceUrl" target="_blank" rel="noopener noreferrer"
      >查看信息来源</a
    ><span>{{
      candidate.verifiedAt
        ? `记录日期 ${candidate.verifiedAt}`
        : "未记录核实日期"
    }}</span>
  </p>
  <p class="source-line unknown" v-else>尚未添加信息来源，出发前请再次确认。</p>
</template>
