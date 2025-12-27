package com.example.fkrscheduletgbot.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class GoogleSheetsService {

    private static final String SPREADSHEET_ID = "1p988SQ0sitghK-HOPzBIA4CjQYonKqUA5qp3vS99OgM";
    private Sheets sheetsService;

    public GoogleSheetsService() {
        System.out.println("=== СОЗДАНИЕ GOOGLE SHEETS SERVICE ===");
        try {
            init();
            System.out.println("Google Sheets Service готов к работе!");
        } catch (Exception e) {
            System.err.println("ФАТАЛЬНАЯ ОШИБКА при создании Google Sheets Service:");
            e.printStackTrace();
        }
    }

    private void init() throws GeneralSecurityException, IOException {
        final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        GoogleCredential credential = GoogleCredential.fromStream(
                        new ClassPathResource("credentials.json").getInputStream())
                .createScoped(Arrays.asList(SheetsScopes.SPREADSHEETS));

        sheetsService = new Sheets.Builder(httpTransport, GsonFactory.getDefaultInstance(), credential)
                .setApplicationName("Telegram Schedule Bot")
                .build();

        createSheetsIfNeeded();
    }

    // ========== РАБОТА С ПОЛЬЗОВАТЕЛЯМИ ==========

    public void addUser(Long telegramId, String username, String name) throws IOException {
        Long nextId = getNextId("Users");

        List<List<Object>> values = Arrays.asList(Arrays.asList(
                nextId,
                telegramId,
                username != null ? username : "",
                name,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        ));

        ValueRange body = new ValueRange().setValues(values);
        sheetsService.spreadsheets().values()
                .append(SPREADSHEET_ID, "Users!A:E", body)
                .setValueInputOption("RAW")
                .execute();
    }

    public boolean userExists(Long telegramId) throws IOException {
        List<Map<String, String>> users = getAllRows("Users");
        return users.stream()
                .anyMatch(user -> String.valueOf(telegramId).equals(user.get("Telegram ID")));
    }

    public String getUserName(Long telegramId) throws IOException {
        List<Map<String, String>> users = getAllRows("Users");
        for (Map<String, String> user : users) {
            if (String.valueOf(telegramId).equals(user.get("Telegram ID"))) {
                return user.get("Name");
            }
        }
        return "Неизвестный";
    }

    // ========== РАБОТА СО СОБЫТИЯМИ ==========

    public Long addEvent(String title, String date, String time, String location,
                         Long createdBy, String creatorName) throws IOException {
        Long nextId = getNextId("Events");

        // Создаем событие с 0 подписчиков
        List<List<Object>> values = Arrays.asList(Arrays.asList(
                nextId,
                title,
                date,
                time,
                location,
                createdBy,
                0, // Начальное количество подписчиков
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        ));

        ValueRange body = new ValueRange().setValues(values);
        sheetsService.spreadsheets().values()
                .append(SPREADSHEET_ID, "Events!A:H", body)
                .setValueInputOption("RAW")
                .execute();

        return nextId;
    }

    public void updateEventSubscribers(Long eventId, int subscriberCount) throws IOException {
        List<Map<String, String>> events = getAllRows("Events");

        for (int i = 0; i < events.size(); i++) {
            Map<String, String> event = events.get(i);
            if (String.valueOf(eventId).equals(event.get("ID"))) {
                // Обновляем строку (i+2 т.к. первая строка заголовки)
                updateEventRow(i + 2, event, subscriberCount);
                break;
            }
        }
    }

    private void updateEventRow(int rowIndex, Map<String, String> eventData, int subscriberCount) throws IOException {
        List<Object> row = Arrays.asList(
                eventData.get("ID"),
                eventData.get("Title"),
                eventData.get("Date"),
                eventData.get("Time"),
                eventData.get("Location"),
                eventData.get("Created By"),
                subscriberCount,
                eventData.get("Created At")
        );

        List<List<Object>> values = Arrays.asList(row);
        ValueRange body = new ValueRange().setValues(values);

        sheetsService.spreadsheets().values()
                .update(SPREADSHEET_ID, "Events!A" + rowIndex + ":H" + rowIndex, body)
                .setValueInputOption("RAW")
                .execute();
    }

    public List<Map<String, String>> getAllEvents() throws IOException {
        return getAllRows("Events");
    }

    public List<Map<String, String>> getActiveEvents() throws IOException {
        // Все события активны, просто возвращаем все
        return getAllEvents();
    }

    // ========== РАБОТА С ПОДПИСКАМИ ==========

    public void addSubscription(Long userId, Long eventId, String userName) throws IOException {
        Long nextId = getNextId("Subscriptions");

        List<List<Object>> values = Arrays.asList(Arrays.asList(
                nextId,
                userName,
                eventId,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        ));

        ValueRange body = new ValueRange().setValues(values);
        sheetsService.spreadsheets().values()
                .append(SPREADSHEET_ID, "Subscriptions!A:D", body)
                .setValueInputOption("RAW")
                .execute();

        // Обновляем количество подписчиков в событии
        int subscriberCount = getEventSubscribersCount(eventId);
        updateEventSubscribers(eventId, subscriberCount);
    }

    public List<Map<String, String>> getUserSubscriptions(Long userId) throws IOException {
        List<Map<String, String>> allSubscriptions = getAllRows("Subscriptions");
        List<Map<String, String>> userSubscriptions = new ArrayList<>();
        String userName = getUserName(userId);

        for (Map<String, String> sub : allSubscriptions) {
            if (userName.equals(sub.get("Name"))) {
                userSubscriptions.add(sub);
            }
        }

        return userSubscriptions;
    }

    public boolean isUserSubscribed(Long userId, Long eventId) throws IOException {
        List<Map<String, String>> subscriptions = getUserSubscriptions(userId);
        String userName = getUserName(userId);

        return subscriptions.stream()
                .anyMatch(sub -> userName.equals(sub.get("Name")) &&
                        String.valueOf(eventId).equals(sub.get("Event ID")));
    }

    public void deleteSubscription(Long userId, Long eventId) throws IOException {
        List<Map<String, String>> subscriptions = getAllRows("Subscriptions");
        String userName = getUserName(userId);

        for (int i = 0; i < subscriptions.size(); i++) {
            Map<String, String> sub = subscriptions.get(i);
            if (userName.equals(sub.get("Name")) &&
                    String.valueOf(eventId).equals(sub.get("Event ID"))) {

                deleteRow("Subscriptions", i + 2); // +2 потому что первая строка заголовки

                // Обновляем количество подписчиков в событии
                int subscriberCount = getEventSubscribersCount(eventId);
                updateEventSubscribers(eventId, subscriberCount);

                break;
            }
        }
    }

    public int getEventSubscribersCount(Long eventId) throws IOException {
        List<Map<String, String>> allSubscriptions = getAllRows("Subscriptions");
        int count = 0;

        for (Map<String, String> sub : allSubscriptions) {
            if (String.valueOf(eventId).equals(sub.get("Event ID"))) {
                count++;
            }
        }

        return count;
    }

    public List<String> getEventSubscribersNames(Long eventId) throws IOException {
        List<Map<String, String>> allSubscriptions = getAllRows("Subscriptions");
        List<String> names = new ArrayList<>();

        for (Map<String, String> sub : allSubscriptions) {
            if (String.valueOf(eventId).equals(sub.get("Event ID"))) {
                names.add(sub.get("Name"));
            }
        }

        return names;
    }

    // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ==========

    private void createSheetsIfNeeded() throws IOException {
        System.out.println("Проверка и создание листов в таблице...");

        Spreadsheet spreadsheet = sheetsService.spreadsheets().get(SPREADSHEET_ID).execute();
        Set<String> existingSheets = new HashSet<>();

        for (Sheet sheet : spreadsheet.getSheets()) {
            existingSheets.add(sheet.getProperties().getTitle());
        }

        // Создаем листы с нужными заголовками
        String[] usersHeaders = {"ID", "Telegram ID", "Username", "Name", "Registered At"};
        String[] eventsHeaders = {"ID", "Title", "Date", "Time", "Location", "Created By", "Subs Number", "Created At"};
        String[] subsHeaders = {"ID", "Name", "Event ID", "Subscribed At"};

        if (!existingSheets.contains("Users")) {
            System.out.println("Создаю лист: Users");
            createSheet("Users", usersHeaders);
        }

        if (!existingSheets.contains("Events")) {
            System.out.println("Создаю лист: Events");
            createSheet("Events", eventsHeaders);
        }

        if (!existingSheets.contains("Subscriptions")) {
            System.out.println("Создаю лист: Subscriptions");
            createSheet("Subscriptions", subsHeaders);
        }
    }

    private void createSheet(String sheetName, String[] headers) throws IOException {
        AddSheetRequest addSheetRequest = new AddSheetRequest();
        SheetProperties sheetProperties = new SheetProperties();
        sheetProperties.setTitle(sheetName);
        addSheetRequest.setProperties(sheetProperties);

        BatchUpdateSpreadsheetRequest batchUpdateRequest = new BatchUpdateSpreadsheetRequest();
        batchUpdateRequest.setRequests(Collections.singletonList(
                new Request().setAddSheet(addSheetRequest)
        ));

        sheetsService.spreadsheets().batchUpdate(SPREADSHEET_ID, batchUpdateRequest).execute();

        // Добавляем заголовки
        ValueRange headerRow = new ValueRange();
        List<List<Object>> values = new ArrayList<>();
        values.add(Arrays.asList(headers));
        headerRow.setValues(values);

        sheetsService.spreadsheets().values()
                .update(SPREADSHEET_ID, sheetName + "!A1", headerRow)
                .setValueInputOption("RAW")
                .execute();
    }

    private List<Map<String, String>> getAllRows(String sheetName) throws IOException {
        ValueRange response = sheetsService.spreadsheets().values()
                .get(SPREADSHEET_ID, sheetName)
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
        List<Map<String, String>> data = getAllRows(sheetName);

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
                    // игнорируем
                }
            }
        }

        return maxId + 1;
    }

    private void deleteRow(String sheetName, int rowIndex) throws IOException {
        Spreadsheet spreadsheet = sheetsService.spreadsheets().get(SPREADSHEET_ID).execute();
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

            sheetsService.spreadsheets().batchUpdate(SPREADSHEET_ID, batchUpdateRequest).execute();
        }
    }
}