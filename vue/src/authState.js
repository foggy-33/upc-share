import { reactive } from 'vue'
import { api } from './api/http'

const emptyUser = {
  logged_in: false,
  uid: '',
  username: '',
  is_admin: false,
  avatar_url: ''
}

export const currentUser = reactive({ ...emptyUser })

let pendingRequest = null

export function loadCurrentUser({ force = false } = {}) {
  if (!force && pendingRequest) return pendingRequest

  pendingRequest = api('/api/auth/me')
    .then((data) => {
      Object.assign(currentUser, emptyUser, data)
      return currentUser
    })
    .catch(() => currentUser)
    .finally(() => {
      pendingRequest = null
    })

  return pendingRequest
}
