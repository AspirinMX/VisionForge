"use client";

import { useState } from "react";
import { DownloadIcon, ExpandIcon, FileIcon, RefreshCwIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { useAsyncData } from "@/hooks/use-async-data";
import { notifyApiError } from "@/lib/api/feedback";
import type { UUID } from "@/lib/api/types";
import { getAssetDownloadUrl } from "@/lib/studio/asset-cache";
import { cn } from "@/lib/utils";

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

  const { data: url, loading, error, reload } = useAsyncData(
    () => getAssetDownloadUrl(assetId),
    `asset-url:${assetId}`,
    {
      enabled: Boolean(assetId),
      errorMessage: "获取媒体访问地址失败",
    }
  );

  const handleDownload = async () => {
    try {
      const freshUrl = await getAssetDownloadUrl(assetId, true);
      window.open(freshUrl, "_blank", "noopener,noreferrer");
    } catch (err) {
      notifyApiError(err, "获取下载链接失败");
    }
  };

  if (loading && !url) {
    return <Skeleton className={cn("h-36 w-full rounded-xl", className)} />;
  }

  if (error || !url) {
    return (
      <div
        className={cn(
          "flex flex-col items-center justify-center gap-2 rounded-xl border border-dashed bg-muted/30 p-3 text-center text-xs text-muted-foreground",
          className
        )}
      >
        <span>媒体暂不可预览（URL 未取到或非可播放格式）</span>
        <Button type="button" variant="ghost" size="xs" onClick={reload}>
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
            "group relative overflow-hidden rounded-xl border bg-muted/30",
            className
          )}
        >
          {/* Presigned object storage URL varies dynamically per session */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={url}
            alt={alt}
            onError={() => {
              void getAssetDownloadUrl(assetId, true).then(() => reload());
            }}
            className="h-full w-full object-cover"
          />
          {allowLightbox ? (
            <div className="absolute top-2 right-2 flex gap-1 opacity-0 transition-opacity motion-reduce:transition-none group-hover:opacity-100">
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
                <DialogTitle>{alt}</DialogTitle>
              </DialogHeader>
              <div className="flex max-h-[72vh] items-center justify-center overflow-hidden rounded-xl bg-muted/40">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={url}
                  alt={alt}
                  className="max-h-[70vh] w-auto object-contain"
                />
              </div>
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
          "relative overflow-hidden rounded-xl border bg-black",
          className
        )}
      >
        <video
          src={url}
          controls
          playsInline
          preload="metadata"
          onError={() => {
            void getAssetDownloadUrl(assetId, true).then(() => reload());
          }}
          className="h-full w-full object-contain"
        />
      </div>
    );
  }

  return (
    <div
      className={cn(
        "flex items-center justify-between gap-2 rounded-xl border bg-muted/30 px-3 py-2 text-xs",
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
