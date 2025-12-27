package com.example.fkrscheduletgbot.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.*;

@Service
public class GoogleSheetsService {

    // Жёстко заданный ID таблицы
    private final String spreadsheetId = "1p988SQ0sitghK-HOPzBIA4CjQYonKqUA5qp3vS99OgM";

    private static final String APPLICATION_NAME = "Telegram Schedule Bot";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Arrays.asList(
            SheetsScopes.SPREADSHEETS,
            SheetsScopes.DRIVE
    );

    private Sheets sheetsService;

    public GoogleSheetsService() {
        System.out.println("=== КОНСТРУКТОР GOOGLE SHEETS SERVICE ===");
        System.out.println("Spreadsheet ID: " + spreadsheetId);

        try {
            initializeSheetsService();
            initializeSheets();
            System.out.println("=== GOOGLE SHEETS SERVICE УСПЕШНО ИНИЦИАЛИЗИРОВАН ===");
        } catch (Exception e) {
            System.err.println("ОШИБКА при инициализации Google Sheets:");
            e.printStackTrace();
        }
    }

    private void initializeSheetsService() throws GeneralSecurityException, IOException {
        System.out.println("=== ИНИЦИАЛИЗАЦИЯ GOOGLE SHEETS API ===");
        final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        // Загружаем credentials из resources
        GoogleCredential credential = GoogleCredential.fromStream(
                        new ClassPathResource("credentials.json").getInputStream())
                .createScoped(SCOPES);

        sheetsService = new Sheets.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
        System.out.println("Google Sheets API успешно инициализирован");
    }

