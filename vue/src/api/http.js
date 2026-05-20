export async function api(path, options = {}) {
  const res = await fetch(path, {
    credentials: 'include',
    headers: options.body instanceof FormData ? undefined : { 'Content-Type': 'application/json' },
    ...options
  })
  const type = res.headers.get('content-type') || ''
  const data = type.includes('application/json') ? await res.json() : await res.text()
  if (!res.ok) throw new Error(errorMessage(data))
  return data
}

export function postJson(path, body) {
  return api(path, { method: 'POST', body: JSON.stringify(body) })
}

function errorMessage(data) {
  if (!data) return '请求失败'
  if (typeof data === 'string') return data
  const detail = data.detail || data.msg || data.message || data.error
  if (typeof detail === 'string') return detail
  if (Array.isArray(detail)) return detail.map(errorMessage).join('；')
  if (detail && typeof detail === 'object') return detail.msg || detail.message || JSON.stringify(detail)
  return JSON.stringify(data)
}
