<template>
  <div class="multi-select" :class="{ open }">
    <button class="multi-select-trigger" type="button" @click="open = !open">
      <span :class="{ placeholder: selected.length === 0 }">{{ summary }}</span>
      <span class="multi-select-arrow">⌄</span>
    </button>
    <div v-if="open" class="multi-select-menu">
      <label v-if="allowAll" class="multi-select-option all">
        <input type="checkbox" :checked="isAll" @change="toggleAll" />
        <span>全部</span>
      </label>
      <label v-for="option in options" :key="option.value" class="multi-select-option">
        <input type="checkbox" :checked="isAll || selected.includes(option.value)" @change="toggle(option.value)" />
        <span>{{ option.label }}</span>
      </label>
      <div class="multi-select-footer">
        <span>已选 {{ isAll ? options.length : selected.length }} 项</span>
        <button type="button" @click="open = false">完成</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  options: { type: Array, default: () => [] },
  placeholder: { type: String, default: '请选择' },
  allowAll: { type: Boolean, default: true }
})

const emit = defineEmits(['update:modelValue'])
const open = ref(false)
const isAll = computed(() => props.modelValue.trim() === '*')
const selected = computed(() => isAll.value ? [] : props.modelValue
  .split(',')
  .map(value => value.trim())
  .filter(Boolean))
const summary = computed(() => {
  if (isAll.value) return '全部'
  if (!selected.value.length) return props.placeholder
  const labels = selected.value.map(value => props.options.find(option => option.value === value)?.label || value)
  return labels.length <= 2 ? labels.join('、') : `${labels.slice(0, 2).join('、')} 等 ${labels.length} 项`
})

function toggleAll(event) {
  emit('update:modelValue', event.target.checked ? '*' : '')
}

function toggle(value) {
  const next = isAll.value ? props.options.map(option => option.value) : [...selected.value]
  const index = next.indexOf(value)
  if (index >= 0) next.splice(index, 1)
  else next.push(value)
  emit('update:modelValue', next.join(','))
}
</script>
