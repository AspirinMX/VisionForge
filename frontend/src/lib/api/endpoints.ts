import {
  apiDelete,
  apiDownload,
  apiGet,
  apiPatch,
  apiPost,
  apiPut,
  createIdempotencyKey,
} from "./client";
import { consumeAgentStream, type StreamHandlers } from "./sse";
import type {
  AdjustCreditRequest,
  AdminCreateUserRequest,
  AdminCreditLedgerResponse,
  AdminOverviewResponse,
  AdminResetPasswordRequest,
  AgentRunResponse,
  AgentRunStatus,
  AgentTurnResponse,
  AlertChannelResponse,
  AlertDeliveryResponse,
  AlertDeliveryStatus,
  AssetDownloadUrlResponse,
  AssetKind,
  AssetResponse,
  AssetSource,
  AuditLogResponse,
  ChangePasswordRequest,
  ChangeRoleRequest,
  ChangeUserStatusRequest,
  CompositionStatus,
  CompositionTaskResponse,
  ConfigCenterOverviewResponse,
  ConfigEntryResponse,
  ConversationFileKind,
  ConversationFileResponse,
  ConversationFileVersionResponse,
  ConversationResponse,
  CreateAlertChannelRequest,
  CreateCompositionTaskRequest,
  CreateConversationFileRequest,
  CreateConversationRequest,
  CreateGenerationTaskRequest,
  CreateModelRequest,
  CreateRedemptionBatchRequest,
  CreateUploadRequest,
  CreditLedgerEntryResponse,
  CreditLedgerEntryType,
  CreditSummaryResponse,
  CreditUsageResponse,
  GenerationTaskResponse,
  LoginRequest,
  LoginResponse,
  MessageResponse,
  ModelCapabilityType,
  ModelResponse,
  ModelTestResponse,
  PageResult,
  PublicModelResponse,
  RedeemCodeRequest,
  RedeemCodeResponse,
  RedemptionCodeResponse,
  RedemptionCodeStatus,
  RefreshRequest,
  RegisterRequest,
  RenameConversationRequest,
  RevokeSessionsResponse,
  SendMessageRequest,
  TokenResponse,
  UpdateAlertChannelRequest,
  UpdateConfigRequest,
  UpdateConversationFileRequest,
  UpdateModelRequest,
  UpdateProfileRequest,
  UploadTicketResponse,
  UserResponse,
  UserRole,
  UserStatus,
  UUID,
} from "./types";

export const authApi = {
  register(request: RegisterRequest): Promise<UserResponse> {
    return apiPost<UserResponse>("/api/v1/auth/register", request, {
      auth: false,
    });
  },
  login(request: LoginRequest): Promise<LoginResponse> {
    return apiPost<LoginResponse>("/api/v1/auth/login", request, {
      auth: false,
    });
  },
  refresh(request: RefreshRequest): Promise<TokenResponse> {
    return apiPost<TokenResponse>("/api/v1/auth/refresh", request, {
      auth: false,
    });
  },
  logout(request: RefreshRequest): Promise<void> {
    return apiPost<void>("/api/v1/auth/logout", request, {
      auth: false,
    });
  },
  me(): Promise<UserResponse> {
    return apiGet<UserResponse>("/api/v1/auth/me");
  },
  updateProfile(request: UpdateProfileRequest): Promise<UserResponse> {
    return apiPatch<UserResponse>("/api/v1/auth/me", request);
  },
  changePassword(request: ChangePasswordRequest): Promise<void> {
    return apiPost<void>("/api/v1/auth/change-password", request);
  },
};

