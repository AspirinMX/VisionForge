"use client";

import { useState } from "react";
import { motion } from "motion/react";
import {
  CopyIcon,
  DownloadIcon,
  ExpandIcon,
  FileIcon,
  RefreshCwIcon,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { useAsyncData } from "@/hooks/use-async-data";
import {
  copyToClipboard,
  notifyApiError,
  notifyError,
  notifySuccess,
} from "@/lib/api/feedback";
import type { UUID } from "@/lib/api/types";
import { SURFACE } from "@/lib/motion";
import { getAssetDownloadUrl } from "@/lib/studio/asset-cache";
import { cn } from "@/lib/utils";

const MAX_AUTO_PRESIGN_RETRY = 2;

interface AssetMediaPreviewProps {
  assetId: UUID;
  kind: "IMAGE" | "VIDEO" | "DOCUMENT" | "OTHER";
  alt?: string;
  className?: string;
  allowLightbox?: boolean;
}

export function AssetMediaPreview({
  assetId,
  kind,
  alt = "媒体资产",
  className,
  allowLightbox = true,
}: AssetMediaPreviewProps) {
  const [lightboxOpen, setLightboxOpen] = useState(false);
  const [retryState, setRetryState] = useState<{
    assetId: UUID;
    count: number;
    exhausted: boolean;
  }>({ assetId, count: 0, exhausted: false });

  const { data: url, loading, error, reload } = useAsyncData(
    () => getAssetDownloadUrl(assetId),
    `asset-url:${assetId}`,
    {
      enabled: Boolean(assetId),
      errorMessage: "获取媒体访问地址失败",
    }
  );

  const activeRetry =
    retryState.assetId === assetId
      ? retryState
      : { assetId, count: 0, exhausted: false };

  const handleMediaError = () => {
    if (activeRetry.count >= MAX_AUTO_PRESIGN_RETRY) {
      setRetryState({ assetId, count: activeRetry.count, exhausted: true });
      return;
    }
    setRetryState({
      assetId,
      count: activeRetry.count + 1,
      exhausted: false,
    });
    void getAssetDownloadUrl(assetId, true)
      .then(() => reload())
      .catch(() => {
        setRetryState((prev) =>
          prev.assetId === assetId
            ? { ...prev, exhausted: true }
            : { assetId, count: 1, exhausted: true }
        );
      });
  };

  const handleManualReload = () => {
    setRetryState({ assetId, count: 0, exhausted: false });
    void getAssetDownloadUrl(assetId, true)
      .catch(() => undefined)
      .finally(() => reload());
  };

  const handleDownload = async () => {
    try {
      const freshUrl = await getAssetDownloadUrl(assetId, true);
      window.open(freshUrl, "_blank", "noopener,noreferrer");
    } catch (err) {
      notifyApiError(err, "获取下载链接失败");
    }
  };

  const handleCopyUrl = async () => {
    try {
      const freshUrl = await getAssetDownloadUrl(assetId, false);
      const copied = await copyToClipboard(freshUrl);
      if (copied) {
        notifySuccess("已复制媒体访问链接");
      } else {
        notifyError("当前浏览器不支持剪贴板复制");
      }
    } catch (err) {
      notifyApiError(err, "获取媒体链接失败");
    }
  };

  if (loading && !url) {
    return <Skeleton className={cn("h-36 w-full rounded-surface-sm", className)} />;
  }

  if (error || !url || activeRetry.exhausted) {
    return (
      <div
        className={cn(
          "flex flex-col items-center justify-center gap-2 rounded-surface-sm border border-dashed bg-muted/30 p-3 text-center text-xs text-muted-foreground",
          className
        )}
      >
        <span>
          {activeRetry.exhausted
            ? "媒体链接刷新后仍无法加载，请手动重试"
            : "媒体暂不可预览（URL 未取到或非可播放格式）"}
        </span>
        <Button type="button" variant="ghost" size="xs" onClick={handleManualReload}>
          <RefreshCwIcon />
          <span>重试加载</span>
        </Button>
      </div>
    );
  }

  if (kind === "IMAGE") {
    return (
      <>
        <div
          className={cn(
            "group relative overflow-hidden rounded-surface-sm border bg-muted/30",
            className
          )}
        >
          {/* Presigned object storage URL varies dynamically per session */}
          <motion.img
            key={url}
            src={url}
            alt={alt}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={SURFACE.enter}
            onError={handleMediaError}
            className="h-full w-full object-cover"
          />
          {allowLightbox ? (
            <div className="absolute top-2 right-2 flex gap-1 opacity-100 transition-opacity motion-reduce:transition-none sm:opacity-0 sm:group-focus-within:opacity-100 sm:group-hover:opacity-100">
              <Button
                type="button"
                size="icon-xs"
                variant="secondary"
                onClick={() => setLightboxOpen(true)}
                aria-label="放大预览"
              >
                <ExpandIcon />
              </Button>
              <Button
                type="button"
                size="icon-xs"
                variant="secondary"
                onClick={() => void handleDownload()}
                aria-label="下载原图"
              >
                <DownloadIcon />
              </Button>
            </div>
          ) : null}
        </div>

        {allowLightbox ? (
          <Dialog open={lightboxOpen} onOpenChange={setLightboxOpen}>
            <DialogContent className="sm:max-w-3xl">
              <DialogHeader>
                <DialogTitle className="truncate pr-6">{alt}</DialogTitle>
              </DialogHeader>
              <div className="flex max-h-[70vh] items-center justify-center overflow-hidden rounded-surface-sm bg-muted/40">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={url}
                  alt={alt}
                  onError={handleMediaError}
                  className="max-h-[68vh] w-auto object-contain"
                />
              </div>
              <DialogFooter className="gap-2 sm:justify-end">
                <div className="flex items-center gap-2">
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    onClick={() => void handleCopyUrl()}
                  >
                    <CopyIcon />
                    <span>复制链接</span>
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    onClick={() => void handleDownload()}
                  >
                    <DownloadIcon />
                    <span>下载原图</span>
                  </Button>
                </div>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        ) : null}
      </>
    );
  }

  if (kind === "VIDEO") {
    return (
      <div
        className={cn(
          "group relative overflow-hidden rounded-surface-sm border bg-black",
          className
        )}
      >
        <motion.video
          key={url}
          src={url}
          controls
          playsInline
          preload="metadata"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={SURFACE.enter}
          onError={handleMediaError}
          className="h-full w-full object-contain"
        />
        <div className="absolute top-2 right-2 flex gap-1 opacity-100 transition-opacity motion-reduce:transition-none sm:opacity-0 sm:group-focus-within:opacity-100 sm:group-hover:opacity-100">
          <Button
            type="button"
            size="icon-xs"
            variant="secondary"
            onClick={() => void handleDownload()}
            aria-label="下载视频"
          >
            <DownloadIcon />
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        "flex items-center justify-between gap-2 rounded-surface-sm border bg-muted/30 px-3 py-2 text-xs",
        className
      )}
    >
      <span className="flex items-center gap-2 truncate">
        <FileIcon className="size-4 shrink-0 text-muted-foreground" />
        <span className="truncate">{alt ?? "媒体文件"}</span>
      </span>
      <Button
        type="button"
        variant="ghost"
        size="xs"
        onClick={() => void handleDownload()}
      >
        <DownloadIcon />
        <span>下载</span>
      </Button>
    </div>
  );
}
