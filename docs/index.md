---
layout: home

hero:
  name: Seedj
  text: 写业务代码，不写基础设施
  tagline: Java 21 / Spring Boot 多模块基座：命令进、查询出、双时间轴落库，权限与工作流开箱可用。
  actions:
    - theme: brand
      text: 5 分钟跑起来
      link: /guide/quickstart
    - theme: alt
      text: 业务开发手册
      link: /guide/cookbook
    - theme: alt
      text: GitHub
      link: https://github.com/byteforce-cn/seedj

features:
  - title: 换存储不改业务代码
    details: 内存实现与 PostgreSQL 实现在同一套接口后面；local 开发不装数据库，生产换 profile 即可。
  - title: 时点查询是内置的
    details: 每条记录同时带业务时间与记录时间，as_of / recorded_as_of 直接可查。
  - title: 权限在声明点收口
    details: 命令、读路径、字段脱敏只写一次，运行时按声明失败关闭。
  - title: 契约层零 Spring
    details: core 只依赖 Java 与 Jackson，业务规则测试用 CommandHarness，毫秒级，不启动容器。
---