export const conversationsApi = {
  list(): Promise<ConversationResponse[]> {
    return apiGet<ConversationResponse[]>("/api/v1/conversations");
  },
  create(request: CreateConversationRequest): Promise<ConversationResponse> {
    return apiPost<ConversationResponse>("/api/v1/conversations", request);
  },
  get(conversationId: UUID): Promise<ConversationResponse> {
    return apiGet<ConversationResponse>(`/api/v1/conversations/${conversationId}`);
  },
  rename(
    conversationId: UUID,
    request: RenameConversationRequest
  ): Promise<ConversationResponse> {
    return apiPatch<ConversationResponse>(
      `/api/v1/conversations/${conversationId}`,
      request
    );
  },
  delete(conversationId: UUID): Promise<void> {
    return apiDelete<void>(`/api/v1/conversations/${conversationId}`);
  },
  messages(conversationId: UUID): Promise<MessageResponse[]> {
    return apiGet<MessageResponse[]>(
      `/api/v1/conversations/${conversationId}/messages`
    );
  },
  send(
    conversationId: UUID,
    request: SendMessageRequest
  ): Promise<AgentTurnResponse> {
    return apiPost<AgentTurnResponse>(
      `/api/v1/conversations/${conversationId}/messages`,
      request
    );
  },
  sendStream(
    conversationId: UUID,
    request: SendMessageRequest,
    handlers: StreamHandlers,
    signal?: AbortSignal
  ): Promise<void> {
    return consumeAgentStream({
      path: `/api/v1/conversations/${conversationId}/messages/stream`,
      body: request,
      handlers,
      signal,
    });
  },
  getAgentRun(runId: UUID): Promise<AgentRunResponse> {
    return apiGet<AgentRunResponse>(`/api/v1/agent-runs/${runId}`);
  },
};

export const agentRunsApi = {
  list(params?: {
    conversationId?: UUID;
    status?: AgentRunStatus[];
    limit?: number;
  }): Promise<AgentRunResponse[]> {
    return apiGet<AgentRunResponse[]>("/api/v1/agent-runs", {
      query: {
        conversationId: params?.conversationId,
        status: params?.status?.length ? params.status.join(",") : undefined,
        limit: params?.limit,
      },
    });
  },
  cancel(runId: UUID): Promise<AgentRunResponse> {
    return apiPost<AgentRunResponse>(`/api/v1/agent-runs/${runId}/cancel`);
  },
};

export const filesApi = {
  list(
    conversationId: UUID,
    params?: { kind?: ConversationFileKind; limit?: number }
  ): Promise<ConversationFileResponse[]> {
    return apiGet<ConversationFileResponse[]>(
      `/api/v1/conversations/${conversationId}/files`,
      { query: params }
    );
  },
  search(
    conversationId: UUID,
    params?: { q?: string; kind?: ConversationFileKind; limit?: number }
  ): Promise<ConversationFileResponse[]> {
    return apiGet<ConversationFileResponse[]>(
      `/api/v1/conversations/${conversationId}/files/search`,
      { query: params }
    );
  },
  create(
    conversationId: UUID,
    request: CreateConversationFileRequest
  ): Promise<ConversationFileResponse> {
    return apiPost<ConversationFileResponse>(
      `/api/v1/conversations/${conversationId}/files`,
      request
    );
  },
  get(conversationId: UUID, fileId: UUID): Promise<ConversationFileResponse> {
    return apiGet<ConversationFileResponse>(
      `/api/v1/conversations/${conversationId}/files/${fileId}`
    );
  },
  update(
    conversationId: UUID,
    fileId: UUID,
    request: UpdateConversationFileRequest
  ): Promise<ConversationFileResponse> {
    return apiPatch<ConversationFileResponse>(
      `/api/v1/conversations/${conversationId}/files/${fileId}`,
      request
    );
  },
  versions(
    conversationId: UUID,
    fileId: UUID,
    limit?: number
  ): Promise<ConversationFileVersionResponse[]> {
    return apiGet<ConversationFileVersionResponse[]>(
      `/api/v1/conversations/${conversationId}/files/${fileId}/versions`,
      { query: { limit } }
    );
  },
  version(
    conversationId: UUID,
    fileId: UUID,
    version: number
  ): Promise<ConversationFileVersionResponse> {
    return apiGet<ConversationFileVersionResponse>(
      `/api/v1/conversations/${conversationId}/files/${fileId}/versions/${version}`
    );
  },
};

