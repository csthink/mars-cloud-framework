package com.mars.cloud.security;

/** Valid allow or deny decision, without transport details. */
public record PdpDecision(boolean allowed, String reasonCode, String decisionId) { }
