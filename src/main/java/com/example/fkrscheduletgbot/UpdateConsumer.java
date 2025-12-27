package com.example.fkrscheduletgbot;

import com.example.fkrscheduletgbot.service.GoogleSheetsService;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class UpdateConsumer implements LongPollingSingleThreadUpdateConsumer {

    private final TelegramClient telegramClient;
    private final GoogleSheetsService sheetsService;

    // Состояния пользователей: awaiting_name - ждем имя, creating_event - создаем событие
    private final Map<Long, String> userState = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, String>> userEventData = new ConcurrentHashMap<>();
    private final Map<Long, String> userNames = new ConcurrentHashMap<>();

    public UpdateConsumer(GoogleSheetsService sheetsService) {
        this.telegramClient = new OkHttpTelegramClient("8023202316:AAF0l8dhfJCB6H1eifCz2QwYW66OQlcTk7M");
        this.sheetsService = sheetsService;
        System.out.println("UpdateConsumer инициализирован с GoogleSheetsService");
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleMessage(update);
            } else if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleMessage(Update update) throws TelegramApiException, IOException {
        String messageText = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();
        Long userId = update.getMessage().getFrom().getId();
        String username = update.getMessage().getFrom().getUserName();
        String firstName = update.getMessage().getFrom().getFirstName();
        String lastName = update.getMessage().getFrom().getLastName();

        String state = userState.get(userId);

        if (state != null) {
            handleUserState(userId, chatId, messageText, state, username, firstName, lastName);
        } else if (messageText.equals("/start")) {
            startRegistration(userId, chatId);
        } else if (messageText.equals("/menu")) {
            if (userNames.containsKey(userId)) {
                sendMainMenu(chatId);
            } else {
                sendMessage(chatId, "Пожалуйста, сначала введите /start для регистрации");
            }
        } else if (messageText.equals("/cancel")) {
            cancelOperation(userId, chatId);
        } else {
            if (userNames.containsKey(userId)) {
                sendMainMenu(chatId);
            } else {
                sendMessage(chatId, "Пожалуйста, введите /start для начала работы");
            }
        }
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) throws TelegramApiException, IOException {
        String data = callbackQuery.getData();
        Long chatId = callbackQuery.getMessage().getChatId();
        Long userId = callbackQuery.getFrom().getId();

        if (!userNames.containsKey(userId)) {
            sendMessage(chatId, "Пожалуйста, сначала введите /start для регистрации");
            return;
        }

        switch (data) {
            case "create_event" -> startEventCreation(userId, chatId);
            case "subscribe" -> showAvailableEvents(userId, chatId);
            case "unsubscribe" -> showUserSubscriptions(userId, chatId);
            case "all_events" -> showAllEvents(chatId);
            default -> {
                if (data.startsWith("subscribe_")) {
                    Long eventId = Long.parseLong(data.substring(10));
                    subscribeToEvent(userId, chatId, eventId);
                } else if (data.startsWith("unsubscribe_")) {
                    Long eventId = Long.parseLong(data.substring(12));
                    unsubscribeFromEvent(userId, chatId, eventId);
                }
            }
        }
    }

    private void handleUserState(Long userId, Long chatId, String messageText, String state,
                                 String username, String firstName, String lastName)
            throws TelegramApiException, IOException {

        switch (state) {
            case "awaiting_name" -> {
                String userName = messageText.trim();
                if (userName.isEmpty()) {
                    sendMessage(chatId, "Имя не может быть пустым. Пожалуйста, введите ваше имя:");
                    return;
                }

                userNames.put(userId, userName);
                userState.remove(userId);

                // Регистрируем пользователя в Google Sheets
                if (!sheetsService.userExists(userId)) {
                    sheetsService.addUser(userId, username, userName);
                }

                sendMessage(chatId, "Добро пожаловать, " + userName + "!");
                sendMainMenu(chatId);
            }

            case "awaiting_event_title" -> {
                Map<String, String> eventData = new HashMap<>();
                eventData.put("title", messageText);
                userEventData.put(userId, eventData);
                userState.put(userId, "awaiting_event_date");
                sendMessage(chatId, "Введите дату сбора в формате ДД.ММ.ГГГГ\nНапример: 25.12.2024");
            }

            case "awaiting_event_date" -> {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
                    LocalDate.parse(messageText, formatter);

                    Map<String, String> eventData = userEventData.get(userId);
                    eventData.put("date", messageText);
                    userState.put(userId, "awaiting_event_time");
                    sendMessage(chatId, "Введите время сбора в формате ЧЧ:ММ\nНапример: 18:30");
                } catch (DateTimeParseException e) {
                    sendMessage(chatId, "Неверный формат даты! Введите дату в формате ДД.ММ.ГГГГ:");
                }
            }

            case "awaiting_event_time" -> {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
                    LocalTime.parse(messageText, formatter);

                    Map<String, String> eventData = userEventData.get(userId);
                    eventData.put("time", messageText);
                    userState.put(userId, "awaiting_event_location");
                    sendMessage(chatId, "Введите место проведения сбора:");
                } catch (DateTimeParseException e) {
                    sendMessage(chatId, "Неверный формат времени! Введите время в формате ЧЧ:ММ:");
                }
            }

            case "awaiting_event_location" -> {
                Map<String, String> eventData = userEventData.get(userId);
                eventData.put("location", messageText);

                // Сохраняем событие
                createEvent(userId, chatId, eventData);

                // Очищаем состояние
                userState.remove(userId);
                userEventData.remove(userId);
            }
        }
    }

    private void startRegistration(Long userId, Long chatId) throws TelegramApiException {
        userState.put(userId, "awaiting_name");
        sendMessage(chatId, "Добро пожаловать! Для начала работы, пожалуйста, введите ваше имя:");
    }

    private void startEventCreation(Long userId, Long chatId) throws TelegramApiException {
        userState.put(userId, "awaiting_event_title");
        sendMessage(chatId, "Создание нового сбора. Введите название сбора:");
    }

    private void createEvent(Long userId, Long chatId, Map<String, String> eventData)
            throws TelegramApiException, IOException {
        try {
            String userName = userNames.get(userId);
            Long eventId = sheetsService.addEvent(
                    eventData.get("title"),
                    eventData.get("date"),
                    eventData.get("time"),
                    eventData.get("location"),
                    userId,
                    userName
            );

            sendMessage(chatId, "Сбор успешно создан!\n\n" +
                    "Название: " + eventData.get("title") + "\n" +
                    "Дата: " + eventData.get("date") + "\n" +
                    "Время: " + eventData.get("time") + "\n" +
                    "Место: " + eventData.get("location"));

            sendMainMenu(chatId);

        } catch (Exception e) {
            sendMessage(chatId, "Ошибка при создании сбора: " + e.getMessage());
        }
    }

    private void showAvailableEvents(Long userId, Long chatId) throws TelegramApiException, IOException {
        List<Map<String, String>> events = sheetsService.getActiveEvents();
        List<Map<String, String>> userSubscriptions = sheetsService.getUserSubscriptions(userId);

        // Фильтруем события, на которые пользователь не подписан
        Set<String> subscribedEventIds = new HashSet<>();
        for (Map<String, String> sub : userSubscriptions) {
            subscribedEventIds.add(sub.get("Event ID"));
        }

        List<Map<String, String>> availableEvents = new ArrayList<>();
        for (Map<String, String> event : events) {
            if (!subscribedEventIds.contains(event.get("ID"))) {
                availableEvents.add(event);
            }
        }

        if (availableEvents.isEmpty()) {
            sendMessage(chatId, "Нет доступных сборов для подписки.");
            return;
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Доступные сборы для подписки:")
                .build();

        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (Map<String, String> event : availableEvents) {
            String buttonText = event.get("Title") + " - " + event.get("Date") + " " + event.get("Time");
            if (buttonText.length() > 64) {
                buttonText = buttonText.substring(0, 61) + "...";
            }

            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text(buttonText)
                    .callbackData("subscribe_" + event.get("ID"))
                    .build();

            rows.add(new InlineKeyboardRow(button));
        }

        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                .text("Назад")
                .callbackData("back")
                .build();
        rows.add(new InlineKeyboardRow(backButton));

        message.setReplyMarkup(new InlineKeyboardMarkup(rows));
        telegramClient.execute(message);
    }

    private void showUserSubscriptions(Long userId, Long chatId) throws TelegramApiException, IOException {
        List<Map<String, String>> userSubscriptions = sheetsService.getUserSubscriptions(userId);

        if (userSubscriptions.isEmpty()) {
            sendMessage(chatId, "Вы не подписаны ни на один сбор.");
            return;
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Ваши подписки:")
                .build();

        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (Map<String, String> sub : userSubscriptions) {
            // Находим событие
            List<Map<String, String>> events = sheetsService.getAllEvents();
            for (Map<String, String> event : events) {
                if (event.get("ID").equals(sub.get("Event ID"))) {
                    String buttonText = event.get("Title") + " - " + event.get("Date") + " " + event.get("Time");
                    if (buttonText.length() > 64) {
                        buttonText = buttonText.substring(0, 61) + "...";
                    }

                    InlineKeyboardButton button = InlineKeyboardButton.builder()
                            .text(buttonText)
                            .callbackData("unsubscribe_" + event.get("ID"))
                            .build();

                    rows.add(new InlineKeyboardRow(button));
                    break;
                }
            }
        }

        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                .text("Назад")
                .callbackData("back")
                .build();
        rows.add(new InlineKeyboardRow(backButton));

        message.setReplyMarkup(new InlineKeyboardMarkup(rows));
        telegramClient.execute(message);
    }

    private void showAllEvents(Long chatId) throws TelegramApiException, IOException {
        List<Map<String, String>> events = sheetsService.getActiveEvents();

        if (events.isEmpty()) {
            sendMessage(chatId, "Нет запланированных сборов.");
            return;
        }

        StringBuilder messageText = new StringBuilder("Все сборы:\n\n");

        for (Map<String, String> event : events) {
            messageText.append("Название: ").append(event.get("Title")).append("\n")
                    .append("Дата: ").append(event.get("Date")).append("\n")
                    .append("Время: ").append(event.get("Time")).append("\n")
                    .append("Место: ").append(event.get("Location")).append("\n")
                    .append("Подписчиков: ").append(event.get("Subs Number")).append("\n\n");
        }

        sendMessage(chatId, messageText.toString());
    }

    private void subscribeToEvent(Long userId, Long chatId, Long eventId) throws TelegramApiException, IOException {
        try {
            if (sheetsService.isUserSubscribed(userId, eventId)) {
                sendMessage(chatId, "Вы уже подписаны на этот сбор.");
                return;
            }

            String userName = userNames.get(userId);
            sheetsService.addSubscription(userId, eventId, userName);
            sendMessage(chatId, "Вы успешно подписались на сбор!");
            sendMainMenu(chatId);

        } catch (Exception e) {
            sendMessage(chatId, "Ошибка при подписке: " + e.getMessage());
        }
    }

    private void unsubscribeFromEvent(Long userId, Long chatId, Long eventId) throws TelegramApiException, IOException {
        try {
            sheetsService.deleteSubscription(userId, eventId);
            sendMessage(chatId, "Вы отписались от сбора.");
            sendMainMenu(chatId);
        } catch (Exception e) {
            sendMessage(chatId, "Ошибка при отписке: " + e.getMessage());
        }
    }

    private void cancelOperation(Long userId, Long chatId) throws TelegramApiException {
        userState.remove(userId);
        userEventData.remove(userId);
        sendMessage(chatId, "Операция отменена.");
        sendMainMenu(chatId);
    }

    private void sendMessage(Long chatId, String messageText) throws TelegramApiException {
        SendMessage message = SendMessage.builder()
                .text(messageText)
                .chatId(chatId)
                .build();

        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private void sendMainMenu(Long chatId) throws TelegramApiException {
        SendMessage message = SendMessage.builder()
                .text("Главное меню")
                .chatId(chatId)
                .build();

        var button1 = InlineKeyboardButton.builder()
                .text("Создать сбор")
                .callbackData("create_event")
                .build();

        var button2 = InlineKeyboardButton.builder()
                .text("Подписаться на сбор")
                .callbackData("subscribe")
                .build();

        var button3 = InlineKeyboardButton.builder()
                .text("Отписаться от сбора")
                .callbackData("unsubscribe")
                .build();

        var button4 = InlineKeyboardButton.builder()
                .text("Посмотреть расписание")
                .callbackData("all_events")
                .build();

        List<InlineKeyboardRow> keyboardRows = List.of(
                new InlineKeyboardRow(button1),
                new InlineKeyboardRow(button2),
                new InlineKeyboardRow(button3),
                new InlineKeyboardRow(button4)
        );

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(keyboardRows);

        message.setReplyMarkup(markup);

        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }
}