package com.coatcard.axon.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthRequest {
    @NotBlank(message = "Username is required")
    @Email(message = "Please provide a valid email address")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;

    private String name;

    private String dob;

    private Integer age;

    private String gender;
}
