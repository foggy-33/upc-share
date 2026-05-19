export async function api(path, options = {}) {
  const res = await fetch(path, {
    credentials: 'include',
    headers: options.body instanceof FormData ? undefined : { 'Content-Type': 'application/json' },
    ...options
  })
  const type = res.headers.get('content-type') || ''
  const data = type.includes('application/json') ? await res.json() : await res.text()
  if (!res.ok) throw new Error(data.detail || data.msg || data || '请求失败')
  return data
}

export function postJson(path, body) {
  return api(path, { method: 'POST', body: JSON.stringify(body) })
}
