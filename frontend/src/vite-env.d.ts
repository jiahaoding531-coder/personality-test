/// <reference types="vite/client" />

/**
 * Vite 的客户端类型声明。
 *
 * 这行三斜线指令引入 Vite 提供的环境类型，它让 TypeScript 知道：
 * - `import './styles.css'` 这类**副作用导入**是合法的
 *   （TypeScript 默认只认 .ts/.js，不认识 .css）
 * - `import.meta.env` 上有哪些变量
 * - `import.meta.hot` 等 HMR 相关 API
 *
 * 没有这个文件，`main.tsx` 里导入样式表会报
 * "Cannot find module or type declarations for side-effect import"。
 */
