import type { Metadata } from "next";
import { Inter } from "next/font/google";
import { cn } from "cn";

import { AppProviders } from "@/components/providers/app-providers";

import "./globals.css";

const inter = Inter({ subsets: ["latin"], variable: "--font-sans" });

export const metadata: Metadata = {
  title: "VisionForge · 幻影工坊",
  description: "对话驱动的 AI 视频与图像创作工作台",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="zh-CN"
      suppressHydrationWarning
      className={cn("font-sans", inter.variable)}
    >
      <body>
        <AppProviders>{children}</AppProviders>
      </body>
    </html>
  );
}
