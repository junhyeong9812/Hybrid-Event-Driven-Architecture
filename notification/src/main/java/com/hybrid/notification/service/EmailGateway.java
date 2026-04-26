package com.hybrid.notification.service;

public interface EmailGateway {
    void deliver(String to, String content);
}