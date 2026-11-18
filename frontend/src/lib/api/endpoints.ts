import {
  apiGet,
  apiPatch,
  apiPost,
  apiPut,
  createIdempotencyKey,
} from "./client";
import { consumeAgentStream, type StreamHandlers } from "./sse";
import type {
  AgentRunResponse,
  AgentTurnResponse,
  AssetDownloadUrlResponse,
  AssetResponse,
  ChangeRoleRequest,
  CompositionTaskResponse,
  ConversationFileKind,
  ConversationFileResponse,
  ConversationFileVersionResponse,
  ConversationResponse,
  CreateCompositionTaskRequest,
  CreateConversationFileRequest,
  CreateConversationRequest,
  CreateGenerationTaskRequest,
  CreateModelRequest,
  CreateUploadRequest,
  GenerationTaskResponse,
  LoginRequest,
  LoginResponse,
  MessageResponse,
  ModelResponse,
  PageResult,
  RefreshRequest,
  RegisterRequest,
  SendMessageRequest,
  TokenResponse,
  UpdateConversationFileRequest,
  UpdateModelRequest,
  UploadTicketResponse,
  UserResponse,
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
};

export const adminUsersApi = {
  list(params?: {
    page?: number;
    size?: number;
  }): Promise<PageResult<UserResponse>> {
    return apiGet<PageResult<UserResponse>>("/api/v1/admin/users", {
      query: params,
    });
  },
  changeRole(id: UUID, request: ChangeRoleRequest): Promise<UserResponse> {
    return apiPut<UserResponse>(`/api/v1/admin/users/${id}/role`, request);
  },
};
