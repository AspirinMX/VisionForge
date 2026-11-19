"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  ArrowLeftIcon,
  ChevronsUpDownIcon,
  LogOutIcon,
  MonitorIcon,
  MoonIcon,
  ShieldCheckIcon,
  SunIcon,
  WalletIcon,
} from "lucide-react";
import { useTheme } from "next-themes";

import { useAuth } from "@/components/providers/auth-provider";
import { useOptionalCredit } from "@/components/providers/credit-provider";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { SidebarMenu, SidebarMenuButton, SidebarMenuItem } from "@/components/ui/sidebar";
import { getInitials } from "@/lib/format";

interface SidebarUserMenuProps {
  mode?: "studio" | "admin";
  onNavigate?: () => void;
}

export function SidebarUserMenu({
  mode = "studio",
  onNavigate,
}: SidebarUserMenuProps) {
  const router = useRouter();
  const { user, logout } = useAuth();
  const creditState = useOptionalCredit();
  const credit = creditState?.credit ?? null;
  const { theme, setTheme } = useTheme();
  const isAdmin = user?.role === "ADMIN";

  const handleLogout = async () => {
    await logout();
    router.replace("/login");
  };

  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <SidebarMenuButton
                size="lg"
                className="data-popup-open:bg-sidebar-accent data-popup-open:text-sidebar-accent-foreground"
              />
            }
          >
            <Avatar className="size-7 rounded-xl">
              <AvatarFallback className="rounded-xl text-xs">
                {getInitials(user?.displayName)}
              </AvatarFallback>
            </Avatar>
            <div className="grid flex-1 text-left text-sm leading-tight">
              <span className="truncate font-medium">{user?.displayName}</span>
              <span className="truncate text-xs text-muted-foreground">
                {user?.email}
              </span>
            </div>
            <ChevronsUpDownIcon className="ml-auto size-4" />
          </DropdownMenuTrigger>
          <DropdownMenuContent side="top" align="end" className="w-60">
            {mode === "studio" && credit ? (
              <>
                <DropdownMenuItem
                  render={<Link href="/account" onClick={onNavigate} />}
                >
                  <WalletIcon />
                  <span>可用额度</span>
                  <span className="ml-auto font-medium text-foreground tabular-nums">
                    {credit.availableUnits}
                    {credit.reservedUnits > 0 ? (
                      <span className="ml-1 text-xs font-normal text-muted-foreground">
                        (+{credit.reservedUnits})
                      </span>
                    ) : null}
                  </span>
                </DropdownMenuItem>
                <DropdownMenuSeparator />
              </>
            ) : null}

            <DropdownMenuGroup>
              <DropdownMenuLabel>界面外观</DropdownMenuLabel>
              <DropdownMenuRadioGroup
                value={theme ?? "system"}
                onValueChange={(value) => setTheme(value)}
              >
                <DropdownMenuRadioItem value="light">
                  <SunIcon />
                  <span>浅色模式</span>
                </DropdownMenuRadioItem>
                <DropdownMenuRadioItem value="dark">
                  <MoonIcon />
                  <span>深色模式</span>
                </DropdownMenuRadioItem>
                <DropdownMenuRadioItem value="system">
                  <MonitorIcon />
                  <span>跟随系统</span>
                </DropdownMenuRadioItem>
              </DropdownMenuRadioGroup>
            </DropdownMenuGroup>

            <DropdownMenuSeparator />

            {mode === "studio" ? (
              <>
                <DropdownMenuItem
                  render={<Link href="/account" onClick={onNavigate} />}
                >
                  <WalletIcon />
                  <span>账户与额度</span>
                </DropdownMenuItem>
                {isAdmin ? (
                  <DropdownMenuItem
                    render={<Link href="/admin" onClick={onNavigate} />}
                  >
                    <ShieldCheckIcon />
                    <span>管理后台</span>
                  </DropdownMenuItem>
                ) : null}
              </>
            ) : (
              <DropdownMenuItem
                render={<Link href="/conversations" onClick={onNavigate} />}
              >
                <ArrowLeftIcon />
                <span>返回创作工作台</span>
              </DropdownMenuItem>
            )}

            <DropdownMenuItem variant="destructive" onClick={handleLogout}>
              <LogOutIcon />
              <span>退出登录</span>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </SidebarMenuItem>
    </SidebarMenu>
  );
}
