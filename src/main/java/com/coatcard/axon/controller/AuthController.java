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
import org.springframework.mail.SimpleMailMessage;
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
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setFrom(mailFrom);
        message.setSubject("Your Axon OTP Code");
        message.setText("Your one-time password is: " + otp + "\n\nThis code expires in 5 minutes.");
        mailSender.send(message);
    }
}
