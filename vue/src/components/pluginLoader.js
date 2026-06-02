const loadedScripts = new Map()
const loadedStyles = new Set()

export function loadStyle(href) {
  if (loadedStyles.has(href)) return Promise.resolve()
  const existing = document.querySelector(`link[href="${href}"]`)
  if (existing) {
    loadedStyles.add(href)
    return Promise.resolve()
  }
  return new Promise((resolve, reject) => {
    const link = document.createElement('link')
    link.rel = 'stylesheet'
    link.href = href
    link.onload = () => {
      loadedStyles.add(href)
      resolve()
    }
    link.onerror = reject
    document.head.appendChild(link)
  })
}

export function loadScript(src) {
  if (loadedScripts.has(src)) return loadedScripts.get(src)
  const promise = new Promise((resolve, reject) => {
    const existing = document.querySelector(`script[src="${src}"]`)
    if (existing) {
      existing.addEventListener('load', resolve, { once: true })
      existing.addEventListener('error', reject, { once: true })
      if (existing.dataset.loaded === 'true') resolve()
      return
    }
    const script = document.createElement('script')
    script.src = src
    script.async = true
    script.onload = () => {
      script.dataset.loaded = 'true'
      resolve()
    }
    script.onerror = reject
    document.body.appendChild(script)
  })
  loadedScripts.set(src, promise)
  return promise
}

export async function loadToastUiEditor() {
  await loadStyle('https://cdn.jsdelivr.net/npm/@toast-ui/editor@3.2.2/dist/toastui-editor.min.css')
  await loadScript('https://cdn.jsdelivr.net/npm/@toast-ui/editor@3.2.2/dist/toastui-editor-all.min.js')
  return window.toastui?.Editor
}

export async function loadPhotoSwipe() {
  await loadStyle('https://cdn.jsdelivr.net/npm/photoswipe@5.4.4/dist/photoswipe.css')
  return import(/* @vite-ignore */ 'https://cdn.jsdelivr.net/npm/photoswipe@5.4.4/dist/photoswipe-lightbox.esm.min.js')
}
