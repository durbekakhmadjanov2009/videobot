import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Video;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class VideoBot extends TelegramLongPollingBot {
    private static final Logger LOGGER = Logger.getLogger(VideoBot.class.getName());
    private static final String BOT_TOKEN = "7848044111:AAFFu9Q8-ASLSTnntH-DI8WX7plsFEwiJo8";
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/video_bot";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "123";
    private static final int NAME_STATE = 1;
    private static final int VIDEO_STATE = 2;

    private Long adminChatId;
    private int adminState;
    private String videoName;

    @Override
    public String getBotUsername() {
        return "YourBotName"; // Replace with your bot username (e.g., @MyVideoBot)
    }

    @Override
    public String getBotToken() {
        return BOT_TOKEN;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallbackQuery(update);
                return;
            }

            if (!update.hasMessage()) return;

            Message message = update.getMessage();
            Long chatId = message.getChatId();
            String text = message.hasText() ? message.getText() : "";

            // Admin conversation state
            if (adminChatId != null && adminChatId.equals(chatId)) {
                handleAdminConversation(message, chatId);
                return;
            }

            // Commands
            if (text.startsWith("/start")) {
                handleStartCommand(chatId, null);
            } else if (text.startsWith("/list")) {
                handleListCommand(chatId, null);
            } else if (text.startsWith("/get")) {
                handleGetCommand(text, chatId);
            } else if (text.startsWith("/upload") && isAdmin(chatId)) {
                handleUploadCommand(chatId);
            } else if (text.startsWith("/delete") && isAdmin(chatId)) {
                handleDeleteCommand(text, chatId);
            } else if (text.startsWith("/addadmin") && isAdmin(chatId)) {
                handleAddAdminCommand(text, chatId);
            } else if (text.startsWith("/removeadmin") && isAdmin(chatId)) {
                handleRemoveAdminCommand(text, chatId);
            } else {
                sendMessage(chatId, "Noma'lum buyruq yoki ruxsat yo‘q. /start ni sinab ko‘ring.", null);
            }
        } catch (Exception e) {
            LOGGER.severe("Xato: " + e.getMessage());
            if (update.hasMessage()) {
                sendMessage(update.getMessage().getChatId(), "Xatolik yuz berdi. Iltimos, qayta urinib ko‘ring.", null);
            }
        }
    }

    private void handleStartCommand(Long chatId, Integer messageId) {
        InlineKeyboardMarkup markup = createMainMenuMarkup(chatId);
        String text = "Video Botga xush kelibsiz!\nQuyidagi tugmalar orqali harakat qiling:";
        if (messageId == null) {
            sendMessage(chatId, text, markup);
        } else {
            editMessage(chatId, messageId, text, markup);
        }
    }

    private void handleListCommand(Long chatId, Integer messageId) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name, unique_id FROM videos")) {

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString("name");
                String uniqueId = rs.getString("unique_id");
                List<InlineKeyboardButton> row = new ArrayList<>();
                row.add(InlineKeyboardButton.builder()
                        .text(name)
                        .callbackData("get_" + uniqueId)
                        .build());
                rows.add(row);
            }
            // Add "Back" button
            rows.add(List.of(InlineKeyboardButton.builder().text("Ortga").callbackData("back").build()));

            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            markup.setKeyboard(rows);

            String response = rows.isEmpty() ? "Videolar mavjud emas." : "Videolarni tanlang:";
            if (messageId == null) {
                sendMessage(chatId, response, markup);
            } else {
                editMessage(chatId, messageId, response, markup);
            }

        } catch (SQLException e) {
            LOGGER.severe("DB xatosi: " + e.getMessage());
            sendMessage(chatId, "Videolar ro‘yxatini olishda xato.", null);
        }
    }

    private void handleGetCommand(String text, Long chatId) {
        String[] parts = text.split(" ", 2);
        if (parts.length < 2) {
            sendMessage(chatId, "Iltimos, video ID sini kiriting: /get <unique_id>", null);
            return;
        }

        String uniqueId = parts[1];
        sendVideoByUniqueId(chatId, uniqueId);
    }

    private void handleUploadCommand(Long chatId) {
        adminChatId = chatId;
        adminState = NAME_STATE;
        sendMessage(chatId, "Iltimos, video nomini kiriting.", null);
    }

    private void handleDeleteCommand(String text, Long chatId) {
        String[] parts = text.split(" ", 2);
        if (parts.length < 2) {
            sendMessage(chatId, "Iltimos, video ID sini kiriting: /delete <unique_id>", null);
            return;
        }

        String uniqueId = parts[1];
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM videos WHERE unique_id = ?")) {
            stmt.setString(1, uniqueId);
            int rowsAffected = stmt.executeUpdate();

            if (rowsAffected > 0) {
                sendMessage(chatId, "Video (" + uniqueId + ") muvaffaqiyatli o‘chirildi.", null);
            } else {
                sendMessage(chatId, "Video topilmadi: " + uniqueId, null);
            }

        } catch (SQLException e) {
            LOGGER.severe("DB xatosi: " + e.getMessage());
            sendMessage(chatId, "Videoni o‘chirishda xato.", null);
        }
    }

    private void handleAddAdminCommand(String text, Long chatId) {
        String[] parts = text.split(" ", 2);
        if (parts.length < 2) {
            sendMessage(chatId, "Iltimos, user ID kiriting: /addadmin <user_id>", null);
            return;
        }

        try {
            long userId = Long.parseLong(parts[1]);
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement stmt = conn.prepareStatement("INSERT INTO admins (user_id) VALUES (?) ON CONFLICT DO NOTHING")) {
                stmt.setLong(1, userId);
                stmt.executeUpdate();
                sendMessage(chatId, "Foydalanuvchi (" + userId + ") admin sifatida qo‘shildi.", null);
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Noto‘g‘ri user ID formati.", null);
        } catch (SQLException e) {
            LOGGER.severe("DB xatosi: " + e.getMessage());
            sendMessage(chatId, "Admin qo‘shishda xato.", null);
        }
    }

    private void handleRemoveAdminCommand(String text, Long chatId) {
        String[] parts = text.split(" ", 2);
        if (parts.length < 2) {
            sendMessage(chatId, "Iltimos, user ID kiriting: /removeadmin <user_id>", null);
            return;
        }

        try {
            long userId = Long.parseLong(parts[1]);
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM admins WHERE user_id = ?")) {
                stmt.setLong(1, userId);
                int rowsAffected = stmt.executeUpdate();

                if (rowsAffected > 0) {
                    sendMessage(chatId, "Admin (" + userId + ") muvaffaqiyatli o‘chirildi.", null);
                } else {
                    sendMessage(chatId, "Admin topilmadi: " + userId, null);
                }
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Noto‘g‘ri user ID formati.", null);
        } catch (SQLException e) {
            LOGGER.severe("DB xatosi: " + e.getMessage());
            sendMessage(chatId, "Admin o‘chirishda xato.", null);
        }
    }

    private void handleAdminConversation(Message message, Long chatId) throws TelegramApiException {
        if (adminState == NAME_STATE) {
            if (!message.hasText()) {
                sendMessage(chatId, "Iltimos, video nomini kiriting.", null);
                return;
            }
            videoName = message.getText();
            adminState = VIDEO_STATE;
            sendMessage(chatId, "Endi video faylni yuboring.", createBackButtonMarkup());
        } else if (adminState == VIDEO_STATE) {
            if (!message.hasVideo()) {
                sendMessage(chatId, "Iltimos, video fayl yuboring.", createBackButtonMarkup());
                return;
            }
            Video video = message.getVideo();
            String fileId = video.getFileId();
            String uniqueId = UUID.randomUUID().toString();

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO videos (name, file_id, unique_id, view_count) VALUES (?, ?, ?, 0)")) {
                stmt.setString(1, videoName);
                stmt.setString(2, fileId);
                stmt.setString(3, uniqueId);
                stmt.executeUpdate();

                sendMessage(chatId, "Video '" + videoName + "' muvaffaqiyatli yuklandi. ID: " + uniqueId, createMainMenuMarkup(chatId));
                adminChatId = null;
                adminState = 0;
                videoName = null;

            } catch (SQLException e) {
                LOGGER.severe("DB xatosi: " + e.getMessage());
                sendMessage(chatId, "Videoni saqlashda xato.", createBackButtonMarkup());
            }
        }
    }

    private void handleCallbackQuery(Update update) {
        String callbackData = update.getCallbackQuery().getData();
        Long chatId = update.getCallbackQuery().getMessage().getChatId();
        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();

        if (callbackData.equals("back")) {
            handleStartCommand(chatId, messageId);
        } else if (callbackData.equals("list")) {
            handleListCommand(chatId, messageId);
        } else if (callbackData.equals("admin_panel") && isAdmin(chatId)) {
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            rows.add(List.of(InlineKeyboardButton.builder().text("Video yuklash").callbackData("upload").build()));
            rows.add(List.of(InlineKeyboardButton.builder().text("Videolar ro‘yxati").callbackData("list").build()));
            rows.add(List.of(InlineKeyboardButton.builder().text("Ortga").callbackData("back").build()));
            markup.setKeyboard(rows);
            editMessage(chatId, messageId, "Admin paneli:", markup);
        } else if (callbackData.startsWith("get_")) {
            String uniqueId = callbackData.substring(4);
            sendVideoByUniqueId(chatId, uniqueId);
        } else if (callbackData.equals("upload") && isAdmin(chatId)) {
            handleUploadCommand(chatId);
        }
    }

    private void sendVideoByUniqueId(Long chatId, String uniqueId) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            // Retrieve video details and increment view_count
            PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT file_id, name, view_count FROM videos WHERE unique_id = ?");
            selectStmt.setString(1, uniqueId);
            ResultSet rs = selectStmt.executeQuery();

            if (rs.next()) {
                String fileId = rs.getString("file_id");
                String videoName = rs.getString("name");
                int viewCount = rs.getInt("view_count");

                // Increment view_count
                PreparedStatement updateStmt = conn.prepareStatement(
                        "UPDATE videos SET view_count = view_count + 1 WHERE unique_id = ?");
                updateStmt.setString(1, uniqueId);
                updateStmt.executeUpdate();

                // Send video with caption and "Ortga" button
                SendVideo sendVideo = new SendVideo();
                sendVideo.setChatId(chatId.toString());
                sendVideo.setVideo(new InputFile(fileId));
                sendVideo.setCaption(String.format("Video: %s\nKo‘rilgan: %d marta", videoName, viewCount + 1));
                sendVideo.setReplyMarkup(createBackButtonMarkup());
                execute(sendVideo);
            } else {
                sendMessage(chatId, "Video topilmadi: " + uniqueId, createMainMenuMarkup(chatId));
            }
        } catch (SQLException | TelegramApiException e) {
            LOGGER.severe("Video yuborishda xato: " + e.getMessage());
            sendMessage(chatId, "Videoni yuborishda xato.", createMainMenuMarkup(chatId));
        }
    }

    private boolean isAdmin(Long userId) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement("SELECT user_id FROM admins WHERE user_id = ?")) {
            stmt.setLong(1, userId);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            LOGGER.severe("Admin tekshirishda xato: " + e.getMessage());
            return false;
        }
    }

    private InlineKeyboardMarkup createMainMenuMarkup(Long chatId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        row.add(InlineKeyboardButton.builder().text("Videolar ro‘yxati").callbackData("list").build());
        if (isAdmin(chatId)) {
            row.add(InlineKeyboardButton.builder().text("Admin paneli").callbackData("admin_panel").build());
        }
        rows.add(row);
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createBackButtonMarkup() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(InlineKeyboardButton.builder().text("Ortga").callbackData("back").build()));
        markup.setKeyboard(rows);
        return markup;
    }

    private void sendMessage(Long chatId, String text, InlineKeyboardMarkup markup) {
        try {
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText(text);
            if (markup != null) {
                message.setReplyMarkup(markup);
            }
            execute(message);
        } catch (TelegramApiException e) {
            LOGGER.severe("Xabar yuborishda xato: " + e.getMessage());
        }
    }

    private void editMessage(Long chatId, Integer messageId, String text, InlineKeyboardMarkup markup) {
        try {
            EditMessageText editMessage = new EditMessageText();
            editMessage.setChatId(chatId.toString());
            editMessage.setMessageId(messageId);
            editMessage.setText(text);
            if (markup != null) {
                editMessage.setReplyMarkup(markup);
            }
            execute(editMessage);
        } catch (TelegramApiException e) {
            LOGGER.severe("Xabar tahrirlashda xato: " + e.getMessage());
            sendMessage(chatId, text, markup); // Fallback to sending new message
        }
    }

    public static void main(String[] args) {
        try {
            Class.forName("org.postgresql.Driver");
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            VideoBot bot = new VideoBot();
            botsApi.registerBot(bot);
        } catch (ClassNotFoundException e) {
            LOGGER.severe("PostgreSQL JDBC Driver topilmadi: " + e.getMessage());
        } catch (TelegramApiException e) {
            LOGGER.severe("Botni ishga tushirishda xato: " + e.getMessage());
        }
    }
}