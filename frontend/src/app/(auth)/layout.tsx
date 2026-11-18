"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter } from "next/navigation";

import { useAuth } from "@/components/providers/auth-provider";

export default function AuthLayout({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "authenticated") {
      router.replace("/conversations");
    }
  }, [status, router]);

  return (
    <main className="flex min-h-svh items-center justify-center bg-background p-4">
      {children}
    </main>
  );
}
