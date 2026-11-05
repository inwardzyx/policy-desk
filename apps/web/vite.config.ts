import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// ★ 开发期用代理把 /api 转到 Java（8081），而不是在 Java 里配 CORS。
//   理由：生产环境是 nginx 同源反代，本来就该同源；为了开发期而在后端放开跨域，
//   等于把一个只在开发时需要的口子永久留在生产配置里（并且很容易忘了收紧）。
//
// ★ 这里**不写 test 配置**：vitest 自带一份 vite，和这里的 vite 6 类型不同源，
//   混在一个文件里会让 vue-tsc 报一堆 "UserConfig 不兼容"。
//   测试配置放 vitest.config.ts（它不参与 tsc 的构建检查）。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
      },
    },
  },
  // ★ preview（跑 dist）不会复用 server.proxy，必须单独写一份 ——
  //   否则"开发能跑、preview 起来 404"这种假象会在演示前 10 分钟出现。
  preview: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
      },
    },
  },
})
