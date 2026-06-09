import { reactive } from 'vue'

const LABEL_CHECKING = '\u7ebf\u8def\u68c0\u6d4b\u4e2d'
const LABEL_CAMPUS = '\u6821\u56ed\u7f51\u8bbf\u95ee'
const LABEL_PUBLIC = '\u516c\u7f51\u8bbf\u95ee'

const config = {
  campusOrigin: normalizeOrigin(import.meta.env.VITE_CAMPUS_ORIGIN || 'https://in.upcshare.cn'),
  publicOrigins: splitOrigins(import.meta.env.VITE_PUBLIC_ORIGINS || 'https://upcshare.cn,https://www.upcshare.cn'),
  probePath: normalizePath(import.meta.env.VITE_ACCESS_ROUTE_PROBE_PATH || '/api/ping'),
  timeoutMs: positiveInt(import.meta.env.VITE_ACCESS_ROUTE_TIMEOUT_MS, 1200)
}

export const accessRouteState = reactive({
  mode: detectCurrentMode(),
  label: LABEL_CHECKING,
  checking: false,
  campusReachable: false
})

let started = false

export function startAccessRouteProbe() {
  if (started) return
  started = true

  const currentOrigin = normalizeOrigin(window.location.origin)
  if (config.campusOrigin && currentOrigin === config.campusOrigin) {
    setState('campus', LABEL_CAMPUS, false, true)
    return
  }

  const isPublicEntry = config.publicOrigins.includes(currentOrigin)
  if (!isPublicEntry || !config.campusOrigin) {
    setState('public', LABEL_PUBLIC, false, false)
    return
  }

  setState('checking', LABEL_CHECKING, true, false)
  probeOrigin(config.campusOrigin).then((ok) => {
    if (!ok) {
      setState('public', LABEL_PUBLIC, false, false)
      return
    }

    // Keep the current origin so host-only login cookies remain valid.
    setState('public', LABEL_PUBLIC, false, true)
  })
}

function setState(mode, label, checking, campusReachable) {
  accessRouteState.mode = mode
  accessRouteState.label = label
  accessRouteState.checking = checking
  accessRouteState.campusReachable = campusReachable
}

async function probeOrigin(origin) {
  const probeUrl = `${origin}${config.probePath}${config.probePath.includes('?') ? '&' : '?'}_probe=${Date.now()}`
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), config.timeoutMs)

  try {
    await fetch(probeUrl, {
      method: 'GET',
      mode: 'no-cors',
      cache: 'no-store',
      credentials: 'omit',
      signal: controller.signal
    })
    return true
  } catch {
    return false
  } finally {
    clearTimeout(timer)
  }
}

function detectCurrentMode() {
  const currentOrigin = normalizeOrigin(window.location.origin)
  if (config.campusOrigin && currentOrigin === config.campusOrigin) return 'campus'
  return 'checking'
}

function normalizeOrigin(url) {
  return (url || '').trim().replace(/\/+$/, '')
}

function splitOrigins(value) {
  return String(value || '')
    .split(',')
    .map(normalizeOrigin)
    .filter(Boolean)
}

function normalizePath(path) {
  const p = String(path || '').trim()
  if (!p) return '/api/ping'
  return p.startsWith('/') ? p : `/${p}`
}

function positiveInt(value, fallback) {
  const n = Number(value)
  if (!Number.isFinite(n) || n <= 0) return fallback
  return Math.floor(n)
}
