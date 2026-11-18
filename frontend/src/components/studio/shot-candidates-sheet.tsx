"use client";

import { useState } from "react";
import { CheckCircle2Icon, FilmIcon, ScissorsIcon } from "lucide-react";

import { AssetMediaPreview } from "@/components/common/media-preview";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { notifyError } from "@/lib/api/feedback";
import type {
  StoryboardRange,
  StoryboardShot,
  StoryboardVideoCandidate,
  StoryboardVideoSelection,
} from "@/lib/api/types";
import { cn } from "@/lib/utils";

interface ShotCandidatesSheetProps {
  shot: StoryboardShot;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  saving?: boolean;
  onApplySelection: (
    shotId: string,
    selectedVideo: StoryboardVideoSelection | null,
    usedRange: StoryboardRange | null
  ) => Promise<void>;
}

export function ShotCandidatesSheet({
  shot,
  open,
  onOpenChange,
  saving = false,
  onApplySelection,
}: ShotCandidatesSheetProps) {
  const candidates = shot.videoCandidates ?? [];

  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(
    shot.selectedVideo?.taskId ?? null
  );
  const [enableTrim, setEnableTrim] = useState<boolean>(
    Boolean(shot.usedRange)
  );
  const [startSeconds, setStartSeconds] = useState<string>(
    shot.usedRange ? String(shot.usedRange.startSeconds) : "0"
  );
  const [endSeconds, setEndSeconds] = useState<string>(
    shot.usedRange
      ? String(shot.usedRange.endSeconds)
      : String(shot.durationSeconds)
  );

  const handleApply = async () => {
    let nextSelectedVideo: StoryboardVideoSelection | null = null;
    if (selectedTaskId) {
      const picked = candidates.find((c) => c.taskId === selectedTaskId);
      if (!picked || !picked.assetId) {
        notifyError("所选候选视频尚未生成完毕或缺少资产 ID");
        return;
      }
      nextSelectedVideo = {
        taskId: picked.taskId,
        assetId: picked.assetId,
        fileId: picked.fileId ?? undefined,
        version: picked.version ?? 1,
      };
    }

    let nextUsedRange: StoryboardRange | null = null;
    if (enableTrim) {
      const start = Number(startSeconds);
      const end = Number(endSeconds);
      if (
        Number.isNaN(start) ||
        Number.isNaN(end) ||
        start < 0 ||
        end <= start ||
        end > shot.durationSeconds
      ) {
        notifyError(
          `裁剪区间不合法：必须满足 0 <= 起始 < 结束 <= ${shot.durationSeconds} 秒`
        );
        return;
      }
      nextUsedRange = { startSeconds: start, endSeconds: end };
    }

    await onApplySelection(shot.id, nextSelectedVideo, nextUsedRange);
  };

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="flex w-full flex-col sm:max-w-2xl">
        <SheetHeader>
          <SheetTitle className="flex items-center gap-2">
            <FilmIcon className="size-4" />
            <span>镜头 {shot.id} · 多候选视频对比</span>
            <Badge variant="outline" className="tabular-nums">
              #{shot.order} · {shot.durationSeconds}s
            </Badge>
          </SheetTitle>
          <SheetDescription>
            并排预览该镜头的历史候选视频，指定最终入轨版本（selectedVideo）与可选时间裁剪区间（usedRange）。
          </SheetDescription>
        </SheetHeader>

        <div className="flex-1 space-y-5 overflow-y-auto px-4 py-2">
          {candidates.length === 0 ? (
            <div className="rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground">
              当前镜头尚无已回写的视频候选（videoCandidates）。你可以先在镜头卡片点击「生成视频候选」。
            </div>
          ) : (
            <div className="grid gap-3 md:grid-cols-2">
              {candidates.map((candidate: StoryboardVideoCandidate) => {
                const isSelected = selectedTaskId === candidate.taskId;
                const canSelect = Boolean(candidate.assetId);
                return (
                  <div
                    key={candidate.taskId}
                    className={cn(
                      "flex flex-col gap-2.5 rounded-xl border p-3 transition-colors",
                      isSelected ? "border-primary bg-accent/50" : "bg-card"
                    )}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <Badge variant="outline" className="font-mono text-[10px]">
                        {candidate.taskId.slice(0, 8)}
                      </Badge>
                      <Badge
                        variant={
                          candidate.status === "SUCCEEDED"
                            ? "secondary"
                            : "outline"
                        }
                      >
                        {candidate.status}
                      </Badge>
                    </div>

                    {candidate.assetId ? (
                      <AssetMediaPreview
                        assetId={candidate.assetId}
                        kind="VIDEO"
                        alt={`候选 ${candidate.taskId}`}
                        className="aspect-video w-full"
                      />
                    ) : (
                      <div className="flex aspect-video items-center justify-center rounded-xl border border-dashed text-xs text-muted-foreground">
                        等待归档资产 ID...
                      </div>
                    )}

                    <div className="flex items-center justify-between gap-2 text-xs">
                      <span className="truncate font-mono text-muted-foreground">
                        {candidate.assetId
                          ? `Asset ${candidate.assetId.slice(0, 8)} · v${candidate.version ?? 1}`
                          : "未归档"}
                      </span>
                      <Button
                        type="button"
                        size="xs"
                        variant={isSelected ? "default" : "outline"}
                        disabled={!canSelect}
                        onClick={() =>
                          setSelectedTaskId(isSelected ? null : candidate.taskId)
                        }
                      >
                        {isSelected ? (
                          <>
                            <CheckCircle2Icon />
                            <span>已选为主轨道</span>
                          </>
                        ) : (
                          <span>选为成片片段</span>
                        )}
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          <div className="space-y-3 rounded-xl border bg-muted/30 p-3.5">
            <label className="flex cursor-pointer items-center gap-2 text-sm font-medium">
              <Checkbox
                checked={enableTrim}
                onCheckedChange={(checked) => setEnableTrim(Boolean(checked))}
              />
              <ScissorsIcon className="size-3.5 text-muted-foreground" />
              <span>启用时间裁剪区间 (usedRange)</span>
            </label>

            {enableTrim ? (
              <div className="grid grid-cols-2 gap-3 pt-1">
                <div className="space-y-1.5">
                  <Label htmlFor="trim-start" className="text-xs">
                    起始时间（秒，&gt;= 0）
                  </Label>
                  <Input
                    id="trim-start"
                    type="number"
                    step="0.1"
                    min="0"
                    max={shot.durationSeconds}
                    value={startSeconds}
                    onChange={(e) => setStartSeconds(e.target.value)}
                    className="tabular-nums"
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="trim-end" className="text-xs">
                    结束时间（秒，&lt;= {shot.durationSeconds}）
                  </Label>
                  <Input
                    id="trim-end"
                    type="number"
                    step="0.1"
                    min="0"
                    max={shot.durationSeconds}
                    value={endSeconds}
                    onChange={(e) => setEndSeconds(e.target.value)}
                    className="tabular-nums"
                  />
                </div>
              </div>
            ) : (
              <p className="text-xs text-muted-foreground">
                未启用裁剪时，合成默认截取 0 至 {shot.durationSeconds} 秒完整片段。
              </p>
            )}
          </div>
        </div>

        <SheetFooter className="border-t pt-3">
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            取消
          </Button>
          <Button
            type="button"
            disabled={saving || (candidates.length === 0 && !shot.selectedVideo)}
            onClick={() => void handleApply()}
          >
            {saving ? "保存分镜版本中..." : "应用并保存分镜新版本"}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}
