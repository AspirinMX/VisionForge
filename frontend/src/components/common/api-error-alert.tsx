"use client";

import { AlertCircleIcon } from "lucide-react";

import {
  Alert,
  AlertDescription,
  AlertTitle,
} from "@/components/ui/alert";
import type { ApiError } from "@/lib/api/errors";

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
  if (!error) {
    return null;
  }

  return (
    <Alert variant="destructive" className={className}>
      <AlertCircleIcon />
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription>{error.message}</AlertDescription>
    </Alert>
  );
}
