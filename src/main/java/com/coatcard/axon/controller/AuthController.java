package com.coatcard.axon.controller;

import com.coatcard.axon.dto.AuthRequest;
import com.coatcard.axon.dto.AuthResponse;
import com.coatcard.axon.dto.EmailRequest;
import com.coatcard.axon.dto.OtpVerifyRequest;
import com.coatcard.axon.model.User;
import com.coatcard.axon.repository.UserRepository;
import com.coatcard.axon.security.JwtTokenProvider;
import jakarta.validation.Valid;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final JwtTokenProvider tokenProvider;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserDetailsService userDetailsService;
    private final JavaMailSender mailSender;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @org.springframework.beans.factory.annotation.Value("${spring.mail.username}")
    private String mailFrom;

    private final java.security.SecureRandom secureRandom = new java.security.SecureRandom();

    public AuthController(JwtTokenProvider tokenProvider,
                          StringRedisTemplate stringRedisTemplate,
                          UserDetailsService userDetailsService,
                          JavaMailSender mailSender,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder) {
        this.tokenProvider = tokenProvider;
        this.stringRedisTemplate = stringRedisTemplate;
        this.userDetailsService = userDetailsService;
        this.mailSender = mailSender;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody EmailRequest request) {
        String username = request.getUsername();
        boolean userExists = userRepository.findByUsername(username).isPresent();

        if (!userExists) {
            return ResponseEntity.ok(Map.of(
                    "status", "SIGNUP_REQUIRED",
                    "message", "No account found. Please create a new account."
            ));
        }

        String otp = String.format("%06d", secureRandom.nextInt(1000000));
        String otpKey = "otp:" + username;
        stringRedisTemplate.opsForValue().set(otpKey, otp, 5, TimeUnit.MINUTES);

        try {
            sendOtpEmail(username, otp);
        } catch (MailException ex) {
            stringRedisTemplate.delete(otpKey);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Unable to send OTP email. Please try again later."));
        }

        return ResponseEntity.ok(Map.of(
                "status", "OTP_REQUIRED",
                "username", username,
                "message", "OTP sent to your email. Please verify to complete login.",
                "isNewUser", false
        ));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody AuthRequest authRequest) {
        String username = authRequest.getUsername();
        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "Account already exists. Please log in instead."));
        }

        String pendingKey = "pending-registration:" + username;
        stringRedisTemplate.opsForValue().set(pendingKey,
                passwordEncoder.encode(authRequest.getPassword()),
                10,
                TimeUnit.MINUTES);

        String otp = String.format("%06d", secureRandom.nextInt(1000000));
        String otpKey = "otp:" + username;
        stringRedisTemplate.opsForValue().set(otpKey, otp, 10, TimeUnit.MINUTES);

        try {
            sendOtpEmail(username, otp);
        } catch (MailException ex) {
            stringRedisTemplate.delete(pendingKey);
            stringRedisTemplate.delete(otpKey);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Unable to send OTP email. Please try again later."));
        }

        return ResponseEntity.ok(Map.of(
                "status", "OTP_REQUIRED",
                "username", username,
                "message", "OTP sent to your email. Complete verification to create your account.",
                "isNewUser", true
        ));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@Valid @RequestBody OtpVerifyRequest otpVerifyRequest) {
        String redisKey = "otp:" + otpVerifyRequest.getUsername();
        String storedOtp = stringRedisTemplate.opsForValue().get(redisKey);

        if (storedOtp == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "OTP expired or not found. Please try again."));
        }

        if (!storedOtp.equals(otpVerifyRequest.getOtp())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Invalid OTP code. Please try again."));
        }

        stringRedisTemplate.delete(redisKey);

        String pendingKey = "pending-registration:" + otpVerifyRequest.getUsername();
        String pendingPassword = stringRedisTemplate.opsForValue().get(pendingKey);
        boolean createdNewUser = false;

        if (pendingPassword != null) {
            if (userRepository.findByUsername(otpVerifyRequest.getUsername()).isEmpty()) {
                User newUser = User.builder()
                        .username(otpVerifyRequest.getUsername())
                        .password(pendingPassword)
                        .roles(Set.of("ROLE_CLIENT"))
                        .build();
                userRepository.save(newUser);
                createdNewUser = true;
            }
            stringRedisTemplate.delete(pendingKey);
        }

        UserDetails userDetails = userDetailsService.loadUserByUsername(otpVerifyRequest.getUsername());
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        String jwt = tokenProvider.generateToken(userDetails.getUsername(), roles);
        AuthResponse response = new AuthResponse(jwt, userDetails.getUsername(), roles, "Bearer");

        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("token", response.getToken());
        responseBody.put("username", response.getUsername());
        responseBody.put("roles", response.getRoles());
        responseBody.put("tokenType", response.getTokenType());
        responseBody.put("message", createdNewUser
                ? "Account created and login successful."
                : "OTP verified. Login successful.");
        responseBody.put("isNewUser", createdNewUser);

        return ResponseEntity.ok(responseBody);
    }

    private void sendOtpEmail(String to, String otp) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "utf-8");
            
            helper.setTo(to);
            helper.setFrom(mailFrom);
            helper.setSubject("Your Axon OTP Code");
            
            String htmlContent = "<!DOCTYPE html>"
                    + "<html>"
                    + "<head>"
                    + "  <meta charset=\"utf-8\">"
                    + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                    + "  <title>Your Axon OTP Code</title>"
                    + "</head>"
                    + "<body style=\"margin: 0; padding: 0; background-color: #09090e; font-family: 'Outfit', 'Plus Jakarta Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; color: #f8fafc; -webkit-font-smoothing: antialiased;\">"
                    + "  <table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"background-color: #09090e; min-height: 100vh; padding: 40px 20px;\">"
                    + "    <tr>"
                    + "      <td align=\"center\" valign=\"top\">"
                    + "        <table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"max-width: 500px; background: rgba(255, 255, 255, 0.03); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 16px; box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.4); padding: 40px 30px; text-align: center;\">"
                    + "          <tr>"
                    + "            <td align=\"center\" style=\"padding-bottom: 24px;\">"
                    + "              <div style=\"display: inline-block; background: rgba(168, 85, 247, 0.08); border: 1px solid rgba(168, 85, 247, 0.25); border-radius: 12px; padding: 12px; margin-bottom: 12px;\">"
                    + "                <span style=\"font-size: 28px; line-height: 1;\">🛡️</span>"
                    + "              </div>"
                    + "              <h1 style=\"margin: 0; font-size: 26px; font-weight: 700; color: #ffffff; letter-spacing: 0.05em; text-shadow: 0 0 15px rgba(168, 85, 247, 0.4);\">AXON CORE</h1>"
                    + "              <p style=\"margin: 4px 0 0 0; font-size: 13px; color: #94a3b8; letter-spacing: 0.1em; text-transform: uppercase;\">Security Gateway Verification</p>"
                    + "            </td>"
                    + "          </tr>"
                    + "          <tr>"
                    + "            <td align=\"left\" style=\"padding-bottom: 30px;\">"
                    + "              <p style=\"margin: 0 0 16px 0; font-size: 15px; line-height: 1.6; color: #e2e8f0;\">Hello,</p>"
                    + "              <p style=\"margin: 0 0 24px 0; font-size: 15px; line-height: 1.6; color: #e2e8f0;\">You are requesting access to your Axon Dashboard. Please use the following One-Time Password (OTP) to complete your verification request:</p>"
                    + "            </td>"
                    + "          </tr>"
                    + "          <tr>"
                    + "            <td align=\"center\" style=\"padding-bottom: 30px;\">"
                    + "              <div style=\"background: rgba(168, 85, 247, 0.06); border: 1px solid rgba(168, 85, 247, 0.2); border-radius: 12px; padding: 20px 40px; display: inline-block; box-shadow: inset 0 0 12px rgba(168, 85, 247, 0.1);\">"
                    + "                <span style=\"font-size: 38px; font-weight: 800; letter-spacing: 12px; color: #a855f7; font-family: 'Courier New', Courier, monospace; text-shadow: 0 0 10px rgba(168, 85, 247, 0.3); padding-left: 12px;\">" + otp + "</span>"
                    + "              </div>"
                    + "            </td>"
                    + "          </tr>"
                    + "          <tr>"
                    + "            <td align=\"center\" style=\"padding-bottom: 24px; border-bottom: 1px solid rgba(255, 255, 255, 0.05);\">"
                    + "              <div style=\"background: rgba(245, 158, 11, 0.05); border: 1px solid rgba(245, 158, 11, 0.15); border-radius: 8px; padding: 12px 16px; display: inline-block; max-width: 90%;\">"
                    + "                <p style=\"margin: 0; font-size: 13px; line-height: 1.5; color: #f59e0b; font-weight: 500;\">"
                    + "                  ⚠️ This OTP code is valid for <strong>5 minutes</strong>."
                    + "                </p>"
                    + "              </div>"
                    + "            </td>"
                    + "          </tr>"
                    + "          <tr>"
                    + "            <td align=\"center\" style=\"padding-top: 24px;\">"
                    + "              <p style=\"margin: 0 0 8px 0; font-size: 12px; color: #64748b; line-height: 1.5;\">If you did not initiate this login request, you can safely ignore this email.</p>"
                    + "              <p style=\"margin: 0; font-size: 11px; color: #475569;\">&copy; 2026 Axon Core. All rights reserved.</p>"
                    + "            </td>"
                    + "          </tr>"
                    + "        </table>"
                    + "      </td>"
                    + "    </tr>"
                    + "  </table>"
                    + "</body>"
                    + "</html>";

            helper.setText(htmlContent, true);
            mailSender.send(mimeMessage);
        } catch (Exception e) {
            System.err.println("Failed to send HTML OTP email: " + e.getMessage());
            throw new MailSendException("Failed to send verification email", e);
        }
    }
}
