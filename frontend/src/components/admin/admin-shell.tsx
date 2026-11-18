"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  ArrowLeftIcon,
  CpuIcon,
  ShieldCheckIcon,
  UsersIcon,
} from "lucide-react";

import { SidebarUserMenu } from "@/components/layout/sidebar-user-menu";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarRail,
  SidebarSeparator,
  useSidebar,
} from "@/components/ui/sidebar";

function AdminSidebarContent() {
  const pathname = usePathname();
  const { isMobile, setOpenMobile } = useSidebar();

  const closeMobileIfNeeded = () => {
    if (isMobile) {
      setOpenMobile(false);
    }
  };

  return (
    <Sidebar collapsible="icon" variant="sidebar">
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              tooltip="VisionForge 管理后台"
              render={<Link href="/admin/models" onClick={closeMobileIfNeeded} />}
            >
              <ShieldCheckIcon />
              <span className="font-semibold tracking-tight">管理后台</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>

      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupLabel>系统运维</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              <SidebarMenuItem>
                <SidebarMenuButton
                  tooltip="模型与协议配置"
                  isActive={pathname === "/admin" || pathname.startsWith("/admin/models")}
                  render={
                    <Link href="/admin/models" onClick={closeMobileIfNeeded} />
                  }
                >
                  <CpuIcon />
                  <span>模型配置</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
              <SidebarMenuItem>
                <SidebarMenuButton
                  tooltip="用户与角色管理"
                  isActive={pathname.startsWith("/admin/users")}
                  render={
                    <Link href="/admin/users" onClick={closeMobileIfNeeded} />
                  }
                >
                  <UsersIcon />
                  <span>用户管理</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>

      <SidebarFooter>
        <SidebarSeparator />
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              tooltip="返回创作工作台"
              render={<Link href="/conversations" onClick={closeMobileIfNeeded} />}
            >
              <ArrowLeftIcon />
              <span>返回创作工作台</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
        <SidebarUserMenu mode="admin" onNavigate={closeMobileIfNeeded} />
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  );
}

export function AdminShell({ children }: { children: ReactNode }) {
  return (
    <SidebarProvider>
      <AdminSidebarContent />
      <SidebarInset className="flex h-svh min-w-0 flex-col overflow-hidden">
        {children}
      </SidebarInset>
    </SidebarProvider>
  );
}
