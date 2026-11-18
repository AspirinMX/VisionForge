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
export type ReasoningEffort = "NONE" | "MINIMAL" | "LOW" | "MEDIUM" | "HIGH";
export type ReasoningReplayPolicy = "ENCRYPTED" | "ALL" | "NONE";

export interface ModelResponse {
  id: UUID;
  displayName: string;
  baseUrl: string;
  modelId: string;
  reasoningEffort: ReasoningEffort | null;
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

export interface CreateModelRequest {
  displayName: string;
  baseUrl: string;
  apiKey: string;
  modelId: string;
  reasoningEffort?: ReasoningEffort | null;
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

export type MessageRole = "USER" | "ASSISTANT" | "SYSTEM";
export type MessageStatus = "COMPLETED" | "FAILED";

export interface MessageResponse {
  id: UUID;
  role: MessageRole;
  content: string;
  sequenceNo: number;
  status: MessageStatus;
  senderUserId: UUID | null;
  createdAt: string;
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

export type AgentRunStatus = "PENDING" | "RUNNING" | "COMPLETED" | "FAILED";

export interface AgentRunResponse {
  id: UUID;
  conversationId: UUID;
  status: AgentRunStatus;
  assistantMessageId: UUID | null;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export type AgentStreamEvent =
  | { type: "accepted"; runId: UUID }
  | { type: "reasoning"; content: string }
  | { type: "reasoning_summary"; content: string }
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

export interface AssetResponse {
  id: UUID;
  conversationId: UUID;
  kind: AssetKind;
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
