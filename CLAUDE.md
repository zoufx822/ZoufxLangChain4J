# CLAUDE.md

本文档为 Claude Code 在本项目中工作时提供指导。

## 项目概述

Spring Boot 4.0.3 + LangChain4J 应用，通过 Anthropic 兼容接口连接 MiniMax AI API，支持带会话记忆的流式聊天。

## 架构
```
src/main/java/com/zoufxdemo/zoufxlangchain4j/
├── ZoufxLangChain4JApplication.java
├── config/LangChain4JConfig.java          # LangChain4J + MiniMax API 配置
├── controller/AIChatController.java       # REST API（SSE 流式响应）
├── model/ChatRequest.java
└── service/
    ├── ChatMemoryService.java
    └── impl/ChatMemoryServiceImpl.java
```

## 开发规范

- 代码简洁优雅，恰到好处地使用工具
- 默认在 `main` 分支开发，代码写完后**不自动提交**，告知用户变更内容后等待用户决定

## 多 Agent 并行开发

仅当需求**同时满足**以下条件时才启用：

- 涉及多个独立模块
- 任务量大，适合分工

启用流程：

1. 用 `superpowers:using-git-worktrees` 创建工作树和功能分支
2. 多 agent 在独立工作树中并行开发
3. 完成后用 `superpowers:finishing-a-development-branch` 合并分支

## 测试规范

测试完成后清理测试脚本、截图等临时文件。