import { ref } from 'vue'

const STORAGE_KEY = 'upcshare-theme'
let saved = 'light'
try {
  saved = localStorage.getItem(STORAGE_KEY) || 'light'
} catch { /* The theme still works when browser storage is unavailable. */ }

export const isDark = ref(saved === 'dark')

function applyTheme() {
  document.documentElement.dataset.theme = isDark.value ? 'dark' : 'light'
}

export function toggleTheme() {
  isDark.value = !isDark.value
  applyTheme()
  try {
    localStorage.setItem(STORAGE_KEY, isDark.value ? 'dark' : 'light')
  } catch { /* Keep the current session's selection. */ }
}

applyTheme()