export const assetsApi = {
  list(
    conversationId: UUID,
    params?: { kind?: AssetKind; source?: AssetSource; limit?: number }
  ): Promise<AssetResponse[]> {
    return apiGet<AssetResponse[]>(
      `/api/v1/conversations/${conversationId}/assets`,
      { query: params }
    );
  },
  resolveInConversation(
    conversationId: UUID,
    assetId: UUID
  ): Promise<AssetResponse> {
    return apiGet<AssetResponse>(
      `/api/v1/conversations/${conversationId}/assets/${assetId}`
    );
  },
  createUpload(
    conversationId: UUID,
    request: CreateUploadRequest
  ): Promise<UploadTicketResponse> {
    return apiPost<UploadTicketResponse>(
      `/api/v1/conversations/${conversationId}/uploads`,
      request
    );
  },
  completeUpload(
    conversationId: UUID,
    uploadSessionId: UUID
  ): Promise<AssetResponse> {
    return apiPost<AssetResponse>(
      `/api/v1/conversations/${conversationId}/uploads/${uploadSessionId}/complete`
    );
  },
  get(assetId: UUID): Promise<AssetResponse> {
    return apiGet<AssetResponse>(`/api/v1/assets/${assetId}`);
  },
  downloadUrl(assetId: UUID): Promise<AssetDownloadUrlResponse> {
    return apiGet<AssetDownloadUrlResponse>(
      `/api/v1/assets/${assetId}/download-url`
    );
  },
};

export const generationApi = {
  create(
    request: CreateGenerationTaskRequest,
    idempotencyKey = createIdempotencyKey()
  ): Promise<GenerationTaskResponse> {
    return apiPost<GenerationTaskResponse>("/api/v1/generation-tasks", request, {
      idempotencyKey,
    });
  },
  list(params?: {
    conversationId?: UUID;
    limit?: number;
  }): Promise<GenerationTaskResponse[]> {
    return apiGet<GenerationTaskResponse[]>("/api/v1/generation-tasks", {
      query: params,
    });
  },
  get(taskId: UUID): Promise<GenerationTaskResponse> {
    return apiGet<GenerationTaskResponse>(`/api/v1/generation-tasks/${taskId}`);
  },
  cancel(taskId: UUID): Promise<GenerationTaskResponse> {
    return apiPost<GenerationTaskResponse>(
      `/api/v1/generation-tasks/${taskId}/cancel`
    );
  },
  retryArchive(taskId: UUID): Promise<GenerationTaskResponse> {
    return apiPost<GenerationTaskResponse>(
      `/api/v1/generation-tasks/${taskId}/retry-archive`
    );
  },
};

export const compositionApi = {
  list(params?: {
    conversationId?: UUID;
    status?: CompositionStatus;
    limit?: number;
  }): Promise<CompositionTaskResponse[]> {
    return apiGet<CompositionTaskResponse[]>("/api/v1/composition-tasks", {
      query: params,
    });
  },
  create(
    request: CreateCompositionTaskRequest
  ): Promise<CompositionTaskResponse> {
    return apiPost<CompositionTaskResponse>(
      "/api/v1/composition-tasks",
      request
    );
  },
  get(taskId: UUID): Promise<CompositionTaskResponse> {
    return apiGet<CompositionTaskResponse>(`/api/v1/composition-tasks/${taskId}`);
  },
  cancel(taskId: UUID): Promise<CompositionTaskResponse> {
    return apiPost<CompositionTaskResponse>(
      `/api/v1/composition-tasks/${taskId}/cancel`
    );
  },
  retry(taskId: UUID): Promise<CompositionTaskResponse> {
    return apiPost<CompositionTaskResponse>(
      `/api/v1/composition-tasks/${taskId}/retry`
    );
  },
};

export const modelsApi = {
  list(params?: {
    capabilityType?: ModelCapabilityType;
  }): Promise<PublicModelResponse[]> {
    return apiGet<PublicModelResponse[]>("/api/v1/models", {
      query: params,
    });
  },
};

