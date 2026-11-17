# 贡献指南

## 初次开发

第一次参与本项目开发时，先按顺序阅读 `docs/` 下的文档：

1. `docs/PROPOSAL.md`：产品定位、架构、身份与隔离、Agent Loop、数据模型和当前实现差距。
2. `docs/STANDARDS.md`：编码、模块边界和工程规范。
3. `docs/MEDIA_PIPELINE_DESIGN.md`：图片/视频生成、任务生命周期和媒体资产方案。

随后阅读 `README.md` 或 `README_zh-CN.md` 了解本地启动方式。文档与代码冲突时，以当前代码、数据库迁移和测试为准，并在同一次变更中修正文档。

## 提交信息

提交信息必须使用多行格式：

1. 第一行是简短主题，采用 `type: summary` 形式，例如 `feat: ...`、`fix: ...`、`refactor: ...`、`docs: ...`。
2. 空一行后写正文，说明动机、影响范围和关键取舍。
3. 涉及多个模块时，在正文中用列表逐项列出。
4. 不要把全部内容挤在一行。

示例（commit `eb81be33eae29353599051afe3e8241fac01d29a`）：

```text
refactor: replace workspaces with per-user conversation work areas

Drop the workspace entity, membership, space roles, workspace switching and
every workspace claim from the access token. Users remain, but each one
only sees their own conversations: a conversation is the ownership
boundary and its files form that conversation's work area.

- identity: login issues a token pair directly, tokens carry sub/email/role
- common: BizLog no longer records a workspace
- asset: workspace_item/conversation_item become conversation_file and
  conversation_file_version, scoped by conversation and owner
- agent: conversation/message/agent_run/tool_invocation lose workspace_id;
  tools become the six conversation-file tools
- api: conversation-scoped paths, workspace controllers and guards removed
- V11 drops the workspace model (pre-release data not migrated), V12
  creates the conversation file tables
```

提交前确认：

- 变更范围与提交主题一致。
- 相关测试通过，并在提交说明中如实反映结果。
- 受影响的文档已同步更新。
