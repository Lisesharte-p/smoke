package server;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 飞书机器人长连接服务。
 *
 * 使用飞书官方 SDK 的长连接模式，不要求本机提供公网 HTTPS 回调地址；
 * 只要运行后端的电脑可以访问公网，就能接收飞书消息事件。
 */
public class FeishuBotService {

    private static final Path LOCAL_CONFIG = Paths.get("config", "feishu.local.properties");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final ExecutorService SEND_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "feishu-sender");
        t.setDaemon(true);
        return t;
    });

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "feishu-scheduler");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean started = false;
    private static volatile Config config;
    private static volatile com.lark.oapi.Client apiClient;
    private static volatile com.lark.oapi.ws.Client wsClient;
    private static volatile String lastChatId = "";

    private FeishuBotService() {}

    public static synchronized void start() {
        if (started) return;
        Config loaded = loadConfig();
        config = loaded;

        if (!loaded.enabled) {
            System.out.println("[FeishuBot] 未启用，跳过启动。");
            return;
        }
        if (isBlank(loaded.appId) || isBlank(loaded.appSecret)) {
            System.out.println("[FeishuBot] 未配置 FEISHU_APP_ID / FEISHU_APP_SECRET，跳过启动。");
            return;
        }

        started = true;
        apiClient = com.lark.oapi.Client.newBuilder(loaded.appId, loaded.appSecret)
                .requestTimeout(20, TimeUnit.SECONDS)
                .build();

        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        handleMessage(event);
                    }
                })
                .build();

        Thread wsThread = new Thread(() -> {
            try {
                wsClient = new com.lark.oapi.ws.Client.Builder(loaded.appId, loaded.appSecret)
                        .eventHandler(dispatcher)
                        .build();
                wsClient.start();
                System.out.println("[FeishuBot] 长连接客户端已启动。");
            } catch (Throwable t) {
                started = false;
                System.out.println("[FeishuBot] 长连接启动失败: " + t.getMessage());
                t.printStackTrace(System.out);
            }
        }, "feishu-ws-client");
        wsThread.setDaemon(true);
        wsThread.start();

        scheduleDailySummaryIfNeeded();
    }

    public static void pushAlarmAsync(String plotId, String alarmType, String value, String level) {
        if (!started || apiClient == null) return;
        Config cfg = config;
        String chatId = firstNonBlank(cfg.alertChatId, lastChatId);
        if (isBlank(chatId)) {
            System.out.println("[FeishuBot] 告警已生成，但尚未配置 FEISHU_ALERT_CHAT_ID，也没有最近会话可推送。");
            return;
        }
        String text = FeishuCommandRouter.alarmNotification(plotId, alarmType, value, level);
        SEND_POOL.execute(() -> sendToChat(chatId, text));
    }

    private static void handleMessage(P2MessageReceiveV1 event) {
        try {
            P2MessageReceiveV1Data data = event == null ? null : event.getEvent();
            EventMessage message = data == null ? null : data.getMessage();
            if (message == null) return;

            if (config.rememberLastChat && !isBlank(message.getChatId())) {
                lastChatId = message.getChatId();
            }
            if (!"text".equals(message.getMessageType())) {
                reply(message.getMessageId(), "我目前先支持文本命令。\n" + FeishuCommandRouter.route("帮助"));
                return;
            }

            String text = extractText(message.getContent());
            System.out.println("[FeishuBot] 收到消息: " + text);
            String answer = FeishuCommandRouter.route(text);
            reply(message.getMessageId(), answer);
        } catch (Throwable t) {
            System.out.println("[FeishuBot] 处理消息失败: " + t.getMessage());
            t.printStackTrace(System.out);
        }
    }

    private static void reply(String messageId, String text) {
        if (apiClient == null || isBlank(messageId)) return;
        SEND_POOL.execute(() -> {
            try {
                ReplyMessageReq req = ReplyMessageReq.newBuilder()
                        .messageId(messageId)
                        .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                                .msgType("text")
                                .content(textContent(text))
                                .uuid(UUID.randomUUID().toString())
                                .build())
                        .build();
                ReplyMessageResp resp = apiClient.im().message().reply(req);
                if (!resp.success()) {
                    System.out.println("[FeishuBot] 回复失败 code=" + resp.getCode() + ", msg=" + resp.getMsg());
                }
            } catch (Throwable t) {
                System.out.println("[FeishuBot] 回复异常: " + t.getMessage());
                t.printStackTrace(System.out);
            }
        });
    }

    private static void sendToChat(String chatId, String text) {
        if (apiClient == null || isBlank(chatId)) return;
        try {
            CreateMessageReq req = CreateMessageReq.newBuilder()
                    .receiveIdType("chat_id")
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(chatId)
                            .msgType("text")
                            .content(textContent(text))
                            .uuid(UUID.randomUUID().toString())
                            .build())
                    .build();
            apiClient.im().message().create(req);
        } catch (Throwable t) {
            System.out.println("[FeishuBot] 主动发送消息失败: " + t.getMessage());
            t.printStackTrace(System.out);
        }
    }

    private static String extractText(String content) {
        String text = Json.strValue(content, "text");
        return text == null ? "" : text.trim();
    }

    private static String textContent(String text) {
        return "{\"text\":" + Json.str(text) + "}";
    }

    private static void scheduleDailySummaryIfNeeded() {
        Config cfg = config;
        if (!cfg.dailySummaryEnabled) return;
        scheduleNextDaily(cfg.dailyTime);
    }

    private static void scheduleNextDaily(LocalTime time) {
        long delayMs = millisUntilNext(time);
        SCHEDULER.schedule(() -> {
            try {
                String chatId = firstNonBlank(config.dailyChatId, firstNonBlank(config.alertChatId, lastChatId));
                if (isBlank(chatId)) {
                    System.out.println("[FeishuBot] 到达每日总结时间，但尚未配置会话，也没有最近会话可推送。");
                } else {
                    sendToChat(chatId, FeishuCommandRouter.dailySummary());
                }
            } finally {
                scheduleNextDaily(time);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        System.out.println("[FeishuBot] 每日总结已计划在 " + time + " 推送。");
    }

    private static long millisUntilNext(LocalTime time) {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        ZonedDateTime next = now.withHour(time.getHour()).withMinute(time.getMinute()).withSecond(0).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }

    private static Config loadConfig() {
        Properties props = new Properties();
        if (Files.isRegularFile(LOCAL_CONFIG)) {
            try (InputStream in = Files.newInputStream(LOCAL_CONFIG)) {
                props.load(in);
            } catch (IOException e) {
                System.out.println("[FeishuBot] 读取本地配置失败: " + e.getMessage());
            }
        }

        Config cfg = new Config();
        cfg.appId = read("FEISHU_APP_ID", props);
        cfg.appSecret = read("FEISHU_APP_SECRET", props);
        cfg.enabled = bool(read("FEISHU_ENABLED", props), !isBlank(cfg.appId) && !isBlank(cfg.appSecret));
        cfg.alertChatId = read("FEISHU_ALERT_CHAT_ID", props);
        cfg.rememberLastChat = bool(read("FEISHU_REMEMBER_LAST_CHAT", props), true);
        cfg.dailySummaryEnabled = bool(read("FEISHU_DAILY_SUMMARY_ENABLED", props), false);
        cfg.dailyChatId = read("FEISHU_DAILY_CHAT_ID", props);
        cfg.dailyTime = parseTime(read("FEISHU_DAILY_TIME", props), LocalTime.of(17, 0));
        return cfg;
    }

    private static String read(String key, Properties props) {
        String env = System.getenv(key);
        if (!isBlank(env)) return env.trim();
        String prop = props.getProperty(key);
        return prop == null ? "" : prop.trim();
    }

    private static boolean bool(String value, boolean def) {
        if (isBlank(value)) return def;
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value) || "启用".equals(value);
    }

    private static LocalTime parseTime(String value, LocalTime def) {
        if (isBlank(value)) return def;
        try {
            return LocalTime.parse(value);
        } catch (Exception e) {
            return def;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return isBlank(a) ? (isBlank(b) ? "" : b) : a;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class Config {
        boolean enabled;
        String appId;
        String appSecret;
        String alertChatId;
        boolean rememberLastChat;
        boolean dailySummaryEnabled;
        String dailyChatId;
        LocalTime dailyTime;
    }
}
