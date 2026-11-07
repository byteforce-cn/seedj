import { defineConfig } from "vitepress";

// 开发者文档站：只构建 docs/ 下的产品文档，过程文档不在构建输入里。
// https://vitepress.dev/reference/site-config
export default defineConfig({
  lang: "zh-CN",
  title: "Seedj",
  description: "Java 21 / Spring Boot 业务基础设施：命令、双时间轴、权限与工作流",
  base: "/seedj/",
  cleanUrls: true,
  lastUpdated: true,
  sitemap: {
    hostname: "https://byteforce-cn.github.io",
  },
  themeConfig: {
    nav: [
      { text: "指南", link: "/guide/" },
      { text: "模块", link: "/guide/modules" },
      { text: "命令契约", link: "/guide/command-contract" },
      { text: "测试", link: "/guide/testing" },
      { text: "兼容性", link: "/guide/api-compatibility" },
      { text: "GitHub", link: "https://github.com/byteforce-cn/seedj" },
    ],
    sidebar: {
      "/guide/": [
        {
          text: "开始",
          items: [
            { text: "指南索引", link: "/guide/" },
            { text: "快速开始", link: "/guide/quickstart" },
            { text: "概念与术语", link: "/guide/concepts" },
          ],
        },
        {
          text: "业务开发",
          items: [
            { text: "业务开发手册", link: "/guide/cookbook" },
            { text: "模块", link: "/guide/modules" },
      { text: "命令契约", link: "/guide/command-contract" },
            { text: "读模型", link: "/guide/read-model" },
            { text: "错误目录", link: "/guide/errors" },
            { text: "测试", link: "/guide/testing" },
          ],
        },
        {
          text: "装配与集成",
          items: [
            { text: "模块与装配", link: "/guide/modules" },
            { text: "业务装配", link: "/guide/biz-starter" },
            { text: "IAM 装配", link: "/guide/iam-starter" },
            { text: "基础设施 SPI", link: "/guide/spi" },
            { text: "配置说明", link: "/guide/configuration" },
          ],
        },
        {
          text: "边界与决策",
          items: [
            { text: "决策指南", link: "/guide/decision-guide" },
            { text: "API 兼容性", link: "/guide/api-compatibility" },
            { text: "已知限制", link: "/guide/known-limitations" },
            { text: "安全", link: "/guide/security" },
          ],
        },
        {
          text: "参考",
          items: [{ text: "FAQ", link: "/faq" }],
        },
      ],
    },
    search: { provider: "local" },
    editLink: {
      pattern: "https://github.com/byteforce-cn/seedj/edit/main/docs/:path",
      text: "在 GitHub 上编辑此页",
    },
    socialLinks: [
      { icon: "github", link: "https://github.com/byteforce-cn/seedj" },
    ],
    footer: {
      message: "Apache License 2.0",
      copyright: "Copyright 2026 ByteForce",
    },
  },
});
