"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ClapperboardIcon, EyeIcon, EyeOffIcon, Loader2Icon } from "lucide-react";

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

export default function LoginPage() {
  const router = useRouter();
  const { login } = useAuth();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);

    const nextFieldErrors: Record<string, string> = {};
    const trimmedEmail = email.trim();
    if (!trimmedEmail) {
      nextFieldErrors.email = "请输入邮箱";
    }
    if (!password) {
      nextFieldErrors.password = "请输入密码";
    }
    if (Object.keys(nextFieldErrors).length > 0) {
      setFieldErrors(nextFieldErrors);
      return;
    }

    setFieldErrors({});
    setSubmitting(true);
    try {
      await login({ email: trimmedEmail, password });
      router.replace("/conversations");
    } catch (err) {
      const apiError = normalizeUnknownError(err, "登录失败，请稍后重试");
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
    <Card className="w-full max-w-[400px]">
      <CardHeader className="items-center text-center">
        <div className="mb-1 flex size-10 items-center justify-center rounded-2xl bg-primary text-primary-foreground">
          <ClapperboardIcon className="size-5" />
        </div>
        <CardTitle className="text-xl">登录 VisionForge</CardTitle>
        <CardDescription>
          幻影工坊 · 对话驱动的 AI 视频与图像创作工作台
        </CardDescription>
      </CardHeader>

      <CardContent>
        <form id="login-form" onSubmit={handleSubmit} className="space-y-4">
          <ApiErrorAlert error={error} title="登录失败" />

          <div className="space-y-1.5">
            <Label htmlFor="email">邮箱</Label>
            <Input
              id="email"
              type="email"
              autoComplete="email"
              placeholder="name@example.com"
              value={email}
              onChange={(event) => {
                setEmail(event.target.value);
                if (fieldErrors.email) {
                  setFieldErrors((prev) => ({ ...prev, email: "" }));
                }
              }}
              aria-invalid={Boolean(fieldErrors.email)}
              disabled={submitting}
            />
            {fieldErrors.email ? (
              <p className="text-xs text-destructive">{fieldErrors.email}</p>
            ) : null}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="password">密码</Label>
            <div className="relative">
              <Input
                id="password"
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                placeholder="输入登录密码"
                value={password}
                onChange={(event) => {
                  setPassword(event.target.value);
                  if (fieldErrors.password) {
                    setFieldErrors((prev) => ({ ...prev, password: "" }));
                  }
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

          <Button
            type="submit"
            className="w-full"
            disabled={submitting}
          >
            {submitting ? (
              <>
                <Loader2Icon className="animate-spin motion-reduce:animate-none" />
                <span>登录中...</span>
              </>
            ) : (
              "登录"
            )}
          </Button>
        </form>
      </CardContent>

      <CardFooter className="justify-center border-t pt-4 text-sm text-muted-foreground">
        <span>还没有账号？</span>
        <Button
          variant="link"
          size="sm"
          nativeButton={false}
          render={<Link href="/register" />}
          className="px-1.5"
        >
          立即注册
        </Button>
      </CardFooter>
    </Card>
  );
}
