"use client";

import type { ReactNode } from "react";
import { AnimateUIProvider } from "@animate-ui/react/provider";
import { MotionConfig } from "motion/react";
import { ThemeProvider } from "next-themes";

import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";

import { AuthProvider } from "./auth-provider";

export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <MotionConfig reducedMotion="user">
      <AnimateUIProvider reducedMotion="user">
        <ThemeProvider
          attribute="class"
          defaultTheme="system"
          enableSystem
          disableTransitionOnChange
        >
          <TooltipProvider>
            <AuthProvider>
              {children}
              <Toaster position="top-right" richColors closeButton />
            </AuthProvider>
          </TooltipProvider>
        </ThemeProvider>
      </AnimateUIProvider>
    </MotionConfig>
  );
}
