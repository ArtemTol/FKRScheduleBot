package com.example.fkrscheduletgbot;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;

@Component
@RequiredArgsConstructor
public class MyTelegramBot implements SpringLongPollingBot {

    private final UpdateConsumer updateConsumer;

    @Override
    public String getBotToken() {
        return "8023202316:AAF0l8dhfJCB6H1eifCz2QwYW66OQlcTk7M";
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return updateConsumer;
    }
}