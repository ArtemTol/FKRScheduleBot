package com.example.fkrscheduletgbot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PostgresDatabaseService {

    private final JdbcTemplate jdbcTemplate;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    // ========== ПОЛЬЗОВАТЕЛИ ==========

    @Transactional
    public void addUser(Long telegramId, String username, String name) {
        jdbcTemplate.update(
                "INSERT INTO users (telegram_id, username, name, registered_at) VALUES (?, ?, ?, ?)",
                telegramId, username, name, LocalDateTime.now()
        );
    }

    public boolean userExists(Long telegramId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE telegram_id = ?",
                    Integer.class,
                    telegramId
            );
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public String getUserName(Long telegramId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT name FROM users WHERE telegram_id = ?",
                    String.class,
                    telegramId
            );
        } catch (Exception e) {
            return null;
        }
    }

    public String getUsernameByTelegramId(Long telegramId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT username FROM users WHERE telegram_id = ?",
                    String.class,
                    telegramId
            );
        } catch (Exception e) {
            return null;
        }
    }

    // ========== СОБЫТИЯ ==========

    @Transactional
    public Long addEvent(String title, String date, String time, String location,
                         Long createdBy, String creatorName) {

        // Парсим дату и время
        LocalDate eventDate = LocalDate.parse(date, DATE_FORMATTER);
        LocalTime eventTime = LocalTime.parse(time, TIME_FORMATTER);

        // Вставляем событие и возвращаем ID
        jdbcTemplate.update(
                "INSERT INTO events (title, event_date, event_time, location, created_by, creator_name, created_at, is_active, subscribers_count) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                title, eventDate, eventTime, location, createdBy, creatorName,
                LocalDateTime.now(), true, 0
        );

        // Получаем последний созданный ID
        return jdbcTemplate.queryForObject(
                "SELECT currval(pg_get_serial_sequence('events', 'id'))",
                Long.class
        );
    }

    public List<Map<String, String>> getActiveEvents() {
        try {
            return jdbcTemplate.query(
                    "SELECT * FROM events WHERE is_active = true ORDER BY event_date, event_time",
                    new EventRowMapper()
            );
        } catch (Exception e) {
            System.err.println("Ошибка при получении активных событий: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<Map<String, String>> getAllEvents() {
        try {
            return jdbcTemplate.query(
                    "SELECT * FROM events WHERE is_active = true ORDER BY event_date, event_time",
                    new EventRowMapper()
            );
        } catch (Exception e) {
            System.err.println("Ошибка при получении всех событий: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<Map<String, String>> getUserEvents(Long userId) {
        try {
            return jdbcTemplate.query(
                    "SELECT * FROM events WHERE created_by = ? AND is_active = true ORDER BY event_date, event_time",
                    new EventRowMapper(),
                    userId
            );
        } catch (Exception e) {
            System.err.println("Ошибка при получении событий пользователя " + userId + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public Map<String, String> getEventById(Long eventId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM events WHERE id = ?",
                    new EventRowMapper(),
                    eventId
            );
        } catch (Exception e) {
            System.err.println("Ошибка при получении события " + eventId + ": " + e.getMessage());
            return new HashMap<>();
        }
    }

    public boolean isEventCreator(Long userId, Long eventId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM events WHERE id = ? AND created_by = ? AND is_active = true",
                    Integer.class,
                    eventId, userId
            );
            return count != null && count > 0;
        } catch (Exception e) {
            System.err.println("Ошибка при проверке создателя события " + eventId + ": " + e.getMessage());
            return false;
        }
    }

    @Transactional
    public void updateEvent(Long eventId, String title, String date, String time, String location) {
        try {
            LocalDate eventDate = LocalDate.parse(date, DATE_FORMATTER);
            LocalTime eventTime = LocalTime.parse(time, TIME_FORMATTER);

            jdbcTemplate.update(
                    "UPDATE events SET title = ?, event_date = ?, event_time = ?, location = ? WHERE id = ?",
                    title, eventDate, eventTime, location, eventId
            );

            System.out.println("Обновлен сбор ID: " + eventId);
        } catch (Exception e) {
            System.err.println("Ошибка при обновлении события " + eventId + ": " + e.getMessage());
            throw e;
        }
    }

    @Transactional
    public void cancelEvent(Long eventId, Long userId) {
        try {
            // Проверяем, является ли пользователь создателем
            if (!isEventCreator(userId, eventId)) {
                throw new RuntimeException("Вы не являетесь создателем этого сбора");
            }

            // Удаляем подписки
            int deletedSubscriptions = jdbcTemplate.update("DELETE FROM subscriptions WHERE event_id = ?", eventId);

            // Деактивируем событие
            int updatedEvents = jdbcTemplate.update("UPDATE events SET is_active = false WHERE id = ?", eventId);

            System.out.println("Отменен сбор ID: " + eventId +
                    ", удалено подписок: " + deletedSubscriptions +
                    ", обновлено событий: " + updatedEvents);

        } catch (Exception e) {
            System.err.println("Ошибка при отмене сбора " + eventId + ": " + e.getMessage());
            throw e;
        }
    }

    public int getEventSubscribersCount(Long eventId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM subscriptions WHERE event_id = ?",
                    Integer.class,
                    eventId
            );
        } catch (Exception e) {
            System.err.println("Ошибка при получении количества подписчиков события " + eventId + ": " + e.getMessage());
            return 0;
        }
    }

    public String getEventCreatorUsername(Long eventId) {
        try {
            Map<String, String> event = getEventById(eventId);
            if (event.isEmpty()) {
                return null;
            }

            Long creatorId = Long.parseLong(event.get("Created By"));
            return getUsernameByTelegramId(creatorId);
        } catch (Exception e) {
            System.err.println("Ошибка при получении username создателя события " + eventId + ": " + e.getMessage());
            return null;
        }
    }

    // ========== ПОДПИСКИ ==========

    @Transactional
    public void addSubscription(Long userId, Long eventId, String userName) {
        try {
            // Проверяем, не подписан ли уже
            if (isUserSubscribed(userId, eventId)) {
                throw new RuntimeException("Вы уже подписаны на этот сбор");
            }

            // Проверяем, не является ли организатором
            if (isEventCreator(userId, eventId)) {
                throw new RuntimeException("Организатор не может подписаться на свой сбор");
            }

            // Добавляем подписку
            jdbcTemplate.update(
                    "INSERT INTO subscriptions (user_id, event_id, subscribed_at) VALUES (?, ?, ?)",
                    userId, eventId, LocalDateTime.now()
            );

            // Обновляем счетчик подписчиков
            updateEventSubscribersCount(eventId);

            System.out.println("Добавлена подписка: пользователь " + userId + " на событие " + eventId);

        } catch (Exception e) {
            System.err.println("Ошибка при добавлении подписки пользователя " + userId + " на событие " + eventId + ": " + e.getMessage());
            throw e;
        }
    }

    public List<Map<String, String>> getUserSubscriptions(Long userId) {
        try {
            return jdbcTemplate.query(
                    "SELECT s.*, e.title, e.event_date, e.event_time FROM subscriptions s " +
                            "JOIN events e ON s.event_id = e.id WHERE s.user_id = ? AND e.is_active = true " +
                            "ORDER BY e.event_date, e.event_time",
                    new RowMapper<Map<String, String>>() {
                        @Override
                        public Map<String, String> mapRow(ResultSet rs, int rowNum) throws SQLException {
                            Map<String, String> map = new HashMap<>();
                            map.put("Event ID", String.valueOf(rs.getLong("event_id")));
                            map.put("Title", rs.getString("title"));

                            // Форматируем дату и время
                            if (rs.getDate("event_date") != null) {
                                map.put("Date", rs.getDate("event_date").toLocalDate().format(DATE_FORMATTER));
                            }
                            if (rs.getTime("event_time") != null) {
                                map.put("Time", rs.getTime("event_time").toLocalTime().format(TIME_FORMATTER));
                            }

                            return map;
                        }
                    },
                    userId
            );
        } catch (Exception e) {
            System.err.println("Ошибка при получении подписок пользователя " + userId + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public boolean isUserSubscribed(Long userId, Long eventId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM subscriptions WHERE user_id = ? AND event_id = ?",
                    Integer.class,
                    userId, eventId
            );
            return count != null && count > 0;
        } catch (Exception e) {
            System.err.println("Ошибка при проверке подписки пользователя " + userId + " на событие " + eventId + ": " + e.getMessage());
            return false;
        }
    }

    @Transactional
    public void deleteSubscription(Long userId, Long eventId) {
        try {
            jdbcTemplate.update(
                    "DELETE FROM subscriptions WHERE user_id = ? AND event_id = ?",
                    userId, eventId
            );

            updateEventSubscribersCount(eventId);

            System.out.println("Удалена подписка: пользователь " + userId + " от события " + eventId);

        } catch (Exception e) {
            System.err.println("Ошибка при удалении подписки пользователя " + userId + " от события " + eventId + ": " + e.getMessage());
            throw e;
        }
    }

    public List<Map<String, String>> getEventSubscriptions(Long eventId) {
        try {
            return jdbcTemplate.query(
                    "SELECT s.*, u.name, u.username FROM subscriptions s " +
                            "JOIN users u ON s.user_id = u.telegram_id WHERE s.event_id = ? " +
                            "ORDER BY s.subscribed_at",
                    new RowMapper<Map<String, String>>() {
                        @Override
                        public Map<String, String> mapRow(ResultSet rs, int rowNum) throws SQLException {
                            Map<String, String> map = new HashMap<>();
                            map.put("User ID", String.valueOf(rs.getLong("user_id")));
                            map.put("Name", rs.getString("name"));
                            map.put("Username", rs.getString("username"));
                            return map;
                        }
                    },
                    eventId
            );
        } catch (Exception e) {
            System.err.println("Ошибка при получении подписчиков события " + eventId + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ==========

    private void updateEventSubscribersCount(Long eventId) {
        try {
            int count = getEventSubscribersCount(eventId);
            jdbcTemplate.update(
                    "UPDATE events SET subscribers_count = ? WHERE id = ?",
                    count, eventId
            );
        } catch (Exception e) {
            System.err.println("Ошибка при обновлении счетчика подписчиков события " + eventId + ": " + e.getMessage());
        }
    }

    // RowMapper для событий
    private static class EventRowMapper implements RowMapper<Map<String, String>> {
        @Override
        public Map<String, String> mapRow(ResultSet rs, int rowNum) throws SQLException {
            Map<String, String> event = new HashMap<>();
            event.put("ID", String.valueOf(rs.getLong("id")));
            event.put("Title", rs.getString("title"));

            // Форматируем дату и время
            if (rs.getDate("event_date") != null) {
                event.put("Date", rs.getDate("event_date").toLocalDate().format(DATE_FORMATTER));
            }
            if (rs.getTime("event_time") != null) {
                event.put("Time", rs.getTime("event_time").toLocalTime().format(TIME_FORMATTER));
            }

            event.put("Location", rs.getString("location"));
            event.put("Created By", String.valueOf(rs.getLong("created_by")));
            event.put("Creator Name", rs.getString("creator_name"));
            event.put("Subs Number", String.valueOf(rs.getInt("subscribers_count")));

            if (rs.getTimestamp("created_at") != null) {
                event.put("Created At", rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")));
            }

            return event;
        }
    }
}