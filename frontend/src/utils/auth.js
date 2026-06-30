const TOKEN_KEY = 'cd_token'
const USER_KEY = 'cd_user'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setAuth(user) {
  localStorage.setItem(TOKEN_KEY, user.token || '')
  localStorage.setItem(USER_KEY, JSON.stringify(user || {}))
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export function getCurrentUser() {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) {
    return null
  }

  try {
    return JSON.parse(raw)
  } catch {
    clearAuth()
    return null
  }
}
