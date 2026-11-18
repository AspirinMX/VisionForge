"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ClapperboardIcon, EyeIcon, EyeOffIcon, Loader2Icon } from "lucide-react";
import { toast } from "sonner";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { useAuth } from "@/components/providers/auth-provider";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { normalizeUnknownError, type ApiError } from "@/lib/api/errors";

export default function RegisterPage() {
  const router = useRouter();
  const { register, login } = useAuth();

  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const clearFieldError = (field: string) => {
    if (fieldErrors[field]) {
      setFieldErrors((prev) => ({ ...prev, [field]: "" }));
    }
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);

    const nextFieldErrors: Record<string, string> = {};
    const trimmedEmail = email.trim();
    const trimmedDisplayName = displayName.trim();

    if (!trimmedEmail) {
      nextFieldErrors.email = "邮箱不能为空";
    }
    if (!trimmedDisplayName) {
      nextFieldErrors.displayName = "显示名称不能为空";
    } else if (trimmedDisplayName.length > 100) {
      nextFieldErrors.displayName = "显示名称最多 100 个字符";
    }
    if (password.length < 12 || password.length > 72) {
      nextFieldErrors.password = "密码长度必须在 12 到 72 个字符之间";
    }
    if (confirmPassword !== password) {
      nextFieldErrors.confirmPassword = "两次输入的密码不一致";
    }

    if (Object.keys(nextFieldErrors).length > 0) {
      setFieldErrors(nextFieldErrors);
      return;
    }

    setFieldErrors({});
    setSubmitting(true);
    try {
      await register({
        email: trimmedEmail,
        displayName: trimmedDisplayName,
        password,
      });
      toast.success("账号注册成功，正在进入工作台");
      try {
        await login({ email: trimmedEmail, password });
        router.replace("/conversations");
      } catch {
        router.replace("/login");
      }
    } catch (err) {
      const apiError = normalizeUnknownError(err, "注册失败，请检查输入信息");
      if (apiError.isValidationError) {
        setFieldErrors(apiError.fieldErrors);
      } else {
        setError(apiError);
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card className="w-full max-w-[420px]">
      <CardHeader className="items-center text-center">
        <div className="mb-1 flex size-10 items-center justify-center rounded-2xl bg-primary text-primary-foreground">
          <ClapperboardIcon className="size-5" />
        </div>
        <CardTitle className="text-xl">注册 VisionForge</CardTitle>
        <CardDescription>
          创建独立创作账号，拥有专属会话工作区与媒体资产隔离空间
        </CardDescription>
      </CardHeader>

      <CardContent>
        <form onSubmit={handleSubmit} className="space-y-4">
          <ApiErrorAlert error={error} title="注册失败" />

          <div className="space-y-1.5">
            <Label htmlFor="register-email">邮箱</Label>
            <Input
              id="register-email"
              type="email"
              autoComplete="email"
              placeholder="name@example.com"
              value={email}
              onChange={(event) => {
                setEmail(event.target.value);
                clearFieldError("email");
              }}
              aria-invalid={Boolean(fieldErrors.email)}
              disabled={submitting}
            />
            {fieldErrors.email ? (
              <p className="text-xs text-destructive">{fieldErrors.email}</p>
            ) : null}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="register-name">显示名称</Label>
            <Input
              id="register-name"
              type="text"
              autoComplete="name"
              placeholder="例如：Alice"
              maxLength={100}
              value={displayName}
              onChange={(event) => {
                setDisplayName(event.target.value);
                clearFieldError("displayName");
              }}
              aria-invalid={Boolean(fieldErrors.displayName)}
              disabled={submitting}
            />
            {fieldErrors.displayName ? (
              <p className="text-xs text-destructive">
                {fieldErrors.displayName}
              </p>
            ) : null}
          </div>

          <div className="space-y-1.5">
            <div className="flex items-center justify-between">
              <Label htmlFor="register-password">密码</Label>
              <span className="text-xs text-muted-foreground tabular-nums">
                {password.length} / 12–72 位
              </span>
            </div>
            <div className="relative">
              <Input
                id="register-password"
                type={showPassword ? "text" : "password"}
                autoComplete="new-password"
                placeholder="至少 12 个字符"
                value={password}
                onChange={(event) => {
                  setPassword(event.target.value);
                  clearFieldError("password");
                }}
                aria-invalid={Boolean(fieldErrors.password)}
                disabled={submitting}
                className="pr-10"
              />
              <Button
                type="button"
                variant="ghost"
                size="icon-xs"
                onClick={() => setShowPassword((prev) => !prev)}
                aria-label={showPassword ? "隐藏密码" : "显示密码"}
                className="absolute top-1/2 right-1.5 -translate-y-1/2"
                tabIndex={-1}
              >
                {showPassword ? <EyeOffIcon /> : <EyeIcon />}
              </Button>
            </div>
            {fieldErrors.password ? (
              <p className="text-xs text-destructive">{fieldErrors.password}</p>
            ) : null}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="register-confirm">确认密码</Label>
            <Input
              id="register-confirm"
              type={showPassword ? "text" : "password"}
              autoComplete="new-password"
              placeholder="再次输入密码"
              value={confirmPassword}
              onChange={(event) => {
                setConfirmPassword(event.target.value);
                clearFieldError("confirmPassword");
              }}
              aria-invalid={Boolean(fieldErrors.confirmPassword)}
              disabled={submitting}
            />
            {fieldErrors.confirmPassword ? (
              <p className="text-xs text-destructive">
                {fieldErrors.confirmPassword}
              </p>
            ) : null}
          </div>

          <Button type="submit" className="w-full" disabled={submitting}>
            {submitting ? (
              <>
                <Loader2Icon className="animate-spin" />
                <span>创建账号中...</span>
              </>
            ) : (
              "创建账号"
            )}
          </Button>
        </form>
      </CardContent>

      <CardFooter className="justify-center border-t pt-4 text-sm text-muted-foreground">
        <span>已有账号？</span>
        <Button
          variant="link"
          size="sm"
          nativeButton={false}
          render={<Link href="/login" />}
          className="px-1.5"
        >
          返回登录
        </Button>
      </CardFooter>
    </Card>
  );
}