export const accountApi = {
  credit(): Promise<CreditSummaryResponse> {
    return apiGet<CreditSummaryResponse>("/api/v1/account/credit");
  },
  ledger(params?: {
    limit?: number;
    before?: UUID;
  }): Promise<CreditLedgerEntryResponse[]> {
    return apiGet<CreditLedgerEntryResponse[]>("/api/v1/account/credit/ledger", {
      query: params,
    });
  },
  exportLedgerCsv(maxRows = 2000): Promise<string> {
    return apiDownload("/api/v1/account/credit/ledger/export", {
      query: { maxRows },
      fallbackFilename: "credit-ledger.csv",
    });
  },
  redeemCode(request: RedeemCodeRequest): Promise<RedeemCodeResponse> {
    return apiPost<RedeemCodeResponse>("/api/v1/account/credit/redeem", request);
  },
  usage(window = 100): Promise<CreditUsageResponse> {
    return apiGet<CreditUsageResponse>("/api/v1/account/usage", {
      query: { window },
    });
  },
};

export const adminOverviewApi = {
  get(): Promise<AdminOverviewResponse> {
    return apiGet<AdminOverviewResponse>("/api/v1/admin/overview");
  },
};

export const adminModelsApi = {
  list(): Promise<ModelResponse[]> {
    return apiGet<ModelResponse[]>("/api/v1/admin/models");
  },
  get(id: UUID): Promise<ModelResponse> {
    return apiGet<ModelResponse>(`/api/v1/admin/models/${id}`);
  },
  create(request: CreateModelRequest): Promise<ModelResponse> {
    return apiPost<ModelResponse>("/api/v1/admin/models", request);
  },
  update(id: UUID, request: UpdateModelRequest): Promise<ModelResponse> {
    return apiPatch<ModelResponse>(`/api/v1/admin/models/${id}`, request);
  },
  setDefault(id: UUID): Promise<ModelResponse> {
    return apiPost<ModelResponse>(`/api/v1/admin/models/${id}/default`);
  },
  testConnection(id: UUID): Promise<ModelTestResponse> {
    return apiPost<ModelTestResponse>(`/api/v1/admin/models/${id}/test`);
  },
};

export const adminUsersApi = {
  list(params?: {
    page?: number;
    size?: number;
    q?: string;
    role?: UserRole;
    status?: UserStatus;
  }): Promise<PageResult<UserResponse>> {
    return apiGet<PageResult<UserResponse>>("/api/v1/admin/users", {
      query: params,
    });
  },
  create(request: AdminCreateUserRequest): Promise<UserResponse> {
    return apiPost<UserResponse>("/api/v1/admin/users", request);
  },
  changeRole(id: UUID, request: ChangeRoleRequest): Promise<UserResponse> {
    return apiPut<UserResponse>(`/api/v1/admin/users/${id}/role`, request);
  },
  changeStatus(id: UUID, request: ChangeUserStatusRequest): Promise<UserResponse> {
    return apiPut<UserResponse>(`/api/v1/admin/users/${id}/status`, request);
  },
  resetPassword(id: UUID, request: AdminResetPasswordRequest): Promise<void> {
    return apiPost<void>(`/api/v1/admin/users/${id}/reset-password`, request);
  },
  revokeSessions(id: UUID): Promise<RevokeSessionsResponse> {
    return apiPost<RevokeSessionsResponse>(
      `/api/v1/admin/users/${id}/revoke-sessions`
    );
  },
};

