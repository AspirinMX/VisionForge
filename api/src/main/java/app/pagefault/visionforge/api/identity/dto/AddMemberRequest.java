package app.pagefault.visionforge.api.identity.dto;

import app.pagefault.visionforge.identity.workspace.WorkspaceRole;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddMemberRequest(
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        String email,
        @NotNull(message = "角色不能为空")
        WorkspaceRole role) {
}
