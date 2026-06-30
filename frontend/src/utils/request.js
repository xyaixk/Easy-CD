import axios from 'axios'
import { clearAuth, getToken } from '@/utils/auth'

const request = axios.create({
  baseURL: '/api',
  timeout: 60000
})

// 请求拦截器
request.interceptors.request.use(
  config => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器
request.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    } else {
      console.error(res.message)
      return Promise.reject(new Error(res.message || 'Error'))
    }
  },
  error => {
    if (error.response?.status === 401) {
      clearAuth()
    }
    const message = error.response?.data?.message || error.message || '请求错误'
    console.error('请求错误:', message)
    return Promise.reject(new Error(message))
  }
)

export default request
