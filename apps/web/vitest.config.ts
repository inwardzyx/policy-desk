// 单独的测试配置：不参与 `vue-tsc` 的构建检查，也避免与 vite.config.ts 的 vite 版本类型冲突。
// 现在只有纯 TS 的单元测试（src/api.test.ts），不需要 vue 插件。
import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