export const adminBillingApi = {
  adjustCredit(
    userId: UUID,
    request: AdjustCreditRequest
  ): Promise<AdminCreditLedgerResponse> {
    return apiPost<AdminCreditLedgerResponse>(
      `/api/v1/admin/users/${userId}/credit-adjust`,
      request
    );
  },
  ledger(params?: {
    page?: number;
    size?: number;
    userId?: UUID;
    entryType?: CreditLedgerEntryType;
  }): Promise<PageResult<AdminCreditLedgerResponse>> {
    return apiGet<PageResult<AdminCreditLedgerResponse>>(
      "/api/v1/admin/billing/ledger",
      { query: params }
    );
  },
  listRedemptions(params?: {
    page?: number;
    size?: number;
    batchName?: string;
    status?: RedemptionCodeStatus;
  }): Promise<PageResult<RedemptionCodeResponse>> {
    return apiGet<PageResult<RedemptionCodeResponse>>(
      "/api/v1/admin/billing/redemptions",
      { query: params }
    );
  },
  createRedemptions(
    request: CreateRedemptionBatchRequest
  ): Promise<RedemptionCodeResponse[]> {
    return apiPost<RedemptionCodeResponse[]>(
      "/api/v1/admin/billing/redemptions",
      request
    );
  },
  voidRedemption(id: UUID): Promise<RedemptionCodeResponse> {
    return apiPost<RedemptionCodeResponse>(
      `/api/v1/admin/billing/redemptions/${id}/void`
    );
  },
};

export const adminAuditLogsApi = {
  list(params?: {
    page?: number;
    size?: number;
    type?: string;
    subType?: string;
    operatorId?: UUID;
    bizNo?: string;
    traceId?: string;
    success?: boolean;
    from?: string;
    to?: string;
  }): Promise<PageResult<AuditLogResponse>> {
    return apiGet<PageResult<AuditLogResponse>>("/api/v1/admin/audit-logs", {
      query: params,
    });
  },
  exportCsv(params?: {
    type?: string;
    subType?: string;
    operatorId?: UUID;
    bizNo?: string;
    traceId?: string;
    success?: boolean;
    from?: string;
    to?: string;
    maxRows?: number;
  }): Promise<string> {
    return apiDownload("/api/v1/admin/audit-logs/export", {
      query: params,
      fallbackFilename: "audit-logs.csv",
    });
  },
};

export const adminConfigsApi = {
  list(): Promise<ConfigCenterOverviewResponse> {
    return apiGet<ConfigCenterOverviewResponse>("/api/v1/admin/configs");
  },
  get(dataId: string): Promise<ConfigEntryResponse> {
    return apiGet<ConfigEntryResponse>(
      `/api/v1/admin/configs/${encodeURIComponent(dataId)}`
    );
  },
  publish(
    dataId: string,
    request: UpdateConfigRequest
  ): Promise<ConfigEntryResponse> {
    return apiPut<ConfigEntryResponse>(
      `/api/v1/admin/configs/${encodeURIComponent(dataId)}`,
      request
    );
  },
  reset(dataId: string): Promise<ConfigEntryResponse> {
    return apiPost<ConfigEntryResponse>(
      `/api/v1/admin/configs/${encodeURIComponent(dataId)}/reset`
    );
  },
};

export const adminAlertsApi = {
  listChannels(): Promise<AlertChannelResponse[]> {
    return apiGet<AlertChannelResponse[]>("/api/v1/admin/alerts/channels");
  },
  createChannel(
    request: CreateAlertChannelRequest
  ): Promise<AlertChannelResponse> {
    return apiPost<AlertChannelResponse>(
      "/api/v1/admin/alerts/channels",
      request
    );
  },
  updateChannel(
    id: UUID,
    request: UpdateAlertChannelRequest
  ): Promise<AlertChannelResponse> {
    return apiPatch<AlertChannelResponse>(
      `/api/v1/admin/alerts/channels/${id}`,
      request
    );
  },
  deleteChannel(id: UUID): Promise<void> {
    return apiDelete<void>(`/api/v1/admin/alerts/channels/${id}`);
  },
  testChannel(id: UUID): Promise<AlertDeliveryResponse> {
    return apiPost<AlertDeliveryResponse>(
      `/api/v1/admin/alerts/channels/${id}/test`
    );
  },
  listDeliveries(params?: {
    page?: number;
    size?: number;
    channelId?: UUID;
    status?: AlertDeliveryStatus;
    eventType?: string;
  }): Promise<PageResult<AlertDeliveryResponse>> {
    return apiGet<PageResult<AlertDeliveryResponse>>(
      "/api/v1/admin/alerts/deliveries",
      { query: params }
    );
  },
};
