package com.ticketpay.identity.dto;

public record AuthResponse(String token, String email, String role) {}
