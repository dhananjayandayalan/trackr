package dev.trakr.api.identity.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record ResetPasswordRequest(@NotBlank @Size(max = 100) String token, @NotBlank @Size(max = 128) String password) {
}
