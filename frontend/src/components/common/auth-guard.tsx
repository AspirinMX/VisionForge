"use client";

import { useEffect, type ReactNode } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ShieldAlertIcon } from "lucide-react";

import { useAuth } from "@/components/providers/auth-provider";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

export function StudioAuthGuard({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "unauthenticated") {
      router.replace("/login");
    }
  }, [status, router]);

  if (status !== "authenticated") {
    return (
      <div className="flex min-h-svh w-full bg-background">
        <div className="hidden w-64 shrink-0 border-r bg-sidebar p-4 md:flex md:flex-col md:gap-3">
          <Skeleton className="h-8 w-36" />
          <Skeleton className="h-9 w-full" />
          <div className="mt-4 space-y-2">
            <Skeleton className="h-7 w-full" />
            <Skeleton className="h-7 w-5/6" />
            <Skeleton className="h-7 w-4/5" />
          </div>
        </div>
        <div className="flex flex-1 flex-col">
          <div className="flex h-12 items-center border-b px-4">
            <Skeleton className="h-5 w-40" />
          </div>
          <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col justify-center gap-4 p-6">
            <Skeleton className="h-8 w-64" />
            <Skeleton className="h-28 w-full rounded-2xl" />
          </div>
        </div>
      </div>
    );
  }

  return <>{children}</>;
}

export function AdminRoleGuard({ children }: { children: ReactNode }) {
  const { user, status } = useAuth();

  if (status === "loading") {
    return (
      <div className="space-y-4 p-6">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-64 w-full rounded-2xl" />
      </div>
    );
  }

  if (user?.role !== "ADMIN") {
    return (
      <div className="flex flex-1 items-center justify-center p-6">
        <Card className="w-full max-w-md">
          <CardHeader>
            <div className="flex items-center gap-2 text-destructive">
              <ShieldAlertIcon className="size-5" />
              <CardTitle>无权访问系统管理界面</CardTitle>
            </div>
            <CardDescription>
              该页面仅向系统管理员（ADMIN）开放。管理员权限仅用于模型与用户运维，不包含跨用户内容访问。
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Button
              variant="outline"
              nativeButton={false}
              render={<Link href="/conversations" />}
            >
              返回创作工作台
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  return <>{children}</>;
}
