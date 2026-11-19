export type UUID = string;

export interface ApiResponse<T> {
  code: string;
  message: string;
  data: T;
  traceId: string;
  timestamp: string;
}

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

// --- Identity & Auth ---

export type UserRole = "ADMIN" | "USER";
export type UserStatus = "ACTIVE" | "DISABLED";

export interface UserResponse {
  id: UUID;
  email: string;
  displayName: string;
  role: UserRole;
  status: UserStatus;
  createdAt: string;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  userId: UUID;
  email: string;
}

export interface LoginResponse {
  token: TokenResponse;
  user: UserResponse;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RefreshRequest {
  refreshToken: string;
}

export interface ChangeRoleRequest {
  role: UserRole;
}

export interface UpdateProfileRequest {
  displayName: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface AdminCreateUserRequest {
  email: string;
  password: string;
  displayName: string;
  role: UserRole;
}

export interface ChangeUserStatusRequest {
  status: UserStatus;
}

export interface AdminResetPasswordRequest {
  newPassword: string;
}

export interface RevokeSessionsResponse {
  revokedCount: number;
}

// --- Models ---

export type ModelCapabilityType = "CHAT" | "IMAGE" | "VIDEO";

export type MediaProtocol =
  | "OPENAI_RESPONSES"
  | "OPENAI_IMAGES"
  | "ARK_IMAGES"
  | "ARK_VIDEO_TASKS"
  | "DASHSCOPE_IMAGE_MESSAGES"
  | "DASHSCOPE_T2I"
  | "DASHSCOPE_VIDEO"
  | "GATEWAY_CUSTOM"
  | "STUB";

export type ExecutionMode = "INLINE_RESULT" | "REMOTE_TASK";
export type ReasoningEffort =
  | "NONE"
  | "LOW"
  | "MEDIUM"
  | "HIGH"
  | "XHIGH"
  | "MAX";
export type ReasoningReplayPolicy = "ENCRYPTED" | "ALL" | "NONE";

export interface ModelResponse {
  id: UUID;
  displayName: string;
  baseUrl: string;
  modelId: string;
  reasoningEffort: ReasoningEffort | null;
  supportedReasoningEfforts: ReasoningEffort[];
  reasoningReplay: ReasoningReplayPolicy;
  capabilityType: ModelCapabilityType;
  protocol: MediaProtocol;
  executionMode: ExecutionMode;
  priceUnits: number;
  supportsImageInput: boolean;
  maxInputTokens: number | null;
  maxOutputTokens: number | null;
  enabled: boolean;
  defaultModel: boolean;
  apiKeyConfigured: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface PublicModelResponse {
  id: UUID;
  displayName: string;
  modelId: string;
  capabilityType: ModelCapabilityType;
  protocol: MediaProtocol;
  executionMode: ExecutionMode;
  priceUnits: number;
  supportsImageInput: boolean;
  defaultModel: boolean;
  reasoningEffort: ReasoningEffort | null;
  supportedReasoningEfforts: ReasoningEffort[];
  maxInputTokens: number | null;
  maxOutputTokens: number | null;
}

export interface CreateModelRequest {
  displayName: string;
  baseUrl: string;
  apiKey: string;
  modelId: string;
  reasoningEffort?: ReasoningEffort | null;
  supportedReasoningEfforts?: ReasoningEffort[];
  reasoningReplay?: ReasoningReplayPolicy | null;
  maxInputTokens?: number | null;
  maxOutputTokens?: number | null;
  enabled?: boolean;
  defaultModel?: boolean;
  capabilityType?: ModelCapabilityType;
  protocol?: MediaProtocol;
  executionMode?: ExecutionMode;
  priceUnits?: number;
  supportsImageInput?: boolean;
}

export interface UpdateModelRequest {
  displayName?: string;
  baseUrl?: string;
  apiKey?: string;
  modelId?: string;
  reasoningEffort?: ReasoningEffort | null;
  supportedReasoningEfforts?: ReasoningEffort[];
  clearReasoningEffort?: boolean;
  reasoningReplay?: ReasoningReplayPolicy | null;
  maxInputTokens?: number | null;
  maxOutputTokens?: number | null;
  enabled?: boolean;
  defaultModel?: boolean;
  capabilityType?: ModelCapabilityType;
  protocol?: MediaProtocol;
  executionMode?: ExecutionMode;
  priceUnits?: number;
  supportsImageInput?: boolean;
}

// --- Conversations & Agent Runs ---

export interface ConversationResponse {
  id: UUID;
  title: string;
  modelDefinitionId: UUID | null;
  createdAt: string;
  updatedAt: string;
  lastMessageAt: string | null;
}

export interface CreateConversationRequest {
  title?: string;
}

export interface RenameConversationRequest {
  title: string;
}

export type MessageRole = "USER" | "ASSISTANT" | "SYSTEM";
export type MessageStatus = "COMPLETED" | "FAILED";
export type ToolInvocationStatus =
  | "RUNNING"
  | "WAITING"
  | "SUCCEEDED"
  | "FAILED";

export interface ToolCallProcessResponse {
  callId: string;
  toolName: string;
  action: string;
  target: string | null;
  status: ToolInvocationStatus;
  fileId?: UUID | null;
  taskId?: UUID | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
}

export interface AgentTurnProcessResponse {
  turnNo: number;
  reasoning: string | null;
  stepText: string | null;
  toolCalls: ToolCallProcessResponse[];
}

export interface AgentRunProcessResponse {
  runId: UUID;
  durationMs: number | null;
  turns: AgentTurnProcessResponse[];
}

export interface MessageResponse {
  id: UUID;
  role: MessageRole;
  content: string;
  sequenceNo: number;
  status: MessageStatus;
  senderUserId: UUID | null;
  createdAt: string;
  assetIds: UUID[];
  generationTaskIds: UUID[];
  process?: AgentRunProcessResponse | null;
}

export interface SendMessageRequest {
  content: string;
  assetIds?: UUID[];
}

export interface AgentTurnResponse {
  runId: UUID;
  workflowId: string;
  userMessageId: UUID;
}

export type AgentRunStatus =
  | "PENDING"
  | "RUNNING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED";

export interface AgentRunResponse {
  id: UUID;
  conversationId: UUID;
  status: AgentRunStatus;
  assistantMessageId: UUID | null;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  process?: AgentRunProcessResponse | null;
}

export type AgentStreamEvent =
  | { type: "accepted"; runId: UUID }
  | { type: "turn_start"; turnNo: number }
  | { type: "reasoning"; content: string }
  | { type: "reasoning_summary"; content: string }
  | {
      type: "tool_start" | "tool_waiting" | "tool_done";
      turnNo: number;
      callId: string;
      toolName: string;
      action: string;
      target: string | null;
      status: ToolInvocationStatus;
      fileId?: UUID | null;
      taskId?: UUID | null;
      message?: string | null;
    }
  | { type: "delta"; content: string }
  | { type: "done"; assistantMessageId: UUID }
  | { type: "error"; message: string };

// --- Conversation Files & Assets ---

export type ConversationFileKind =
  | "SCRIPT"
  | "STORYBOARD"
  | "PROMPT"
  | "IMAGE"
  | "VIDEO"
  | "OTHER";

export type ConversationFileStorageKind = "DB_TEXT" | "OBJECT_STORAGE";
export type ConversationFileStatus = "DRAFT" | "READY" | "ARCHIVED";
export type ConversationFileSource =
  | "USER_UPLOAD"
  | "AGENT"
  | "TOOL"
  | "GENERATED";

export interface ConversationFileResponse {
  id: UUID;
  conversationId: UUID;
  kind: ConversationFileKind;
  title: string;
  path: string | null;
  mimeType: string;
  storageKind: ConversationFileStorageKind;
  currentVersion: number;
  status: ConversationFileStatus;
  createdAt: string;
  updatedAt: string;
}

export interface ConversationFileVersionResponse {
  id: UUID;
  version: number;
  content: string | null;
  sizeBytes: number | null;
  checksum: string | null;
  source: ConversationFileSource;
  assetId: UUID | null;
  createdAt: string;
}

export interface CreateConversationFileRequest {
  kind: ConversationFileKind;
  title: string;
  path?: string | null;
  content: string;
}

export interface UpdateConversationFileRequest {
  baseVersion: number;
  content: string;
  title?: string;
}

export type AssetKind = "IMAGE" | "VIDEO" | "DOCUMENT" | "OTHER";
export type AssetStatus = "PENDING" | "UPLOADING" | "READY" | "FAILED" | "DELETED";

export interface CreateUploadRequest {
  kind: AssetKind;
  filename: string;
  mimeType: string;
  sizeBytes: number;
}

export interface UploadTicketResponse {
  uploadSessionId: UUID;
  assetId: UUID;
  uploadUrl: string;
  uploadUrlExpiresAt: string;
  sessionExpiresAt: string;
  requiredHeaders: Record<string, string>;
}

export type AssetSource = "UPLOAD" | "GENERATION" | "DERIVED";

export interface AssetResponse {
  id: UUID;
  conversationId: UUID;
  kind: AssetKind;
  source: AssetSource;
  mimeType: string;
  sizeBytes: number;
  checksum: string | null;
  width: number | null;
  height: number | null;
  durationMs: number | null;
  status: AssetStatus;
  createdAt: string;
}

export interface AssetDownloadUrlResponse {
  assetId: UUID;
  downloadUrl: string;
  expiresAt: string;
}

// --- Generation Tasks ---

export type GenerationType = "IMAGE" | "VIDEO";

export type GenerationStatus =
  | "PENDING"
  | "SUBMITTING"
  | "RUNNING"
  | "ARCHIVING"
  | "PROCESSING"
  | "SUCCEEDED"
  | "PARTIAL_SUCCEEDED"
  | "FAILED"
  | "CANCELLED"
  | "UNKNOWN"
  | "ARCHIVE_FAILED"
  | "DELIVERY_FAILED";

export type GenerationDeliveryStatus =
  | "PENDING"
  | "ARCHIVED"
  | "PUBLISHED"
  | "PARTIAL"
  | "FAILED";

export type GenerationBillingStatus =
  | "RESERVED"
  | "PENDING_RECONCILIATION"
  | "SETTLED"
  | "RELEASED";

export type GenerationCancelStatus =
  | "NONE"
  | "REQUESTED"
  | "CONFIRMED"
  | "UNSUPPORTED"
  | "TOO_LATE"
  | "UNKNOWN";

export type GenerationInputRole =
  | "REFERENCE"
  | "INIT_IMAGE"
  | "MASK"
  | "FIRST_FRAME"
  | "LAST_FRAME";

export type GenerationOutputStatus =
  | "PENDING"
  | "ARCHIVING"
  | "ARCHIVED"
  | "PUBLISHED"
  | "FAILED";

export interface GenerationInputRequest {
  assetId: UUID;
  fileVersionId?: UUID | null;
  role?: GenerationInputRole | null;
}

export interface CreateGenerationTaskRequest {
  conversationId: UUID;
  type: GenerationType;
  modelDefinitionId?: UUID | null;
  prompt: string;
  parameters?: Record<string, unknown> | null;
  inputs?: GenerationInputRequest[];
  sourceTaskId?: UUID | null;
  shotId?: string | null;
  storyboardFileId?: UUID | null;
  storyboardVersion?: number | null;
}

export interface GenerationInputResponse {
  assetId: UUID;
  fileVersionId: UUID | null;
  role: GenerationInputRole;
  ordinal: number;
}

export interface GenerationOutputResponse {
  outputIndex: number;
  status: GenerationOutputStatus;
  assetId: UUID | null;
  fileId: UUID | null;
  fileVersionId: UUID | null;
  errorMessage: string | null;
}

export interface GenerationTaskResponse {
  taskId: UUID;
  conversationId: UUID;
  shotId: string | null;
  storyboardFileId: UUID | null;
  storyboardVersion: number | null;
  type: GenerationType;
  modelDefinitionId: UUID;
  prompt: string;
  parameters: string;
  status: GenerationStatus;
  providerStatus: string | null;
  deliveryStatus: GenerationDeliveryStatus;
  billingStatus: GenerationBillingStatus;
  cancelStatus: GenerationCancelStatus;
  priceUnits: number;
  outputFileId: UUID | null;
  errorMessage: string | null;
  sourceTaskId: UUID | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  inputs: GenerationInputResponse[];
  outputs: GenerationOutputResponse[];
}

// --- Composition Tasks ---

export type CompositionStatus =
  | "PENDING"
  | "VALIDATING"
  | "COMPOSING"
  | "UPLOADING"
  | "PUBLISHING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED";

export interface CompositionSpec {
  width: number;
  height: number;
  fps: number;
  videoCodec: string;
  preset: string;
  crf: number;
}

export interface CreateCompositionTaskRequest {
  conversationId: UUID;
  storyboardFileId: UUID;
  storyboardVersion?: number | null;
  outputSpec?: Partial<CompositionSpec> | null;
}

export interface CompositionInputResponse {
  shotId: string;
  generationTaskId: UUID;
  assetId: UUID;
  startSeconds: number;
  endSeconds: number;
  ordinal: number;
}

export interface CompositionTaskResponse {
  taskId: UUID;
  conversationId: UUID;
  storyboardFileId: UUID;
  storyboardVersion: number;
  outputSpec: string;
  status: CompositionStatus;
  outputAssetId: UUID | null;
  outputFileId: UUID | null;
  outputVersionId: UUID | null;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  inputs: CompositionInputResponse[];
}

// --- Account & Credit ---

export type CreditLedgerEntryType =
  | "GRANT"
  | "RESERVE"
  | "SETTLE"
  | "RELEASE"
  | "ADJUST"
  | "REDEEM";

export interface CreditSummaryResponse {
  balanceUnits: number;
  reservedUnits: number;
  availableUnits: number;
  updatedAt: string;
}

export interface CreditLedgerEntryResponse {
  id: UUID;
  entryType: CreditLedgerEntryType;
  amountUnits: number;
  reservationId: UUID | null;
  taskId: UUID | null;
  taskType: GenerationType | null;
  taskStatus: GenerationStatus | null;
  remark: string | null;
  createdAt: string;
}

export interface RedeemCodeRequest {
  code: string;
}

export interface RedeemCodeResponse {
  ledgerId: UUID;
  code: string;
  amountUnits: number;
  newBalanceUnits: number;
  newAvailableUnits: number;
}

export interface CreditUsageBucket {
  billingStatus: GenerationBillingStatus;
  taskCount: number;
  priceUnits: number;
}

export interface CreditUsageResponse {
  window: number;
  totalPriceUnits: number;
  buckets: CreditUsageBucket[];
}

// --- Storyboard JSON Schema (PROPOSAL §13.2 & StoryboardValidator.java) ---

export type StoryboardShotSize =
  | "EXTREME_WIDE"
  | "WIDE"
  | "FULL"
  | "MEDIUM"
  | "CLOSE"
  | "EXTREME_CLOSE";

export type StoryboardCamera =
  | "STATIC"
  | "PUSH"
  | "PULL"
  | "PAN"
  | "TRACK"
  | "FOLLOW"
  | "CRANE";

export type StoryboardShotStatus =
  | "DRAFT"
  | "FIRST_FRAME_GENERATING"
  | "FIRST_FRAME_READY"
  | "VIDEO_GENERATING"
  | "VIDEO_CANDIDATES"
  | "APPROVED"
  | "LOCKED"
  | "FAILED";

export type StoryboardReferenceRole =
  | "CHARACTER"
  | "PRODUCT"
  | "SCENE"
  | "STYLE";

export interface StoryboardReference {
  assetId: UUID;
  version: number;
  role: StoryboardReferenceRole;
}

export interface StoryboardAssetRef {
  assetId: UUID;
  fileId?: UUID | null;
  version: number;
}

export interface StoryboardVideoCandidate {
  taskId: UUID;
  assetId: UUID | null;
  fileId: UUID | null;
  version: number | null;
  status: string;
}

export interface StoryboardVideoSelection {
  taskId: UUID;
  assetId: UUID;
  fileId?: UUID | null;
  version: number;
}

export interface StoryboardRange {
  startSeconds: number;
  endSeconds: number;
}

export interface StoryboardShot {
  id: string;
  order: number;
  sceneId?: string | null;
  durationSeconds: number;
  visual?: string | null;
  firstFramePrompt: string;
  videoPrompt: string;
  shotSize: StoryboardShotSize;
  camera: StoryboardCamera;
  dialogue?: string | null;
  voiceOver?: string | null;
  sound?: string | null;
  references?: StoryboardReference[];
  firstFrame?: StoryboardAssetRef | null;
  videoCandidates?: StoryboardVideoCandidate[];
  selectedVideo?: StoryboardVideoSelection | null;
  usedRange?: StoryboardRange | null;
  status: StoryboardShotStatus;
}

export interface StoryboardDocument {
  schemaVersion: number;
  title: string;
  aspectRatio?: string | null;
  language?: string | null;
  style?: string | null;
  shots: StoryboardShot[];
}

// --- Admin Overview, Model Probe, Billing, Audit, Nacos Config & Alerts ---

export interface ModelTestResponse {
  ok: boolean;
  reachable: boolean;
  statusCode: number | null;
  latencyMs: number;
  protocol: string;
  detail: string;
}

export interface AdminOverviewResponse {
  totalUsers: number;
  activeUsers: number;
  disabledUsers: number;
  activeConversations: number;
  enabledModels: number;
  activeAgentRuns: number;
  failedAgentRuns: number;
  activeGenerationTasks: number;
  archiveFailedGenerationTasks: number;
  pendingReconciliationTasks: number;
  activeCompositionTasks: number;
  failedCompositionTasks: number;
  failedWorkflowStarts: number;
}

export interface AdminDashboardTotals {
  settledCredits: number;
  todayCredits: number;
  inputTokens: number;
  outputTokens: number;
  successRate: number | null;
  activeUsers: number;
  newUsers: number;
}

export interface AdminDashboardDeltas {
  settledCredits: number | null;
  totalTokens: number | null;
  activeUsers: number | null;
  successRatePoints: number | null;
}

export interface AdminDashboardDailyPoint {
  date: string;
  inputTokens: number;
  outputTokens: number;
  invocations: number;
  settledCredits: number;
  succeeded: number;
  failed: number;
  cancelled: number;
}

export interface AdminDashboardLeaderboardEntry {
  userId: UUID;
  displayName: string;
  value: number;
}

export interface AdminDashboardAttention {
  archiveFailed: number;
  pendingReconciliation: number;
  failedWorkflowStarts: number;
  failedTasks24h: number;
  failedCompositionTasks: number;
}

export interface AdminDashboardLive {
  activeAgentRuns: number;
  activeGenerationTasks: number;
  activeCompositionTasks: number;
}

export interface AdminDashboardResponse {
  rangeDays: number;
  generatedAt: string;
  totals: AdminDashboardTotals;
  deltas: AdminDashboardDeltas;
  daily: AdminDashboardDailyPoint[];
  topUsersByCredits: AdminDashboardLeaderboardEntry[];
  topUsersByTokens: AdminDashboardLeaderboardEntry[];
  attention: AdminDashboardAttention;
  live: AdminDashboardLive;
}

export type RedemptionCodeStatus = "ACTIVE" | "VOIDED";

export interface AdjustCreditRequest {
  deltaUnits: number;
  idempotencyKey: string;
  remark?: string;
}

export interface AdminCreditLedgerResponse {
  id: UUID;
  userId: UUID;
  reservationId: UUID | null;
  entryType: CreditLedgerEntryType;
  amountUnits: number;
  businessKey: string | null;
  remark: string | null;
  createdAt: string;
}

export interface RedemptionCodeResponse {
  id: UUID;
  batchName: string;
  code: string;
  amountUnits: number;
  maxUses: number;
  redeemedCount: number;
  perUserLimit: number;
  status: RedemptionCodeStatus;
  expiresAt: string | null;
  createdBy: UUID;
  createdAt: string;
  updatedAt: string;
}

export interface CreateRedemptionBatchRequest {
  batchName: string;
  count: number;
  amountUnits: number;
  maxUsesPerCode: number;
  perUserLimit: number;
  customPrefix?: string;
  expiresAt?: string | null;
}

export interface AuditLogResponse {
  id: UUID;
  type: string;
  subType: string;
  bizNo: string | null;
  operatorId: UUID | null;
  operatorName: string | null;
  success: boolean;
  detail: string | null;
  extra: string | null;
  traceId: string | null;
  errorMessage: string | null;
  createdAt: string;
}

export interface ConfigEntryResponse {
  dataId: string;
  group: string;
  namespace: string;
  description: string;
  content: string;
  defaultContent: string;
  md5: string;
  source: string;
  nacosEnabled: boolean;
  nacosConnected: boolean;
  updatedAt: string | null;
}

export interface ConfigCenterOverviewResponse {
  enabled: boolean;
  connected: boolean;
  serverAddr: string;
  namespace: string;
  group: string;
  source: string;
  entries: ConfigEntryResponse[];
}

export interface UpdateConfigRequest {
  content: string;
}

export type AlertChannelType = "EMAIL" | "WEBHOOK";
export type AlertSeverity = "INFO" | "WARNING" | "CRITICAL";
export type AlertDeliveryStatus = "SENT" | "FAILED" | "SKIPPED";

export interface AlertChannelResponse {
  id: UUID;
  name: string;
  channelType: AlertChannelType;
  target: string;
  secretConfigured: boolean;
  subscribedEvents: string;
  minSeverity: AlertSeverity;
  enabled: boolean;
  createdBy: UUID | null;
  createdAt: string;
  updatedAt: string;
}

export interface AlertDeliveryResponse {
  id: UUID;
  channelId: UUID;
  channelName: string;
  channelType: AlertChannelType;
  eventType: string;
  severity: AlertSeverity;
  title: string;
  summary: string | null;
  bizNo: string | null;
  traceId: string | null;
  status: AlertDeliveryStatus;
  httpStatus: number | null;
  attemptCount: number;
  errorMessage: string | null;
  createdAt: string;
}

export interface CreateAlertChannelRequest {
  name: string;
  channelType: AlertChannelType;
  target: string;
  secret?: string;
  subscribedEvents?: string;
  minSeverity?: AlertSeverity;
  enabled?: boolean;
}

export interface UpdateAlertChannelRequest {
  name?: string;
  channelType?: AlertChannelType;
  target?: string;
  secret?: string;
  subscribedEvents?: string;
  minSeverity?: AlertSeverity;
  enabled?: boolean;
}
