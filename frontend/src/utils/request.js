import axios from 'axios'

const request = axios.create({
  baseURL: '/api',
  timeout: 60000
})

// 请求拦截器
request.interceptors.request.use(
  config => {
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
    const message = error.response?.data?.message || error.message || '请求错误'
    console.error('请求错误:', message)
    return Promise.reject(new Error(message))
  }
)

export default request
