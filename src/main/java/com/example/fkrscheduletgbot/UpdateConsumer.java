package com.example.fkrscheduletgbot;

import com.example.fkrscheduletgbot.service.PostgresDatabaseService;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class UpdateConsumer implements LongPollingSingleThreadUpdateConsumer {

    private final TelegramClient telegramClient;
    private final PostgresDatabaseService databaseService;
    private final Long botStartTime;

    // Состояния пользователей: awaiting_name - ждем имя, creating_event - создаем событие
    private final Map<Long, String> userState = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, String>> userEventData = new ConcurrentHashMap<>();
    private final Map<Long, String> userNames = new ConcurrentHashMap<>();
    // Состояние изменения сбора
    private final Map<Long, Long> editingEventId = new ConcurrentHashMap<>();
    private final Map<Long, String> editingField = new ConcurrentHashMap<>();

    public UpdateConsumer(PostgresDatabaseService databaseService) {
        this.telegramClient = new OkHttpTelegramClient("8023202316:AAF0l8dhfJCB6H1eifCz2QwYW66OQlcTk7M");
        this.databaseService = databaseService;
        this.botStartTime = System.currentTimeMillis();
        System.out.println("UpdateConsumer инициализирован с PostgresDatabaseService. Время запуска: " + botStartTime);
    }

    @Override
    public void consume(Update update) {
        try {
            // Получаем время сообщения в миллисекундах
            Long messageTime = null;

            if (update.hasMessage()) {
                // Telegram время в секундах, переводим в миллисекунды
                messageTime = update.getMessage().getDate() * 1000L;
            } else if (update.hasCallbackQuery()) {
                messageTime = update.getCallbackQuery().getMessage().getDate() * 1000L;
            }

            // Если не смогли получить время - обрабатываем
            if (messageTime == null) {
                processUpdate(update);
                return;
            }

            // Текущее время
            Long currentTime = System.currentTimeMillis();

            // Проверяем разницу (увеличим до 30 секунд)
            long timeDiff = currentTime - messageTime;

            // Игнорируем ТОЛЬКО очень старые сообщения (старше 30 секунд)
            if (timeDiff > 30000) { // 30 секунд вместо 10
                System.out.println("Игнорируем старое сообщение: разница " + timeDiff + "ms");
                return;
            }

            // Обработка сообщения
            processUpdate(update);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processUpdate(Update update) throws TelegramApiException, IOException {
        if (update.hasMessage() && update.getMessage().hasText()) {
            handleMessage(update);
        } else if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
        }
    }

    // Обработка сообщения от пользователя
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
            // Обработка команды /start
            handleStartCommand(userId, chatId, username);
        } else if (messageText.equals("/cancel")) {
            cancelOperation(userId, chatId);
        }
        // Обработка reply-кнопок главного меню
        else if (messageText.equals("📋 Сборы")) {
            if (userNames.containsKey(userId)) {
                showAllEvents(chatId);
            } else {
                sendMessage(chatId, "Пожалуйста, сначала введите /start для регистрации");
            }
        } else if (messageText.equals("✅ Подписаться")) {
            if (userNames.containsKey(userId)) {
                showAvailableEvents(userId, chatId);
            } else {
                sendMessage(chatId, "Пожалуйста, сначала введите /start для регистрации");
            }
        } else if (messageText.equals("❌ Отписаться")) {
            if (userNames.containsKey(userId)) {
                showUserSubscriptions(userId, chatId);
            } else {
                sendMessage(chatId, "Пожалуйста, сначала введите /start для регистрации");
            }
        } else if (messageText.equals("⚙️ Модерация")) {
            if (userNames.containsKey(userId)) {
                showModerationMenu(chatId);
            } else {
                sendMessage(chatId, "Пожалуйста, сначала введите /start для регистрации");
            }
        }
        // Обработка reply-кнопок меню модерации
        else if (messageText.equals("➕ Создать сбор")) {
            if (userNames.containsKey(userId)) {
                startEventCreation(userId, chatId);
            } else {
                sendMessage(chatId, "Пожалуйста, сначала введите /start для регистрации");
            }
        } else if (messageText.equals("✏️ Редактировать сбор")) {
            if (userNames.containsKey(userId)) {
                showUserEventsForEditing(userId, chatId);
            } else {
                sendMessage(chatId, "Пожалуйста, сначала введите /start для регистрации");
            }
        } else if (messageText.equals("❌ Отменить сбор")) {
            if (userNames.containsKey(userId)) {
                showUserEventsForCancellation(userId, chatId);
            } else {
                sendMessage(chatId, "Пожалуйста, сначала введите /start для регистрации");
            }
        } else if (messageText.equals("👥 Подписчики")) {
            if (userNames.containsKey(userId)) {
                showEventsForSubscribers(chatId);
            } else {
                sendMessage(chatId, "Пожалуйста, сначала введите /start для регистрации");
            }
        } else if (messageText.equals("🔙 Назад")) {
            if (userNames.containsKey(userId)) {
                showMainMenuKeyboard(chatId);
            }
        } else {
            if (userNames.containsKey(userId)) {
                // Показываем меню при любом другом тексте
                showMainMenuKeyboard(chatId);
            } else {
                sendMessage(chatId, "Пожалуйста, введите /start для начала работы");
            }
        }
    }

    private void handleStartCommand(Long userId, Long chatId, String username) throws TelegramApiException {
        // Проверяем, зарегистрирован ли пользователь в памяти бота
        if (userNames.containsKey(userId)) {
            // Пользователь уже в памяти - приветствуем
            welcomeRegisteredUser(userId, chatId);
            return;
        }

        // Проверяем, есть ли пользователь в базе данных
        try {
            if (databaseService.userExists(userId)) {
                // Пользователь есть в базе - получаем его имя
                String userName = databaseService.getUserName(userId);
                if (userName != null && !userName.isEmpty() && !userName.equals("null")) {
                    // Добавляем пользователя в память бота
                    userNames.put(userId, userName);
                    // Приветствуем
                    welcomeRegisteredUser(userId, chatId);
                } else {
                    // Имя не найдено - начинаем регистрацию
                    startRegistration(userId, chatId);
                }
            } else {
                // Новый пользователь - начинаем регистрацию
                startRegistration(userId, chatId);
            }
        } catch (Exception e) {
            // Ошибка при обращении к базе данных - начинаем регистрацию
            System.err.println("Ошибка при проверке пользователя: " + e.getMessage());
            startRegistration(userId, chatId);
        }
    }

    // Приветствие зарегистрированного пользователя
    private void welcomeRegisteredUser(Long userId, Long chatId) throws TelegramApiException {
        // Получаем имя пользователя
        String userName = userNames.get(userId);
        if (userName == null || userName.isEmpty()) {
            // На всякий случай, если имя не найдено
            sendMessage(chatId, "Добро пожаловать! Что-то пошло не так, попробуйте снова.");
            showMainMenuKeyboard(chatId);
            return;
        }

        // Простое приветствие
        String welcomeMessage = "С возвращением, " + userName + "!\n" +
                "Рады снова видеть вас!";

        sendMessage(chatId, welcomeMessage);
        showMainMenuKeyboard(chatId);
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) throws TelegramApiException, IOException {
        String data = callbackQuery.getData();
        Long chatId = callbackQuery.getMessage().getChatId();
        Long userId = callbackQuery.getFrom().getId();

        if (!userNames.containsKey(userId)) {
            sendMessage(chatId, "Пожалуйста, сначала введите /start для регистрации");
            return;
        }

        // Навигация
        if (data.equals("back")) {
            showMainMenuKeyboard(chatId);
            return;
        }
        if (data.equals("back_to_moderation")) {
            showModerationMenu(chatId);
            return;
        }
        if (data.equals("cancel_creation")) {
            cancelOperation(userId, chatId);
            return;
        }
        if (data.equals("cancel_editing")) {
            cancelOperation(userId, chatId);
            showModerationMenu(chatId);
            return;
        }

        // Подписка/отписка
        if (data.startsWith("subscribe_")) {
            Long eventId = Long.parseLong(data.substring(10));
            subscribeToEvent(userId, chatId, eventId);
        }
        else if (data.startsWith("unsubscribe_")) {
            Long eventId = Long.parseLong(data.substring(12));
            unsubscribeFromEvent(userId, chatId, eventId);
        }
        // Просмотр подписчиков
        else if (data.startsWith("subscribers_")) {
            Long eventId = Long.parseLong(data.substring(12));
            showEventSubscribers(chatId, eventId);
        }
        // Редактирование сбора
        else if (data.startsWith("edit_")) {
            Long eventId = Long.parseLong(data.substring(5));
            startEventEditing(userId, chatId, eventId);
        }
        // Отмена сбора
        else if (data.startsWith("cancel_")) {
            Long eventId = Long.parseLong(data.substring(7));
            cancelEvent(userId, chatId, eventId);
        }
        // Редактирование полей сбора
        else if (data.startsWith("edit_title_")) {
            Long eventId = Long.parseLong(data.substring(11));
            startEditingField(userId, chatId, eventId, "title", "Введите новое название сбора:");
        }
        else if (data.startsWith("edit_date_")) {
            Long eventId = Long.parseLong(data.substring(10));
            startEditingField(userId, chatId, eventId, "date", "Введите новую дату в формате ДД.ММ.ГГГГ:");
        }
        else if (data.startsWith("edit_time_")) {
            Long eventId = Long.parseLong(data.substring(10));
            startEditingField(userId, chatId, eventId, "time", "Введите новое время в формате ЧЧ:ММ:");
        }
        else if (data.startsWith("edit_location_")) {
            Long eventId = Long.parseLong(data.substring(14));
            startEditingField(userId, chatId, eventId, "location", "Введите новое место проведения:");
        }
        // Подтверждение отмены сбора
        else if (data.startsWith("confirm_cancel_")) {
            Long eventId = Long.parseLong(data.substring(15));
            confirmEventCancellation(userId, chatId, eventId);
        }
    }

    private void handleUserState(Long userId, Long chatId, String messageText, String state,
                                 String username, String firstName, String lastName)
            throws TelegramApiException, IOException {

        switch (state) {
            case "awaiting_name" -> {
                String userName = messageText.trim();

                // Валидация имени
                if (userName.isEmpty()) {
                    sendMessage(chatId, "Имя не может быть пустым. Пожалуйста, введите ваше имя:");
                    return;
                }

                if (userName.length() > 50) {
                    sendMessage(chatId, "Имя слишком длинное. Максимум 50 символов. Введите имя заново:");
                    return;
                }

                if (!userName.matches("^[А-Яа-яЁё\\s]+$")) {
                    sendMessage(chatId, "Имя может содержать только русские буквы и пробелы. Введите имя заново:");
                    return;
                }

                if (userName.trim().isEmpty()) {
                    sendMessage(chatId, "Имя не может состоять только из пробелов. Введите ваше имя:");
                    return;
                }

                if (userName.contains("  ")) {
                    sendMessage(chatId, "Имя не должно содержать несколько пробелов подряд. Введите имя заново:");
                    return;
                }

                if (!Character.isLetter(userName.charAt(0))) {
                    sendMessage(chatId, "Имя должно начинаться с буквы. Введите имя заново:");
                    return;
                }

                // Форматируем имя
                String formattedName = formatUserName(userName);

                // Сохраняем имя
                userNames.put(userId, formattedName);
                userState.remove(userId);

                // Регистрируем пользователя в базе данных
                if (!databaseService.userExists(userId)) {
                    databaseService.addUser(userId, username, formattedName);
                }

                sendMessage(chatId, "Регистрация завершена! Добро пожаловать, " + formattedName + "!");
                showMainMenuKeyboard(chatId);
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

            case "editing_event_title" -> {
                Long eventId = editingEventId.get(userId);
                if (eventId == null) {
                    sendMessage(chatId, "Ошибка: не найден ID сбора для редактирования.");
                    showModerationMenu(chatId);
                    return;
                }

                try {
                    Map<String, String> event = databaseService.getEventById(eventId);
                    if (event.isEmpty()) {
                        sendMessage(chatId, "Сбор не найден.");
                        showModerationMenu(chatId);
                        return;
                    }

                    databaseService.updateEvent(eventId, messageText,
                            event.get("Date"), event.get("Time"), event.get("Location"));

                    sendMessage(chatId, "✅ Название сбора обновлено на: " + messageText);

                    // Очищаем состояние
                    userState.remove(userId);
                    editingEventId.remove(userId);
                    editingField.remove(userId);

                    showModerationMenu(chatId);

                } catch (Exception e) {
                    sendMessage(chatId, "❌ Ошибка при обновлении названия: " + e.getMessage());
                    showModerationMenu(chatId);
                }
            }

            case "editing_event_date" -> {
                Long eventId = editingEventId.get(userId);
                if (eventId == null) {
                    sendMessage(chatId, "Ошибка: не найден ID сбора для редактирования.");
                    showModerationMenu(chatId);
                    return;
                }

                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
                    LocalDate.parse(messageText, formatter);

                    Map<String, String> event = databaseService.getEventById(eventId);
                    if (event.isEmpty()) {
                        sendMessage(chatId, "Сбор не найден.");
                        showModerationMenu(chatId);
                        return;
                    }

                    databaseService.updateEvent(eventId, event.get("Title"),
                            messageText, event.get("Time"), event.get("Location"));

                    sendMessage(chatId, "✅ Дата сбора обновлена на: " + messageText);

                    // Очищаем состояние
                    userState.remove(userId);
                    editingEventId.remove(userId);
                    editingField.remove(userId);

                    showModerationMenu(chatId);

                } catch (DateTimeParseException e) {
                    sendMessage(chatId, "Неверный формат даты! Введите дату в формате ДД.ММ.ГГГГ:");
                } catch (Exception e) {
                    sendMessage(chatId, "❌ Ошибка при обновлении даты: " + e.getMessage());
                    showModerationMenu(chatId);
                }
            }

            case "editing_event_time" -> {
                Long eventId = editingEventId.get(userId);
                if (eventId == null) {
                    sendMessage(chatId, "Ошибка: не найден ID сбора для редактирования.");
                    showModerationMenu(chatId);
                    return;
                }

                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
                    LocalTime.parse(messageText, formatter);

                    Map<String, String> event = databaseService.getEventById(eventId);
                    if (event.isEmpty()) {
                        sendMessage(chatId, "Сбор не найден.");
                        showModerationMenu(chatId);
                        return;
                    }

                    databaseService.updateEvent(eventId, event.get("Title"),
                            event.get("Date"), messageText, event.get("Location"));

                    sendMessage(chatId, "✅ Время сбора обновлено на: " + messageText);

                    // Очищаем состояние
                    userState.remove(userId);
                    editingEventId.remove(userId);
                    editingField.remove(userId);

                    showModerationMenu(chatId);

                } catch (DateTimeParseException e) {
                    sendMessage(chatId, "Неверный формат времени! Введите время в формате ЧЧ:ММ:");
                } catch (Exception e) {
                    sendMessage(chatId, "❌ Ошибка при обновлении времени: " + e.getMessage());
                    showModerationMenu(chatId);
                }
            }

            case "editing_event_location" -> {
                Long eventId = editingEventId.get(userId);
                if (eventId == null) {
                    sendMessage(chatId, "Ошибка: не найден ID сбора для редактирования.");
                    showModerationMenu(chatId);
                    return;
                }

                try {
                    Map<String, String> event = databaseService.getEventById(eventId);
                    if (event.isEmpty()) {
                        sendMessage(chatId, "Сбор не найден.");
                        showModerationMenu(chatId);
                        return;
                    }

                    databaseService.updateEvent(eventId, event.get("Title"),
                            event.get("Date"), event.get("Time"), messageText);

                    sendMessage(chatId, "✅ Место сбора обновлено на: " + messageText);

                    // Очищаем состояние
                    userState.remove(userId);
                    editingEventId.remove(userId);
                    editingField.remove(userId);

                    showModerationMenu(chatId);

                } catch (Exception e) {
                    sendMessage(chatId, "❌ Ошибка при обновлении места: " + e.getMessage());
                    showModerationMenu(chatId);
                }
            }
        }
    }

    private void startRegistration(Long userId, Long chatId) throws TelegramApiException {
        userState.put(userId, "awaiting_name");
        sendMessage(chatId, "Добро пожаловать! Для начала работы, пожалуйста, введите ваше имя:");
    }

    private void startEventCreation(Long userId, Long chatId) throws TelegramApiException {
        userState.put(userId, "awaiting_event_title");

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Создание нового сбора. Введите название сбора:\n\n" +
                        "Для отмены создания нажмите кнопку ниже:")
                .build();

        // inline-кнопка отмены
        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardButton cancelButton = InlineKeyboardButton.builder()
                .text("❌ Отменить создание")
                .callbackData("cancel_creation")
                .build();
        rows.add(new InlineKeyboardRow(cancelButton));

        message.setReplyMarkup(new InlineKeyboardMarkup(rows));
        telegramClient.execute(message);
    }

    private void startEventEditing(Long userId, Long chatId, Long eventId) throws TelegramApiException, IOException {
        try {
            // Проверяем, является ли пользователь создателем события
            if (!databaseService.isEventCreator(userId, eventId)) {
                sendMessage(chatId, "Вы не являетесь создателем этого сбора.");
                showModerationMenu(chatId);
                return;
            }

            // Сохраняем ID редактируемого события
            editingEventId.put(userId, eventId);

            // Показываем меню редактирования
            showEditEventMenu(userId, chatId, eventId);

        } catch (Exception e) {
            sendMessage(chatId, "❌ Ошибка: " + e.getMessage());
            showModerationMenu(chatId);
        }
    }

    private void showEditEventMenu(Long userId, Long chatId, Long eventId) throws TelegramApiException, IOException {
        Map<String, String> event = databaseService.getEventById(eventId);

        if (event.isEmpty()) {
            sendMessage(chatId, "Сбор не найден.");
            showModerationMenu(chatId);
            return;
        }

        // Получаем username организатора
        Long creatorId = Long.parseLong(event.get("Created By"));
        String organizerName = event.get("Creator Name");
        String organizerUsername = databaseService.getUsernameByTelegramId(creatorId);
        String organizerInfo = formatOrganizerInfo(organizerName, organizerUsername);

        // Показываем информацию о событии и меню редактирования
        String eventInfo = "✏️ Редактирование сбора:\n\n" +
                "📌 Название: " + event.get("Title") + "\n" +
                "📅 Дата: " + event.get("Date") + "\n" +
                "⏰ Время: " + event.get("Time") + "\n" +
                "📍 Место: " + event.get("Location") + "\n" +
                "👤 Организатор: " + organizerInfo + "\n\n" +
                "Что вы хотите изменить?";

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(eventInfo)
                .build();

        List<InlineKeyboardRow> rows = new ArrayList<>();

        InlineKeyboardButton titleButton = InlineKeyboardButton.builder()
                .text("✏️ Название")
                .callbackData("edit_title_" + eventId)
                .build();

        InlineKeyboardButton dateButton = InlineKeyboardButton.builder()
                .text("📅 Дата")
                .callbackData("edit_date_" + eventId)
                .build();

        InlineKeyboardButton timeButton = InlineKeyboardButton.builder()
                .text("⏰ Время")
                .callbackData("edit_time_" + eventId)
                .build();

        InlineKeyboardButton locationButton = InlineKeyboardButton.builder()
                .text("📍 Место")
                .callbackData("edit_location_" + eventId)
                .build();

        InlineKeyboardButton cancelButton = InlineKeyboardButton.builder()
                .text("❌ Отменить редактирование")
                .callbackData("cancel_editing")
                .build();

        rows.add(new InlineKeyboardRow(titleButton));
        rows.add(new InlineKeyboardRow(dateButton));
        rows.add(new InlineKeyboardRow(timeButton));
        rows.add(new InlineKeyboardRow(locationButton));
        rows.add(new InlineKeyboardRow(cancelButton));

        message.setReplyMarkup(new InlineKeyboardMarkup(rows));
        telegramClient.execute(message);
    }

    private void cancelEvent(Long userId, Long chatId, Long eventId) throws TelegramApiException, IOException {
        try {
            // Проверяем, является ли пользователь создателем события
            if (!databaseService.isEventCreator(userId, eventId)) {
                sendMessage(chatId, "Вы не являетесь создателем этого сбора.");
                showModerationMenu(chatId);
                return;
            }

            Map<String, String> event = databaseService.getEventById(eventId);

            if (event.isEmpty()) {
                sendMessage(chatId, "Сбор не найден.");
                showModerationMenu(chatId);
                return;
            }

            // Получаем username организатора
            Long creatorId = Long.parseLong(event.get("Created By"));
            String organizerName = event.get("Creator Name");
            String organizerUsername = databaseService.getUsernameByTelegramId(creatorId);
            String organizerInfo = formatOrganizerInfo(organizerName, organizerUsername);

            // Показываем подтверждение отмены
            String confirmationText = "❌ Вы уверены, что хотите отменить сбор?\n\n" +
                    "📌 Название: " + event.get("Title") + "\n" +
                    "📅 Дата: " + event.get("Date") + " " + event.get("Time") + "\n" +
                    "📍 Место: " + event.get("Location") + "\n" +
                    "👤 Организатор: " + organizerInfo + "\n\n" +
                    "Отмена сбора удалит его и все подписки!";

            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(confirmationText)
                    .build();

            List<InlineKeyboardRow> rows = new ArrayList<>();

            InlineKeyboardButton confirmButton = InlineKeyboardButton.builder()
                    .text("✅ Да, отменить сбор")
                    .callbackData("confirm_cancel_" + eventId)
                    .build();

            InlineKeyboardButton cancelButton = InlineKeyboardButton.builder()
                    .text("❌ Нет, вернуться")
                    .callbackData("back_to_moderation")
                    .build();

            rows.add(new InlineKeyboardRow(confirmButton));
            rows.add(new InlineKeyboardRow(cancelButton));

            message.setReplyMarkup(new InlineKeyboardMarkup(rows));
            telegramClient.execute(message);

        } catch (Exception e) {
            sendMessage(chatId, "❌ Ошибка: " + e.getMessage());
            showModerationMenu(chatId);
        }
    }

    private void createEvent(Long userId, Long chatId, Map<String, String> eventData)
            throws TelegramApiException, IOException {
        try {
            String userName = userNames.get(userId);

            // Получаем username пользователя
            String username = databaseService.getUsernameByTelegramId(userId);
            String organizerInfo = formatOrganizerInfo(userName, username);

            Long eventId = databaseService.addEvent(
                    eventData.get("title"),
                    eventData.get("date"),
                    eventData.get("time"),
                    eventData.get("location"),
                    userId,
                    userName
            );

            String response = "✅ Сбор успешно создан!\n\n" +
                    "📌 Название: " + eventData.get("title") + "\n" +
                    "📅 Дата: " + eventData.get("date") + "\n" +
                    "⏰ Время: " + eventData.get("time") + "\n" +
                    "📍 Место: " + eventData.get("location") + "\n" +
                    "👤 Организатор: " + organizerInfo;

            sendMessage(chatId, response);
            showMainMenuKeyboard(chatId);

        } catch (Exception e) {
            sendMessage(chatId, "❌ Ошибка при создании сбора: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startEditingField(Long userId, Long chatId, Long eventId, String field, String prompt)
            throws TelegramApiException, IOException {

        try {
            if (!databaseService.isEventCreator(userId, eventId)) {
                sendMessage(chatId, "Вы не являетесь создателем этого сбора.");
                showModerationMenu(chatId);
                return;
            }

            // Сохраняем информацию о редактировании
            editingEventId.put(userId, eventId);
            editingField.put(userId, field);
            userState.put(userId, "editing_event_" + field);

            sendMessage(chatId, prompt);

        } catch (Exception e) {
            sendMessage(chatId, "❌ Ошибка: " + e.getMessage());
            showModerationMenu(chatId);
        }
    }

    private void confirmEventCancellation(Long userId, Long chatId, Long eventId)
            throws TelegramApiException, IOException {
        try {
            Map<String, String> event = databaseService.getEventById(eventId);

            if (event.isEmpty()) {
                sendMessage(chatId, "Сбор не найден.");
                showModerationMenu(chatId);
                return;
            }

            // Получаем username организатора
            Long creatorId = Long.parseLong(event.get("Created By"));
            String organizerName = event.get("Creator Name");
            String organizerUsername = databaseService.getUsernameByTelegramId(creatorId);
            String organizerInfo = formatOrganizerInfo(organizerName, organizerUsername);

            // Отменяем сбор
            databaseService.cancelEvent(eventId, userId);

            // Получаем количество подписчиков
            int subscriberCount = databaseService.getEventSubscribersCount(eventId);

            // Отправляем сообщение об успешной отмене
            String successMessage = "✅ Сбор успешно отменен!\n\n" +
                    "📌 Название: " + event.get("Title") + "\n" +
                    "📅 Дата: " + event.get("Date") + " " + event.get("Time") + "\n" +
                    "📍 Место: " + event.get("Location") + "\n" +
                    "👤 Организатор: " + organizerInfo + "\n" +
                    "🗑️ Удалено подписок: " + subscriberCount;

            sendMessage(chatId, successMessage);
            showModerationMenu(chatId);

        } catch (Exception e) {
            sendMessage(chatId, "❌ Ошибка при отмене сбора: " + e.getMessage());
            showModerationMenu(chatId);
        }
    }

    private void showAvailableEvents(Long userId, Long chatId) throws TelegramApiException, IOException {
        List<Map<String, String>> events = databaseService.getActiveEvents();
        List<Map<String, String>> userSubscriptions = databaseService.getUserSubscriptions(userId);

        // Фильтруем события, на которые пользователь не подписан
        Set<Long> subscribedEventIds = new HashSet<>();
        for (Map<String, String> sub : userSubscriptions) {
            try {
                subscribedEventIds.add(Long.parseLong(sub.get("Event ID")));
            } catch (NumberFormatException e) {
                // Игнорируем некорректные ID
            }
        }

        List<Map<String, String>> availableEvents = new ArrayList<>();
        for (Map<String, String> event : events) {
            try {
                Long eventId = Long.parseLong(event.get("ID"));

                // Пропускаем если пользователь уже подписан
                if (subscribedEventIds.contains(eventId)) {
                    continue;
                }

                // Пропускаем если пользователь организатор
                Long createdBy = Long.parseLong(event.get("Created By"));
                if (createdBy.equals(userId)) {
                    continue;
                }

                availableEvents.add(event);
            } catch (NumberFormatException e) {
                // Игнорируем некорректные записи
            }
        }

        if (availableEvents.isEmpty()) {
            sendMessage(chatId, "📭 Нет доступных сборов для подписки.");
            showMainMenuKeyboard(chatId);
            return;
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("📋 Доступные сборы для подписки:")
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

        message.setReplyMarkup(new InlineKeyboardMarkup(rows));
        telegramClient.execute(message);
    }

    private void showUserSubscriptions(Long userId, Long chatId) throws TelegramApiException, IOException {
        List<Map<String, String>> userSubscriptions = databaseService.getUserSubscriptions(userId);

        if (userSubscriptions.isEmpty()) {
            sendMessage(chatId, "📭 Вы не подписаны ни на один сбор.");
            showMainMenuKeyboard(chatId);
            return;
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("📋 Ваши подписки:")
                .build();

        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (Map<String, String> sub : userSubscriptions) {
            try {
                Long eventId = Long.parseLong(sub.get("Event ID"));
                Map<String, String> event = databaseService.getEventById(eventId);

                if (!event.isEmpty()) {
                    String buttonText = event.get("Title") + " - " + event.get("Date") + " " + event.get("Time");
                    if (buttonText.length() > 64) {
                        buttonText = buttonText.substring(0, 61) + "...";
                    }

                    InlineKeyboardButton button = InlineKeyboardButton.builder()
                            .text(buttonText)
                            .callbackData("unsubscribe_" + event.get("ID"))
                            .build();

                    rows.add(new InlineKeyboardRow(button));
                }
            } catch (Exception e) {
                // Игнорируем некорректные записи
            }
        }

        message.setReplyMarkup(new InlineKeyboardMarkup(rows));
        telegramClient.execute(message);
    }

    private void showUserEventsForEditing(Long userId, Long chatId) throws TelegramApiException, IOException {
        List<Map<String, String>> userEvents = databaseService.getUserEvents(userId);

        if (userEvents.isEmpty()) {
            sendMessage(chatId, "✏️ У вас нет сборов для редактирования.");
            showModerationMenu(chatId);
            return;
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("✏️ Выберите сбор для редактирования:")
                .build();

        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (Map<String, String> event : userEvents) {
            String buttonText = event.get("Title") + " - " + event.get("Date");
            if (buttonText.length() > 64) {
                buttonText = buttonText.substring(0, 61) + "...";
            }

            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text(buttonText)
                    .callbackData("edit_" + event.get("ID"))
                    .build();

            rows.add(new InlineKeyboardRow(button));
        }

        message.setReplyMarkup(new InlineKeyboardMarkup(rows));
        telegramClient.execute(message);
    }

    private void showUserEventsForCancellation(Long userId, Long chatId) throws TelegramApiException, IOException {
        List<Map<String, String>> userEvents = databaseService.getUserEvents(userId);

        if (userEvents.isEmpty()) {
            sendMessage(chatId, "❌ У вас нет сборов для отмены.");
            showModerationMenu(chatId);
            return;
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("❌ Выберите сбор для отмены:")
                .build();

        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (Map<String, String> event : userEvents) {
            String buttonText = event.get("Title") + " - " + event.get("Date");
            if (buttonText.length() > 64) {
                buttonText = buttonText.substring(0, 61) + "...";
            }

            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text(buttonText)
                    .callbackData("cancel_" + event.get("ID"))
                    .build();

            rows.add(new InlineKeyboardRow(button));
        }

        message.setReplyMarkup(new InlineKeyboardMarkup(rows));
        telegramClient.execute(message);
    }

    private void showEventsForSubscribers(Long chatId) throws TelegramApiException, IOException {
        List<Map<String, String>> events = databaseService.getActiveEvents();

        if (events.isEmpty()) {
            sendMessage(chatId, "📭 Нет активных сборов.");
            showMainMenuKeyboard(chatId);
            return;
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("👥 Выберите сбор для просмотра подписчиков:")
                .build();

        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (Map<String, String> event : events) {
            String buttonText = event.get("Title") + " - " + event.get("Date");
            if (buttonText.length() > 64) {
                buttonText = buttonText.substring(0, 61) + "...";
            }

            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text(buttonText)
                    .callbackData("subscribers_" + event.get("ID"))
                    .build();

            rows.add(new InlineKeyboardRow(button));
        }

        message.setReplyMarkup(new InlineKeyboardMarkup(rows));
        telegramClient.execute(message);
    }

    private void showEventSubscribers(Long chatId, Long eventId) throws TelegramApiException, IOException {
        try {
            List<Map<String, String>> subscriptions = databaseService.getEventSubscriptions(eventId);
            Map<String, String> event = databaseService.getEventById(eventId);

            if (subscriptions.isEmpty()) {
                sendMessage(chatId, "👥 На этот сбор пока никто не подписался.");
                showMainMenuKeyboard(chatId);
                return;
            }

            // Получаем username организатора
            Long creatorId = Long.parseLong(event.get("Created By"));
            String organizerName = event.get("Creator Name");
            String organizerUsername = databaseService.getUsernameByTelegramId(creatorId);
            String organizerInfo = formatOrganizerInfo(organizerName, organizerUsername);

            StringBuilder messageText = new StringBuilder();
            messageText.append("📌 Сбор: ").append(event.get("Title")).append("\n");
            messageText.append("📅 Дата: ").append(event.get("Date")).append(" ").append(event.get("Time")).append("\n");
            messageText.append("📍 Место: ").append(event.get("Location")).append("\n");
            messageText.append("👤 Организатор: ").append(organizerInfo).append("\n\n");
            messageText.append("👥 Подписчики (").append(subscriptions.size()).append("):\n\n");

            for (int i = 0; i < subscriptions.size(); i++) {
                Map<String, String> sub = subscriptions.get(i);
                String name = sub.get("Name");
                String username = sub.get("Username");

                String userLine = (i + 1) + ". " + formatOrganizerInfo(name, username);
                messageText.append(userLine).append("\n");
            }

            sendMessage(chatId, messageText.toString());
            showMainMenuKeyboard(chatId);

        } catch (Exception e) {
            sendMessage(chatId, "❌ Ошибка при получении списка подписчиков: " + e.getMessage());
            e.printStackTrace();
            showMainMenuKeyboard(chatId);
        }
    }

    private void showAllEvents(Long chatId) throws TelegramApiException, IOException {
        List<Map<String, String>> events = databaseService.getActiveEvents();

        if (events.isEmpty()) {
            sendMessage(chatId, "📭 Нет запланированных сборов.");
            showMainMenuKeyboard(chatId);
            return;
        }

        StringBuilder messageText = new StringBuilder("📋 Все сборы:\n\n");

        for (Map<String, String> event : events) {
            // Получаем username организатора
            Long creatorId = Long.parseLong(event.get("Created By"));
            String organizerName = event.get("Creator Name");
            String organizerUsername = databaseService.getUsernameByTelegramId(creatorId);
            String organizerInfo = formatOrganizerInfo(organizerName, organizerUsername);

            messageText.append("📌 Название: ").append(event.get("Title")).append("\n")
                    .append("📅 Дата: ").append(event.get("Date")).append("\n")
                    .append("⏰ Время: ").append(event.get("Time")).append("\n")
                    .append("📍 Место: ").append(event.get("Location")).append("\n")
                    .append("👤 Организатор: ").append(organizerInfo).append("\n")
                    .append("👥 Подписчиков: ").append(event.get("Subs Number")).append("\n\n");
        }

        sendMessage(chatId, messageText.toString());
        showMainMenuKeyboard(chatId);
    }

    private void subscribeToEvent(Long userId, Long chatId, Long eventId) throws TelegramApiException, IOException {
        try {
            // Проверяем, не является ли пользователь организатором
            if (databaseService.isEventCreator(userId, eventId)) {
                sendMessage(chatId, "Вы являетесь организатором этого сбора и не можете на него подписаться.");
                showMainMenuKeyboard(chatId);
                return;
            }

            if (databaseService.isUserSubscribed(userId, eventId)) {
                sendMessage(chatId, "Вы уже подписаны на этот сбор.");
                showMainMenuKeyboard(chatId);
                return;
            }

            String userName = userNames.get(userId);
            databaseService.addSubscription(userId, eventId, userName);
            sendMessage(chatId, "✅ Вы успешно подписались на сбор!");
            showMainMenuKeyboard(chatId);

        } catch (Exception e) {
            sendMessage(chatId, "❌ Ошибка при подписке: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void unsubscribeFromEvent(Long userId, Long chatId, Long eventId) throws TelegramApiException, IOException {
        try {
            databaseService.deleteSubscription(userId, eventId);
            sendMessage(chatId, "✅ Вы отписались от сбора.");
            showMainMenuKeyboard(chatId);
        } catch (Exception e) {
            sendMessage(chatId, "❌ Ошибка при отписке: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void cancelOperation(Long userId, Long chatId) throws TelegramApiException {
        userState.remove(userId);
        userEventData.remove(userId);
        editingEventId.remove(userId);
        editingField.remove(userId);
        sendMessage(chatId, "❌ Операция отменена.");
        showMainMenuKeyboard(chatId);
    }

    private void sendMessage(Long chatId, String messageText) throws TelegramApiException {
        SendMessage message = SendMessage.builder()
                .text(messageText)
                .chatId(chatId)
                .build();

        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            System.err.println("Ошибка отправки сообщения: " + e.getMessage());
            throw e;
        }
    }

    // Reply-клавиатура главного меню
    private void showMainMenuKeyboard(Long chatId) throws TelegramApiException {
        List<KeyboardRow> keyboard = new ArrayList<>();

        // Основные действия для всех
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📋 Сборы");          // Просмотр всех сборов

        KeyboardRow row2 = new KeyboardRow();
        row2.add("✅ Подписаться");    // Подписаться на сбор

        KeyboardRow row3 = new KeyboardRow();
        row3.add("❌ Отписаться");     // Отписаться от сбора

        KeyboardRow row4 = new KeyboardRow();
        row4.add("⚙️ Модерация");      // Меню модерации

        keyboard.add(row1);
        keyboard.add(row2);
        keyboard.add(row3);
        keyboard.add(row4);

        ReplyKeyboardMarkup replyMarkup = new ReplyKeyboardMarkup(keyboard);
        replyMarkup.setResizeKeyboard(true);
        replyMarkup.setOneTimeKeyboard(false);

        SendMessage message = SendMessage.builder()
                .text("📱 Главное меню. Выберите действие:")
                .chatId(chatId)
                .replyMarkup(replyMarkup)
                .build();

        telegramClient.execute(message);
    }

    // Меню модерации
    private void showModerationMenu(Long chatId) throws TelegramApiException {
        List<KeyboardRow> keyboard = new ArrayList<>();

        // Действия модератора/организатора
        KeyboardRow row1 = new KeyboardRow();
        row1.add("➕ Создать сбор");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("✏️ Редактировать сбор");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("❌ Отменить сбор");

        KeyboardRow row4 = new KeyboardRow();
        row4.add("👥 Подписчики");

        KeyboardRow row5 = new KeyboardRow();
        row5.add("🔙 Назад");

        keyboard.add(row1);
        keyboard.add(row2);
        keyboard.add(row3);
        keyboard.add(row4);
        keyboard.add(row5);

        ReplyKeyboardMarkup replyMarkup = new ReplyKeyboardMarkup(keyboard);
        replyMarkup.setResizeKeyboard(true);

        SendMessage message = SendMessage.builder()
                .text("⚙️ Меню модерации:")
                .chatId(chatId)
                .replyMarkup(replyMarkup)
                .build();

        telegramClient.execute(message);
    }

    // Формат вывода организатора
    private String formatOrganizerInfo(String name, String username) {
        if (name == null || name.isEmpty() || name.equals("null")) {
            name = "Неизвестный";
        }

        // Если есть username, добавляем @тег
        if (username != null && !username.isEmpty() && !username.equals("null")) {
            return name + " (@" + username + ")";
        }

        // Если нет username, показываем просто имя
        return name;
    }

    private String formatUserName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }

        // Убираем лишние пробелы
        name = name.trim().replaceAll("\\s+", " ");

        // Разделяем на слова
        String[] words = name.split(" ");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (!word.isEmpty()) {
                // Делаем первую букву заглавной, остальные строчные
                String capitalizedWord = word.substring(0, 1).toUpperCase() +
                        word.substring(1).toLowerCase();
                result.append(capitalizedWord).append(" ");
            }
        }

        return result.toString().trim();
    }
}