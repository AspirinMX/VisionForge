import { putToPresignedUrl } from "@/lib/api/client";
import { assetsApi } from "@/lib/api/endpoints";
import type { AssetKind, AssetResponse, UUID } from "@/lib/api/types";
import { placeholderStore } from "@/lib/storage/placeholder-store";

interface CachedDownloadEntry {
  url: string;
  expiresAtMs: number;
}

const downloadUrlCache = new Map<UUID, CachedDownloadEntry>();
const pendingDownloads = new Map<UUID, Promise<string>>();
const REFRESH_BUFFER_MS = 30_000;

export async function getAssetDownloadUrl(
  assetId: UUID,
  forceRefresh = false
): Promise<string> {
  const now = Date.now();
  const cached = downloadUrlCache.get(assetId);
  if (!forceRefresh && cached && cached.expiresAtMs - now > REFRESH_BUFFER_MS) {
    return cached.url;
  }

  const inFlight = pendingDownloads.get(assetId);
  if (!forceRefresh && inFlight) {
    return inFlight;
  }

  const request = assetsApi
    .downloadUrl(assetId)
    .then((response) => {
      const parsedExp = Date.parse(response.expiresAt);
      downloadUrlCache.set(assetId, {
        url: response.downloadUrl,
        expiresAtMs: Number.isNaN(parsedExp) ? now + 240_000 : parsedExp,
      });
      return response.downloadUrl;
    })
    .finally(() => {
      pendingDownloads.delete(assetId);
    });

  pendingDownloads.set(assetId, request);
  return request;
}

export function inferAssetKindFromFile(file: File): AssetKind {
  if (file.type.startsWith("image/")) {
    return "IMAGE";
  }
  if (file.type.startsWith("video/")) {
    return "VIDEO";
  }
  return "DOCUMENT";
}

export async function uploadConversationAsset(params: {
  userId: UUID;
  conversationId: UUID;
  file: File;
  kind?: AssetKind;
  onProgress?: (percent: number) => void;
  signal?: AbortSignal;
}): Promise<AssetResponse> {
  const { userId, conversationId, file, onProgress, signal } = params;
  const kind = params.kind ?? inferAssetKindFromFile(file);

  const ticket = await assetsApi.createUpload(conversationId, {
    kind,
    filename: file.name,
    mimeType: file.type || "application/octet-stream",
    sizeBytes: file.size,
  });

  await putToPresignedUrl({
    url: ticket.uploadUrl,
    file,
    requiredHeaders: ticket.requiredHeaders,
    onProgress,
    signal,
  });

  const asset = await assetsApi.completeUpload(
    conversationId,
    ticket.uploadSessionId
  );
  placeholderStore.upsertAsset(userId, conversationId, asset);
  return asset;
}
