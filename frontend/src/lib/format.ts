import type {
  AgentRunStatus,
  AlertChannelType,
  AlertDeliveryStatus,
  AlertSeverity,
  CompositionStatus,
  ConversationFileKind,
  CreditLedgerEntryType,
  ExecutionMode,
  GenerationBillingStatus,
  GenerationCancelStatus,
  GenerationDeliveryStatus,
  GenerationStatus,
  MediaProtocol,
  ModelCapabilityType,
  ReasoningEffort,
  ReasoningReplayPolicy,
  RedemptionCodeStatus,
  UserRole,
  UserStatus,
} from "@/lib/api/types";

const dateTimeFormatter = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

const shortTimeFormatter = new Intl.DateTimeFormat("zh-CN", {
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return "—";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return "—";
  }
  return dateTimeFormatter.format(date);
}

export function formatShortTime(iso: string | null | undefined): string {
  if (!iso) {
    return "—";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return "—";
  }
  return shortTimeFormatter.format(date);
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || bytes < 0) {
    return "—";
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  if (bytes < 1024 * 1024 * 1024) {
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

export function getInitials(name: string | null | undefined): string {
  const trimmed = (name ?? "").trim();
  if (!trimmed) {
    return "VF";
  }
  return trimmed.slice(0, 2).toUpperCase();
}

export const USER_ROLE_LABEL: Record<UserRole, string> = {
  ADMIN: "管理员",
  USER: "普通用户",
};

export const USER_STATUS_LABEL: Record<UserStatus, string> = {
  ACTIVE: "正常",
  DISABLED: "已禁用",
};

export const MODEL_CAPABILITY_LABEL: Record<ModelCapabilityType, string> = {
  CHAT: "主对话",
  IMAGE: "图片生成",
  VIDEO: "视频生成",
};

export const MEDIA_PROTOCOL_LABEL: Record<MediaProtocol, string> = {
  OPENAI_RESPONSES: "OpenAI Responses SSE",
  OPENAI_IMAGES: "OpenAI Images",
  ARK_IMAGES: "方舟 Ark Images",
  ARK_VIDEO_TASKS: "方舟 Seedance 视频任务",
  DASHSCOPE_IMAGE_MESSAGES: "百炼万相图片（Messages）",
  DASHSCOPE_T2I: "百炼万相文生图",
  DASHSCOPE_VIDEO: "百炼万相视频",
  GATEWAY_CUSTOM: "自定义网关协议",
  STUB: "离线 STUB 桩",
};

export const EXECUTION_MODE_LABEL: Record<ExecutionMode, string> = {
  INLINE_RESULT: "同步/内联返回",
  REMOTE_TASK: "异步远端任务",
};

export const REASONING_EFFORT_LABEL: Record<ReasoningEffort, string> = {
  NONE: "关闭 (NONE)",
  MINIMAL: "极简 (MINIMAL)",
  LOW: "低 (LOW)",
  MEDIUM: "中 (MEDIUM)",
  HIGH: "高 (HIGH)",
};

export const REASONING_REPLAY_LABEL: Record<ReasoningReplayPolicy, string> = {
  ENCRYPTED: "仅回放加密推理 (ENCRYPTED)",
  ALL: "回放全部推理 (ALL)",
  NONE: "不回放推理 (NONE)",
};

export const FILE_KIND_LABEL: Record<ConversationFileKind, string> = {
  SCRIPT: "剧本",
  STORYBOARD: "分镜",
  PROMPT: "提示词",
  IMAGE: "图片",
  VIDEO: "视频",
  OTHER: "其他",
};

export const AGENT_RUN_STATUS_LABEL: Record<AgentRunStatus, string> = {
  PENDING: "排队中",
  RUNNING: "执行中",
  COMPLETED: "已完成",
  FAILED: "执行失败",
  CANCELLED: "已取消",
};

export const GENERATION_STATUS_LABEL: Record<GenerationStatus, string> = {
  PENDING: "待提交",
  SUBMITTING: "提交中",
  RUNNING: "生成中",
  ARCHIVING: "归档中",
  PROCESSING: "处理中",
  SUCCEEDED: "已交付",
  PARTIAL_SUCCEEDED: "部分交付",
  FAILED: "已失败",
  CANCELLED: "已取消",
  UNKNOWN: "结果待核对",
  ARCHIVE_FAILED: "归档失败",
  DELIVERY_FAILED: "交付失败",
};

export const GENERATION_BILLING_LABEL: Record<GenerationBillingStatus, string> = {
  RESERVED: "已预占",
  PENDING_RECONCILIATION: "待核账",
  SETTLED: "已结算",
  RELEASED: "已释放",
};

export const GENERATION_DELIVERY_LABEL: Record<GenerationDeliveryStatus, string> = {
  PENDING: "待归档",
  ARCHIVED: "已归档",
  PUBLISHED: "已发布",
  PARTIAL: "部分发布",
  FAILED: "交付失败",
};

export const GENERATION_CANCEL_LABEL: Record<GenerationCancelStatus, string> = {
  NONE: "未取消",
  REQUESTED: "已请求取消",
  CONFIRMED: "已确认取消",
  UNSUPPORTED: "不支持取消",
  TOO_LATE: "已出片无法取消",
  UNKNOWN: "取消状态未知",
};

export const COMPOSITION_STATUS_LABEL: Record<CompositionStatus, string> = {
  PENDING: "待启动",
  VALIDATING: "校验中",
  COMPOSING: "合成中",
  UPLOADING: "分片上传中",
  PUBLISHING: "发布中",
  SUCCEEDED: "成片已就绪",
  FAILED: "合成失败",
  CANCELLED: "已取消",
};

export const CREDIT_ENTRY_LABEL: Record<CreditLedgerEntryType, string> = {
  GRANT: "初始授予",
  RESERVE: "预占",
  SETTLE: "结算扣费",
  RELEASE: "释放预占",
  ADJUST: "人工调账",
  REDEEM: "兑换码充值",
};

export const CREDIT_ENTRY_HINT: Record<CreditLedgerEntryType, string> = {
  GRANT: "可用余额增加",
  RESERVE: "冻结，不影响总余额",
  SETTLE: "总余额与预占同时减少",
  RELEASE: "仅释放冻结",
  ADJUST: "管理员人工调账记录",
  REDEEM: "兑换码激活入账",
};

export const REDEMPTION_STATUS_LABEL: Record<RedemptionCodeStatus, string> = {
  ACTIVE: "有效",
  VOIDED: "已作废",
};

export const ALERT_CHANNEL_TYPE_LABEL: Record<AlertChannelType, string> = {
  EMAIL: "邮件 (SMTP)",
  WEBHOOK: "Webhook",
};

export const ALERT_SEVERITY_LABEL: Record<AlertSeverity, string> = {
  INFO: "提示 (INFO)",
  WARNING: "警告 (WARNING)",
  CRITICAL: "严重 (CRITICAL)",
};

export const ALERT_DELIVERY_STATUS_LABEL: Record<AlertDeliveryStatus, string> = {
  SENT: "已投递",
  FAILED: "投递失败",
  SKIPPED: "已跳过",
};
