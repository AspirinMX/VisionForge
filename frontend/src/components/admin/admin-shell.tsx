"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  ArrowLeftIcon,
  BellRingIcon,
  CpuIcon,
  LayoutDashboardIcon,
  ScrollTextIcon,
  ShieldCheckIcon,
  SlidersHorizontalIcon,
  TicketIcon,
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
              render={<Link href="/admin" onClick={closeMobileIfNeeded} />}
            >
              <ShieldCheckIcon />
              <span className="font-semibold tracking-tight">管理后台</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>

      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupLabel>运行态势</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              <SidebarMenuItem>
                <SidebarMenuButton
                  tooltip="运维总览"
                  isActive={pathname === "/admin"}
                  render={<Link href="/admin" onClick={closeMobileIfNeeded} />}
                >
                  <LayoutDashboardIcon />
                  <span>运维总览</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>

        <SidebarGroup>
          <SidebarGroupLabel>配置与告警</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              <SidebarMenuItem>
                <SidebarMenuButton
                  tooltip="模型与协议配置"
                  isActive={pathname.startsWith("/admin/models")}
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
                  tooltip="Nacos 动态配置中心"
                  isActive={pathname.startsWith("/admin/configs")}
                  render={
                    <Link href="/admin/configs" onClick={closeMobileIfNeeded} />
                  }
                >
                  <SlidersHorizontalIcon />
                  <span>Nacos 配置中心</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
              <SidebarMenuItem>
                <SidebarMenuButton
                  tooltip="告警渠道与投递日志"
                  isActive={pathname.startsWith("/admin/alerts")}
                  render={
                    <Link href="/admin/alerts" onClick={closeMobileIfNeeded} />
                  }
                >
                  <BellRingIcon />
                  <span>告警中心</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>

        <SidebarGroup>
          <SidebarGroupLabel>用户、账务与审计</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              <SidebarMenuItem>
                <SidebarMenuButton
                  tooltip="用户生命周期与额度管理"
                  isActive={pathname.startsWith("/admin/users")}
                  render={
                    <Link href="/admin/users" onClick={closeMobileIfNeeded} />
                  }
                >
                  <UsersIcon />
                  <span>用户管理</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
              <SidebarMenuItem>
                <SidebarMenuButton
                  tooltip="CDK 兑换码与全站流水"
                  isActive={pathname.startsWith("/admin/billing")}
                  render={
                    <Link href="/admin/billing" onClick={closeMobileIfNeeded} />
                  }
                >
                  <TicketIcon />
                  <span>计费与兑换码</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
              <SidebarMenuItem>
                <SidebarMenuButton
                  tooltip="操作审计日志查询与导出"
                  isActive={pathname.startsWith("/admin/audit-logs")}
                  render={
                    <Link
                      href="/admin/audit-logs"
                      onClick={closeMobileIfNeeded}
                    />
                  }
                >
                  <ScrollTextIcon />
                  <span>审计日志</span>
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
