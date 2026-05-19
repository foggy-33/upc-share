<template>
  <div class="global-route-indicator" :class="routeClass">{{ accessRoute.label }}</div>
  <router-view />
</template>

<script setup>
import { computed } from 'vue'
import { accessRouteState as accessRoute } from './accessRoute'

const routeClass = computed(() => ({
  'route-campus': accessRoute.mode === 'campus',
  'route-public': accessRoute.mode === 'public',
  'route-checking': accessRoute.mode === 'checking'
}))
</script>
