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
      { text: "GitHub", link: "https://github.com/byteforce-cn/seedj" },
    ],
    sidebar: {
      "/guide/": [
        {
          text: "开始",
          items: [{ text: "指南索引", link: "/guide/" }],
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
