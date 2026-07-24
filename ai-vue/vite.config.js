import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    // 监听所有网卡（IPv4 + IPv6），保证 localhost / 127.0.0.1 / 局域网 IP 都能打开。
    // 注意：之前写成 '127.0.0.1' 只绑定 IPv4，而 Windows 上 localhost 会优先解析到 ::1，
    // 容易出现「端口在监听但浏览器打不开」的情况。
    host: true,
    port: 5173,
    // 端口被占用时直接报错，而不是自动换端口——避免你以为访问的是本项目，
    // 实际连到了别的项目（多份源码同时启动时极易发生）
    strictPort: true,
    proxy: {
      '/api': {
        // 指向本地后端（application.yml 中 server.port=1236）
        // 原配置为 http://159.75.169.224:1235（远程服务器，实测返回 403）
        // 用 127.0.0.1 而不是 localhost：避免 IPv6 解析带来的连接歧义
        target: 'http://127.0.0.1:1236',
        changeOrigin: true
      }
    }
  }
})
