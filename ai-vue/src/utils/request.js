import axios from 'axios'
import { ElMessage } from 'element-plus'

// 创建axios实例
const service = axios.create({
  baseURL: '/api', // 请求的前缀
  timeout: 5000, // 请求的超时时间
})

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    // 在发送请求之前做些什么
    const token = localStorage.getItem('token')
    if (token) {
      config.headers['token'] = token
    }
    return config
  },
  (error) => {
    // 对请求错误做些什么
    return Promise.reject(error)
  }
)

// 响应拦截器
service.interceptors.response.use(
  (response) => {
    // 对响应数据做点什么
    const { data, config } = response
    // 处理业务状态码
    if (data.code === '200') {
        return data.data
    } else {
        if (data.code === '-1') {
          if (!config.url?.includes('/login')) {
            ElMessage.error(data.msg || '登录过期，请重新登录')

            // 清除登录信息
            localStorage.removeItem('token')
            localStorage.removeItem('userInfo')
            window.location.href = '/auth/login'
          } else {
            ElMessage.error(data.msg || '登录过期，请重新登录')
            return Promise.reject('网络请求失败....')
          }
        }
    }
    return response
  },
  (error) => {
    // 对响应错误做点什么
    //
    // 这里必须处理 401 / 403，不能直接 reject。
    // 原因：后端在"token 失效"时返回的是 HTTP 401/403（不会是业务码 -1），
    // 如果不处理，会出现很难排查的现象——
    //   ① 页面数据请求全部失败 → 界面空白（如知识库没内容）
    //   ② localStorage 里的 token 一直不被清除 → "退出登录"按钮照常显示，
    //      但点击后 logout 请求同样 401/403，.then() 永远不执行，看起来像"按钮点不了"
    // 之前只有业务码 -1 会触发登出，HTTP 层错误被静默吞掉，正是这个坑。
    const status = error.response?.status
    if (status === 401 || status === 403) {
      const url = error.config?.url || ''
      ElMessage.error('登录已失效，请重新登录')
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      // 登录页自身的失败不跳转，否则会陷入循环刷新
      if (!url.includes('/login')) {
        window.location.href = '/auth/login'
      }
    }
    return Promise.reject(error)
  }
)

export default service