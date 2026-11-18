"use client";

import { useState } from "react";
import { AlertCircleIcon, CheckIcon, CopyIcon } from "lucide-react";

import {
  Alert,
  AlertAction,
  AlertDescription,
  AlertTitle,
} from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import type { ApiError } from "@/lib/api/errors";
import { copyToClipboard } from "@/lib/api/feedback";

interface ApiErrorAlertProps {
  error: ApiError | null;
  title?: string;
  className?: string;
}

export function ApiErrorAlert({
  error,
  title = "请求未成功",
  className,
}: ApiErrorAlertProps) {
  const [copied, setCopied] = useState(false);

  if (!error) {
    return null;
  }

  const handleCopyTrace = async () => {
    if (!error.traceId) {
      return;
    }
    const ok = await copyToClipboard(error.traceId);
    if (ok) {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    }
  };

  return (
    <Alert variant="destructive" className={className}>
      <AlertCircleIcon />
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription className="space-y-1">
        <p>{error.message}</p>
        <p className="font-mono text-xs opacity-80">
          {error.code}
          {error.traceId ? ` · traceId: ${error.traceId}` : ""}
        </p>
      </AlertDescription>
      {error.traceId ? (
        <AlertAction>
          <Button
            type="button"
            variant="ghost"
            size="xs"
            onClick={handleCopyTrace}
            aria-label="复制追踪 ID"
          >
            {copied ? <CheckIcon /> : <CopyIcon />}
            <span>{copied ? "已复制" : "追踪 ID"}</span>
          </Button>
        </AlertAction>
      ) : null}
    </Alert>
  );
}
