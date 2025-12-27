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

    // Хранилище состояний пользователей для создания событий
    private final Map<Long, UserState> userStates = new ConcurrentHashMap<>();
    private final Map<Long, EventCreationData> eventCreationData = new ConcurrentHashMap<>();

    private enum UserState {
        AWAITING_EVENT_TITLE,
        AWAITING_EVENT_DATE,
        AWAITING_EVENT_TIME,
        AWAITING_EVENT_DIRECTION,
        AWAITING_EVENT_DESCRIPTION
    }

    private static class EventCreationData {
        String title;
        LocalDate date;
        LocalTime time;
        String direction;
        String description;
    }

    public UpdateConsumer(GoogleSheetsService sheetsService) {
        this.telegramClient = new OkHttpTelegramClient("8023202316:AAF0l8dhfJCB6H1eifCz2QwYW66OQlcTk7M");
        this.sheetsService = sheetsService;
        System.out.println("UpdateConsumer инициализирован с GoogleSheetsService");
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasMessage()) {
                String messageText = update.getMessage().getText();
                Long chatId = update.getMessage().getChatId();

                if (messageText.equals("/start")) {
                    registerUser(update.getMessage().getFrom());
                    sendMainMenu(chatId);
                } else {
                    handleUserInput(update.getMessage().getFrom().getId(), chatId, messageText);
                }
            } else if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleUserInput(Long userId, Long chatId, String messageText) throws TelegramApiException, IOException {
        UserState state = userStates.get(userId);

        if (state != null) {
            handleUserState(userId, chatId, messageText, state);
        } else {
            sendMessage(chatId, "Используйте меню для навигации или /start для главного меню");
        }
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) throws TelegramApiException, IOException {
        var data = callbackQuery.getData();
        var chatId = callbackQuery.getMessage().getChatId();
        var userId = callbackQuery.getFrom().getId();

        // Регистрируем пользователя
        registerUser(callbackQuery.getFrom());

        switch (data) {
            case "create_event" -> startEventCreation(userId, chatId);
            case "subscribe" -> showAvailableEventsForSubscription(userId, chatId);
            case "unsubscribe" -> showUserSubscriptionsForUnsubscribe(userId, chatId);
            case "all_events" -> showAllEvents(chatId);
            case "back" -> sendMainMenu(chatId);
            default -> {
                if (data.startsWith("subscribe_")) {
                    Long eventId = Long.parseLong(data.substring(10));
                    subscribeToEvent(userId, chatId, eventId);
                } else if (data.startsWith("unsubscribe_")) {
                    Long eventId = Long.parseLong(data.substring(12));
                    unsubscribeFromEvent(userId, chatId, eventId);
                } else {
                    sendMessage(chatId, "Неизвестная команда");
                }
            }
        }
    }

    private void handleUserState(Long userId, Long chatId, String messageText, UserState state)
            throws TelegramApiException, IOException {

        switch (state) {
            case AWAITING_EVENT_TITLE -> {
                EventCreationData data = new EventCreationData();
                data.title = messageText;
                eventCreationData.put(userId, data);
                userStates.put(userId, UserState.AWAITING_EVENT_DATE);
                sendMessage(chatId, "📅 Введите дату сбора в формате ДД.ММ.ГГГГ\nНапример: 25.12.2024");
            }

            case AWAITING_EVENT_DATE -> {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
                    LocalDate date = LocalDate.parse(messageText, formatter);

                    if (date.isBefore(LocalDate.now())) {
                        sendMessage(chatId, "❌ Дата не может быть в прошлом! Введите корректную дату:");
                        return;
                    }

                    EventCreationData data = eventCreationData.get(userId);
                    data.date = date;
                    userStates.put(userId, UserState.AWAITING_EVENT_TIME);
                    sendMessage(chatId, "⏰ Введите время сбора в формате ЧЧ:ММ\nНапример: 18:30");
                } catch (DateTimeParseException e) {
                    sendMessage(chatId, "❌ Неверный формат даты! Введите дату в формате ДД.ММ.ГГГГ:");
                }
            }

            case AWAITING_EVENT_TIME -> {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
                    LocalTime time = LocalTime.parse(messageText, formatter);

                    EventCreationData data = eventCreationData.get(userId);
                    data.time = time;
                    userStates.put(userId, UserState.AWAITING_EVENT_DIRECTION);
                    sendMessage(chatId, "📍 Введите направление сбора (например: Футбол, Волейбол, Баскетбол):");
                } catch (DateTimeParseException e) {
                    sendMessage(chatId, "❌ Неверный формат времени! Введите время в формате ЧЧ:ММ:");
                }
            }

            case AWAITING_EVENT_DIRECTION -> {
                EventCreationData data = eventCreationData.get(userId);
                data.direction = messageText;
                userStates.put(userId, UserState.AWAITING_EVENT_DESCRIPTION);
                sendMessage(chatId, "📝 Введите описание сбора (можно пропустить, отправив \"-\"):");
            }

            case AWAITING_EVENT_DESCRIPTION -> {
                EventCreationData data = eventCreationData.get(userId);
                data.description = messageText.equals("-") ? null : messageText;

                // Создаем событие в Google Sheets
                createEventInSheets(userId, data, chatId);

                // Очищаем состояние
                userStates.remove(userId);
                eventCreationData.remove(userId);
            }
        }
    }

    private void registerUser(org.telegram.telegrambots.meta.api.objects.User telegramUser) throws IOException {
        // Проверяем, есть ли пользователь уже в Google Sheets
        Map<String, String> existingUser = sheetsService.findUserByTelegramId(telegramUser.getId());

        if (existingUser == null) {
            System.out.println("Регистрация нового пользователя: " + telegramUser.getId());

            Map<String, Object> userData = new HashMap<>();
            userData.put("telegramId", telegramUser.getId());
            userData.put("username", telegramUser.getUserName() != null ? telegramUser.getUserName() : "");
            userData.put("firstName", telegramUser.getFirstName() != null ? telegramUser.getFirstName() : "");
            userData.put("lastName", telegramUser.getLastName() != null ? telegramUser.getLastName() : "");
            userData.put("registeredAt", LocalDateTime.now().toString());

            sheetsService.addUser(userData);
            System.out.println("Пользователь зарегистрирован в Google Sheets");
        }
    }

    private void createEventInSheets(Long userId, EventCreationData data, Long chatId) throws IOException, TelegramApiException {
        try {
            System.out.println("Создание события от пользователя: " + userId);

            Map<String, Object> eventData = new HashMap<>();
            eventData.put("title", data.title);
            eventData.put("description", data.description != null ? data.description : "");
            eventData.put("date", data.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
            eventData.put("time", data.time.format(DateTimeFormatter.ofPattern("HH:mm")));
            eventData.put("direction", data.direction);
            eventData.put("createdBy", userId);
            eventData.put("createdAt", LocalDateTime.now().toString());
            eventData.put("isActive", "TRUE");

            sheetsService.addEvent(eventData);

            sendMessage(chatId, "✅ Сбор успешно создан!\n\n" +
                    "📅 *" + data.title + "*\n" +
                    "🗓 Дата: " + data.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + "\n" +
                    "⏰ Время: " + data.time.format(DateTimeFormatter.ofPattern("HH:mm")) + "\n" +
                    "📍 Направление: " + data.direction + "\n" +
                    (data.description != null ? "📝 " + data.description + "\n" : "") +
                    "\nПодписчики будут уведомлены за час до начала.");

        } catch (Exception e) {
            System.err.println("Ошибка при создании события: " + e.getMessage());
            e.printStackTrace();
            sendMessage(chatId, "❌ Ошибка при создании сбора: " + e.getMessage());
        }
    }

    private void startEventCreation(Long userId, Long chatId) throws TelegramApiException {
        userStates.put(userId, UserState.AWAITING_EVENT_TITLE);
        sendMessage(chatId, "🏁 Давайте создадим новый сбор!\n\nВведите название сбора:");
    }

    private void showAvailableEventsForSubscription(Long userId, Long chatId) throws TelegramApiException, IOException {
        System.out.println("Показ доступных событий для подписки для пользователя: " + userId);

        List<Map<String, String>> allEvents = sheetsService.getActiveEvents();
        List<Map<String, String>> userSubscriptions = sheetsService.getUserSubscriptions(userId);

        // Фильтруем события, на которые пользователь уже подписан
        Set<String> subscribedEventIds = new HashSet<>();
        for (Map<String, String> sub : userSubscriptions) {
            subscribedEventIds.add(sub.get("Event ID"));
        }

        List<Map<String, String>> availableEvents = new ArrayList<>();
        for (Map<String, String> event : allEvents) {
            if (!subscribedEventIds.contains(event.get("ID"))) {
                availableEvents.add(event);
            }
        }

        if (availableEvents.isEmpty()) {
            sendMessage(chatId, "📭 Нет доступных сборов для подписки.");
            return;
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("📋 Доступные сборы для подписки:")
                .build();

        List<InlineKeyboardRow> keyboardRows = new ArrayList<>();

        for (Map<String, String> event : availableEvents) {
            String buttonText = String.format("%s - %s %s",
                    event.get("Title"),
                    event.get("Date"),
                    event.get("Time")
            );

            if (buttonText.length() > 64) {
                buttonText = buttonText.substring(0, 61) + "...";
            }

            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text(buttonText)
                    .callbackData("subscribe_" + event.get("ID"))
                    .build();

            keyboardRows.add(new InlineKeyboardRow(button));
        }

        // Кнопка "Назад"
        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                .text("⬅️ Назад в меню")
                .callbackData("back")
                .build();
        keyboardRows.add(new InlineKeyboardRow(backButton));

        message.setReplyMarkup(new InlineKeyboardMarkup(keyboardRows));
        telegramClient.execute(message);
    }

    private void showUserSubscriptionsForUnsubscribe(Long userId, Long chatId) throws TelegramApiException, IOException {
        System.out.println("Показ подписок пользователя: " + userId);

        List<Map<String, String>> userSubscriptions = sheetsService.getUserSubscriptions(userId);

        if (userSubscriptions.isEmpty()) {
            sendMessage(chatId, "📭 Вы не подписаны ни на один сбор.");
            return;
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("📋 Ваши подписки:")
                .build();

        List<InlineKeyboardRow> keyboardRows = new ArrayList<>();

        for (Map<String, String> sub : userSubscriptions) {
            // Получаем информацию о событии
            List<Map<String, String>> events = sheetsService.getEvents();
            Map<String, String> event = null;
            for (Map<String, String> e : events) {
                if (e.get("ID").equals(sub.get("Event ID"))) {
                    event = e;
                    break;
                }
            }

            if (event != null) {
                String buttonText = String.format("❌ %s - %s %s",
                        event.get("Title"),
                        event.get("Date"),
                        event.get("Time")
                );

                if (buttonText.length() > 64) {
                    buttonText = buttonText.substring(0, 61) + "...";
                }

                InlineKeyboardButton button = InlineKeyboardButton.builder()
                        .text(buttonText)
                        .callbackData("unsubscribe_" + event.get("ID"))
                        .build();

                keyboardRows.add(new InlineKeyboardRow(button));
            }
        }

        // Кнопка "Назад"
        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                .text("⬅️ Назад в меню")
                .callbackData("back")
                .build();
        keyboardRows.add(new InlineKeyboardRow(backButton));

        message.setReplyMarkup(new InlineKeyboardMarkup(keyboardRows));
        telegramClient.execute(message);
    }

    private void showAllEvents(Long chatId) throws TelegramApiException, IOException {
        System.out.println("Показ всех событий");

        List<Map<String, String>> events = sheetsService.getActiveEvents();

        if (events.isEmpty()) {
            sendMessage(chatId, "📭 Нет запланированных сборов.");
            return;
        }

        StringBuilder messageText = new StringBuilder("📅 Все сборы:\n\n");

        for (Map<String, String> event : events) {
            messageText.append("📅 *").append(event.get("Title")).append("*\n")
                    .append("🗓 Дата: ").append(event.get("Date")).append("\n")
                    .append("⏰ Время: ").append(event.get("Time")).append("\n")
                    .append("📍 Направление: ").append(event.get("Direction")).append("\n")
                    .append("🔢 ID: `").append(event.get("ID")).append("`\n");

            if (event.get("Description") != null && !event.get("Description").isEmpty()) {
                messageText.append("📝 ").append(event.get("Description")).append("\n");
            }

            messageText.append("\n");
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(messageText.toString())
                .parseMode("Markdown")
                .build();

        telegramClient.execute(message);
    }

    private void subscribeToEvent(Long userId, Long chatId, Long eventId) throws TelegramApiException, IOException {
        try {
            System.out.println("Пользователь " + userId + " подписывается на событие " + eventId);

            // Проверяем, не подписан ли уже
            List<Map<String, String>> userSubs = sheetsService.getUserSubscriptions(userId);
            boolean alreadySubscribed = false;
            for (Map<String, String> sub : userSubs) {
                if (String.valueOf(eventId).equals(sub.get("Event ID"))) {
                    alreadySubscribed = true;
                    break;
                }
            }

            if (alreadySubscribed) {
                sendMessage(chatId, "❌ Вы уже подписаны на этот сбор.");
                return;
            }

            Map<String, Object> subscriptionData = new HashMap<>();
            subscriptionData.put("userId", userId);
            subscriptionData.put("eventId", eventId);
            subscriptionData.put("subscribedAt", LocalDateTime.now().toString());

            sheetsService.addSubscription(subscriptionData);

            sendMessage(chatId, "✅ Вы успешно подписались на сбор!");

        } catch (Exception e) {
            System.err.println("Ошибка при подписке: " + e.getMessage());
            e.printStackTrace();
            sendMessage(chatId, "❌ Ошибка при подписке: " + e.getMessage());
        }
    }

    private void unsubscribeFromEvent(Long userId, Long chatId, Long eventId) throws TelegramApiException, IOException {
        try {
            System.out.println("Пользователь " + userId + " отписывается от события " + eventId);

            sheetsService.deleteSubscription(userId, eventId);
            sendMessage(chatId, "✅ Вы отписались от сбора.");

        } catch (Exception e) {
            System.err.println("Ошибка при отписке: " + e.getMessage());
            e.printStackTrace();
            sendMessage(chatId, "❌ Ошибка при отписке: " + e.getMessage());
        }
    }

    private void sendMessage(Long chatId, String messageText) throws TelegramApiException {
        SendMessage message = SendMessage.builder()
                .text(messageText)
                .chatId(chatId)
                .parseMode("Markdown")
                .build();

        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private void sendMainMenu(Long chatId) throws TelegramApiException {
        SendMessage message = SendMessage.builder()
                .text("🏠 Главное меню")
                .chatId(chatId)
                .build();

        var button1 = InlineKeyboardButton.builder()
                .text("➕ Создать сбор")
                .callbackData("create_event")
                .build();

        var button2 = InlineKeyboardButton.builder()
                .text("📋 Подписаться на сбор")
                .callbackData("subscribe")
                .build();

        var button3 = InlineKeyboardButton.builder()
                .text("❌ Отписаться от сбора")
                .callbackData("unsubscribe")
                .build();

        var button4 = InlineKeyboardButton.builder()
                .text("🗓 Посмотреть расписание")
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