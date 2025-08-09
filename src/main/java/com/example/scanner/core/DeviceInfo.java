package com.example.scanner.core;

public record DeviceInfo(String ip, String hostname, long pingMillis, boolean reachable) {}