# Easy-CD

<div align="center">

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.5-4FC08D.svg)](https://vuejs.org/)
[![Docker](https://img.shields.io/badge/Docker-Swarm-2496ED.svg)](https://docs.docker.com/engine/swarm/)
[![Status](https://img.shields.io/badge/status-开发中-orange.svg)]()

轻量级容器部署平台 - 支持 Docker Swarm & Kubernetes | 简单好用，专为中小团队设计

[功能特性](#功能特性) • [技术栈](#技术栈) • [项目结构](#项目结构)

> ⚠️ **项目状态：开发中**  
> 本项目仍在积极开发中，当前已完成 **Docker Swarm** 全部功能可正常使用（但不完整）。  
> **Kubernetes** 支持正在开发中，预计近期完成。  
> 欢迎 Star 关注项目进展！

</div>

---

## 📖 项目简介

一个轻量级的持续部署（CD）平台，专为中小型团队打造。支持 **Docker Swarm** 和 **Kubernetes** 双平台，提供简洁直观的镜像部署能力。

**设计理念：简单、好用、够用**

区别于功能繁重的企业级 CD 平台，本项目聚焦于核心部署需求：
- 🎯 **开箱即用** - 无需复杂配置，快速上手
- 🪶 **轻量简洁** - 摒弃冗余功能，专注部署本质
- 🚀 **高效直观** - 现代化 UI，一键完成服务管理
- 💡 **适度而为** - 为中小组织量身定制，不过度设计

如果你正在寻找一个「刚刚好」的容器部署工具，而不是功能臃肿的「大平台」，那么这个项目正是为你准备的。

> 📦 **GitHub**: [https://github.com/xyaixk/Easy-CD](https://github.com/xyaixk/Easy-CD)

## ✨ 功能特性

### 🚀 服务管理
- **服务部署** - 支持从 Docker 镜像仓库部署服务
- **服务更新** - 一键更新服务到指定版本
- **版本回滚** - 快速回滚到历史版本
- **服务启停** - 灵活控制服务运行状态
- **服务删除** - 安全删除服务及相关数据

### 📊 监控与状态
- **实时状态监控** - 自动刷新服务运行状态
- **副本健康度** - 清晰展示健康/运行/期望副本数
- **服务状态** - 支持运行中、已停止、部署中、失败等多种状态
- **副本详情** - 查看每个副本的节点、容器ID、状态等信息

### ⚙️ 高级功能
- **弹性扩缩容** - 动态调整服务副本数量
- **多环境支持** - 管理多个 Docker Swarm 环境
- **配置管理** - 灵活的环境变量和 Docker 参数配置
- **版本管理** - 自动同步和显示实际版本号

### 🎨 用户体验
- **现代化 UI** - 基于 Vue 3 的响应式界面
- **深色模式支持** - 护眼的深色主题
- **操作便捷** - 右键菜单式操作，快速高效
- **实时反馈** - Toast 提示，操作状态一目了然

## 🛠 技术栈

**后端：** Spring Boot + MyBatis Plus + MySQL，通过 Docker Java API 与容器平台交互

**前端：** Vue 3 + Vite 构建的单页应用，轻量级设计

**部署：** 支持 Docker Swarm 和 Kubernetes 双平台

## 📅 开发进度（持续进行中）

### ✅ 已完成功能

**基础架构**
- [x] Spring Boot 后端框架搭建
- [x] Vue 3 前端项目初始化
- [x] MySQL 数据库设计
- [x] Docker Java API 集成

**Docker Swarm 支持**
- [x] 服务部署（创建/更新）
- [x] 服务启停控制
- [x] 服务删除
- [x] 弹性扩缩容
- [x] 版本回滚
- [x] 服务重启
- [x] 服务状态实时监控
- [x] 副本健康度展示
- [x] 副本详情查看

**环境管理**
- [x] 多环境支持
- [x] 环境增删改查
- [x] 环境切换

**用户界面**
- [x] 服务卡片列表
- [x] 服务创建/编辑弹窗
- [x] 副本详情弹窗
- [x] 操作菜单（三点式）
- [x] Toast 消息提示
- [x] 深色主题设计

**核心特性**
- [x] 版本号自动提取和同步
- [x] 定时任务刷新服务状态
- [x] 定时任务刷新副本状态
- [x] 策略模式支持多平台

### 🚧 开发中

**Kubernetes 支持**
- [ ] K8s 集群连接
- [ ] Deployment 部署策略
- [ ] Service 管理
- [ ] Pod 状态监控
- [ ] 扩缩容支持

**监控增强**
- [ ] 服务日志查看
- [ ] 实时日志流
- [ ] 进入容器 Shell

### 💡 计划中

**功能增强**
- [ ] 镜像仓库管理
- [ ] 配置模板系统
- [ ] 部署历史记录
- [ ] 部署通知（钉钉/企微）
- [ ] 权限管理系统
- [ ] 操作审计日志

**体验优化**
- [ ] 国际化支持
- [ ] 浅色主题
- [ ] 移动端适配

---

### 👥 贡献者

欢迎提交 Issue 和 Pull Request ！

## 📝 许可证

本项目采用 [MIT](LICENSE) 许可证。

---

<div align="center">

**如果这个项目对你有帮助，请给一个 ⭐️ Star 支持一下！**

</div>