    private void initializeSheets() throws IOException {
        System.out.println("=== СОЗДАНИЕ ЛИСТОВ В GOOGLE SHEETS ===");
        System.out.println("Spreadsheet ID: " + spreadsheetId);

        try {
            Spreadsheet spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute();
            System.out.println("Текущие листы в таблице:");

            for (Sheet sheet : spreadsheet.getSheets()) {
                System.out.println("- " + sheet.getProperties().getTitle());
            }

            // Создаем заголовки для таблиц
            System.out.println("Создание листа Users...");
            createSheetIfNotExists("Users", Arrays.asList(
                    "ID", "Telegram ID", "Username", "First Name", "Last Name", "Registered At"
            ));

            System.out.println("Создание листа Events...");
            createSheetIfNotExists("Events", Arrays.asList(
                    "ID", "Title", "Description", "Date", "Time", "Direction",
                    "Created By", "Created At", "Is Active"
            ));

            System.out.println("Создание листа Subscriptions...");
            createSheetIfNotExists("Subscriptions", Arrays.asList(
                    "ID", "User ID", "Event ID", "Subscribed At"
            ));

            System.out.println("=== ЛИСТЫ УСПЕШНО СОЗДАНЫ ===");
        } catch (Exception e) {
            System.err.println("Ошибка при создании листов: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    private void createSheetIfNotExists(String sheetName, List<String> headers) throws IOException {
        Spreadsheet spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute();

        // Проверяем, существует ли лист
        boolean sheetExists = false;
        for (Sheet sheet : spreadsheet.getSheets()) {
            if (sheetName.equals(sheet.getProperties().getTitle())) {
                sheetExists = true;
                System.out.println("Лист '" + sheetName + "' уже существует");
                break;
            }
        }

        if (!sheetExists) {
            System.out.println("Создание нового листа: " + sheetName);

            // Создаем новый лист
            AddSheetRequest addSheetRequest = new AddSheetRequest();
            SheetProperties sheetProperties = new SheetProperties();
            sheetProperties.setTitle(sheetName);
            addSheetRequest.setProperties(sheetProperties);

            BatchUpdateSpreadsheetRequest batchUpdateRequest = new BatchUpdateSpreadsheetRequest();
            batchUpdateRequest.setRequests(Collections.singletonList(
                    new Request().setAddSheet(addSheetRequest)
            ));

            sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute();

            // Добавляем заголовки
            ValueRange headerRow = new ValueRange();
            List<List<Object>> values = new ArrayList<>();
            values.add(new ArrayList<>(headers));
            headerRow.setValues(values);

            sheetsService.spreadsheets().values()
                    .update(spreadsheetId, sheetName + "!A1", headerRow)
                    .setValueInputOption("RAW")
                    .execute();

            System.out.println("Лист '" + sheetName + "' успешно создан с заголовками");
        }
    }

    // ===== Методы для работы с пользователями =====

    public void addUser(Map<String, Object> userData) throws IOException {
        System.out.println("Добавление пользователя в Google Sheets: " + userData.get("telegramId"));

        List<List<Object>> values = Arrays.asList(Arrays.asList(
                getNextId("Users"),
                userData.get("telegramId"),
                userData.get("username"),
                userData.get("firstName"),
                userData.get("lastName"),
                userData.get("registeredAt")
        ));

        ValueRange body = new ValueRange().setValues(values);
        sheetsService.spreadsheets().values()
                .append(spreadsheetId, "Users!A:F", body)
                .setValueInputOption("RAW")
                .execute();

        System.out.println("Пользователь добавлен");
    }

    public List<Map<String, String>> getUsers() throws IOException {
        return getSheetData("Users");
    }

    public Map<String, String> findUserByTelegramId(Long telegramId) throws IOException {
        List<Map<String, String>> users = getUsers();
        for (Map<String, String> user : users) {
            if (String.valueOf(telegramId).equals(user.get("Telegram ID"))) {
                return user;
            }
        }
        return null;
    }

    // ===== Методы для работы с событиями =====

    public void addEvent(Map<String, Object> eventData) throws IOException {
        System.out.println("Добавление события в Google Sheets: " + eventData.get("title"));

        List<List<Object>> values = Arrays.asList(Arrays.asList(
                getNextId("Events"),
                eventData.get("title"),
                eventData.get("description"),
                eventData.get("date"),
                eventData.get("time"),
                eventData.get("direction"),
                eventData.get("createdBy"),
                eventData.get("createdAt"),
                eventData.get("isActive")
        ));

        ValueRange body = new ValueRange().setValues(values);
        sheetsService.spreadsheets().values()
                .append(spreadsheetId, "Events!A:I", body)
                .setValueInputOption("RAW")
                .execute();

        System.out.println("Событие добавлено");
    }

    public List<Map<String, String>> getEvents() throws IOException {
        return getSheetData("Events");
    }

    public List<Map<String, String>> getActiveEvents() throws IOException {
        List<Map<String, String>> events = getEvents();
        List<Map<String, String>> activeEvents = new ArrayList<>();

        for (Map<String, String> event : events) {
            if ("TRUE".equalsIgnoreCase(event.get("Is Active")) || "true".equalsIgnoreCase(event.get("Is Active"))) {
                activeEvents.add(event);
            }
        }

        return activeEvents;
    }

    // ===== Методы для работы с подписками =====

    public void addSubscription(Map<String, Object> subscriptionData) throws IOException {
        System.out.println("Добавление подписки в Google Sheets");

        List<List<Object>> values = Arrays.asList(Arrays.asList(
                getNextId("Subscriptions"),
                subscriptionData.get("userId"),
                subscriptionData.get("eventId"),
                subscriptionData.get("subscribedAt")
        ));

        ValueRange body = new ValueRange().setValues(values);
        sheetsService.spreadsheets().values()
                .append(spreadsheetId, "Subscriptions!A:D", body)
                .setValueInputOption("RAW")
                .execute();

        System.out.println("Подписка добавлена");
    }

    public List<Map<String, String>> getSubscriptions() throws IOException {
        return getSheetData("Subscriptions");
    }

    public void deleteSubscription(Long userId, Long eventId) throws IOException {
        System.out.println("Удаление подписки: userId=" + userId + ", eventId=" + eventId);

        List<Map<String, String>> subscriptions = getSubscriptions();

        for (int i = 0; i < subscriptions.size(); i++) {
            Map<String, String> sub = subscriptions.get(i);
            if (String.valueOf(userId).equals(sub.get("User ID")) &&
                    String.valueOf(eventId).equals(sub.get("Event ID"))) {

                // Находим и удаляем строку (i+2 т.к. первая строка - заголовки)
                deleteRow("Subscriptions", i + 2);
                System.out.println("Подписка удалена");
                break;
            }
        }
    }

    public List<Map<String, String>> getUserSubscriptions(Long userId) throws IOException {
        List<Map<String, String>> allSubscriptions = getSubscriptions();
        List<Map<String, String>> userSubscriptions = new ArrayList<>();

        for (Map<String, String> sub : allSubscriptions) {
            if (String.valueOf(userId).equals(sub.get("User ID"))) {
                userSubscriptions.add(sub);
            }
        }

        return userSubscriptions;
    }

    // ===== Вспомогательные методы =====

    private List<Map<String, String>> getSheetData(String sheetName) throws IOException {
        ValueRange response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, sheetName)
                .execute();

        List<List<Object>> values = response.getValues();

        if (values == null || values.size() <= 1) {
            return new ArrayList<>();
        }

        List<String> headers = new ArrayList<>();
        for (Object header : values.get(0)) {
            headers.add(header.toString());
        }

        List<Map<String, String>> result = new ArrayList<>();

        for (int i = 1; i < values.size(); i++) {
            List<Object> row = values.get(i);
            Map<String, String> rowMap = new HashMap<>();

            for (int j = 0; j < headers.size(); j++) {
                String value = (j < row.size()) ? row.get(j).toString() : "";
                rowMap.put(headers.get(j), value);
            }

            result.add(rowMap);
        }

        return result;
    }

    private Long getNextId(String sheetName) throws IOException {
        List<Map<String, String>> data = getSheetData(sheetName);

        if (data.isEmpty()) {
            return 1L;
        }

        Long maxId = 0L;
        for (Map<String, String> row : data) {
            String idStr = row.get("ID");
            if (idStr != null && !idStr.isEmpty()) {
                try {
                    Long id = Long.parseLong(idStr);
                    if (id > maxId) {
                        maxId = id;
                    }
                } catch (NumberFormatException e) {
                    // игнорируем некорректные ID
                }
            }
        }

        return maxId + 1;
    }

    private void deleteRow(String sheetName, int rowIndex) throws IOException {
        // Получаем ID листа
        Spreadsheet spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute();
        Integer sheetId = null;

        for (Sheet sheet : spreadsheet.getSheets()) {
            if (sheetName.equals(sheet.getProperties().getTitle())) {
                sheetId = sheet.getProperties().getSheetId();
                break;
            }
        }

        if (sheetId != null) {
            DeleteDimensionRequest deleteRequest = new DeleteDimensionRequest()
                    .setRange(new DimensionRange()
                            .setSheetId(sheetId)
                            .setDimension("ROWS")
                            .setStartIndex(rowIndex - 1)
                            .setEndIndex(rowIndex));

            BatchUpdateSpreadsheetRequest batchUpdateRequest = new BatchUpdateSpreadsheetRequest()
                    .setRequests(Collections.singletonList(
                            new Request().setDeleteDimension(deleteRequest)
                    ));

            sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute();
        }
    }
}