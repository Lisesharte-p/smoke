/*
 * Copyright (c) 2020 Nanjing Xiaoxiongpai Intelligent Technology Co., Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * 传感器 TCP 服务器 demo
 * 功能:
 *   1. 查询:客户端发送 "query",服务器返回传感器数据(温度/湿度/光照)
 *   2. 心跳:服务器每隔 5 秒向已连接客户端发送心跳包(附带传感器数据)
 *   3. ping/pong:客户端发送 "ping" 用于连通性测试
 *
 * 协议(纯文本,换行或回车结尾):
 *   客户端 -> 服务器: query  -> 服务器 -> 客户端: DATA TEMP:xx.xx HUMI:xx.xx LUX:xx.xx
 *   客户端 -> 服务器: ping   -> 服务器 -> 客户端: PONG
 *   服务器(每 5s)  -> 客户端: HEARTBEAT TEMP:xx.xx HUMI:xx.xx LUX:xx.xx
 */

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>

#include "cmsis_os2.h"
#include "ohos_init.h"

#include "lwip/sockets.h"
#include "wifi_connect.h"
#include "E53_IA1.h"

#define TASK_STACK_SIZE (1024 * 10)
#define CONFIG_WIFI_SSID "Arch"          // 要连接的 WiFi 热点账号
#define CONFIG_WIFI_PWD "Ljy132333"      // 要连接的 WiFi 热点密码
#define CONFIG_SERVER_PORT 8888          // 服务器监听端口
#define TCP_BACKLOG 10
#define HEARTBEAT_INTERVAL_S 5           // 心跳间隔(秒)
#define RECV_BUF_SIZE 128
#define SEND_BUF_SIZE 256

static int g_client_fd = -1;             // 当前连接的客户端 fd,-1 表示无客户端
static osMutexId_t g_sensor_mutex;       // 传感器读取互斥锁

/* 读取传感器数据(互斥保护,查询任务与心跳任务并发访问 I2C)
 * 注:E53IA1ReadData 在 SHT30 CRC 校验失败时不会写入 Temperature/Humidity
 * 也不返回错误,因此这里清零 + 合理性校验 + 重试,避免发出垃圾数据 */
static int ReadSensorData(E53IA1Data *data)
{
    int ret;

    for (int i = 0; i < 3; i++) {
        memset_s(data, sizeof(*data), 0, sizeof(*data));
        osMutexAcquire(g_sensor_mutex, osWaitForever);
        ret = E53IA1ReadData(data);
        osMutexRelease(g_sensor_mutex);
        if (ret != 0) {
            continue;
        }
        /* 数据合理性校验:温度 -40~85°C,湿度 0~100%RH,光照 0~65535 lx */
        if (data->Temperature < -40.0f || data->Temperature > 85.0f ||
            data->Humidity < 0.0f || data->Humidity > 100.0f ||
            data->Lux < 0.0f || data->Lux > 65535.0f) {
            printf("[sensor] read invalid data, retry %d\r\n", i + 1);
            continue;
        }
        return 0;
    }
    return -1;
}

/* 将传感器数据格式化为 "TEMP:xx.xx HUMI:xx.xx LUX:xx.xx" */
static void FormatSensorData(char *buf, size_t len, E53IA1Data *data)
{
    snprintf_s(buf, len, len - 1, "TEMP:%.2f HUMI:%.2f LUX:%.2f",
               data->Temperature, data->Humidity, data->Lux);
}

/* 心跳任务:定时向已连接客户端发送心跳包 */
static void HeartbeatTask(void)
{
    E53IA1Data data;
    char sensor_str[SEND_BUF_SIZE];
    char heartbeat[SEND_BUF_SIZE];

    while (1) {
        sleep(HEARTBEAT_INTERVAL_S);
        if (g_client_fd < 0) {
            continue;   /* 无客户端连接,跳过本轮 */
        }
        if (ReadSensorData(&data) != 0) {
            printf("[heartbeat] read sensor failed\r\n");
            continue;
        }
        FormatSensorData(sensor_str, sizeof(sensor_str), &data);
        snprintf_s(heartbeat, sizeof(heartbeat), sizeof(heartbeat) - 1,
                   "HEARTBEAT %s", sensor_str);
        if (send(g_client_fd, heartbeat, strlen(heartbeat) + 1, 0) == -1) {
            printf("[heartbeat] send failed, client may be disconnected\r\n");
        } else {
            printf("[heartbeat] %s\r\n", heartbeat);
        }
    }
}

/* 处理单个客户端的查询请求,返回 0 正常退出,返回 -1 断开 */
static int HandleClientRequest(int fd)
{
    char recvbuf[RECV_BUF_SIZE];
    char resp[SEND_BUF_SIZE];
    E53IA1Data data;
    ssize_t ret;

    while (1) {
        memset_s(recvbuf, sizeof(recvbuf), 0, sizeof(recvbuf));
        ret = recv(fd, recvbuf, sizeof(recvbuf) - 1, 0);
        if (ret <= 0) {
            printf("[server] client disconnected\r\n");
            return -1;
        }
        /* 去掉末尾的换行/回车 */
        while (ret > 0 && (recvbuf[ret - 1] == '\n' || recvbuf[ret - 1] == '\r')) {
            recvbuf[ret - 1] = '\0';
            ret--;
        }
        printf("[server] recv: %s\r\n", recvbuf);

        if (strncmp(recvbuf, "query", 5) == 0 || strncmp(recvbuf, "QUERY", 5) == 0) {
            /* 查询:返回传感器数据 */
            if (ReadSensorData(&data) == 0) {
                snprintf_s(resp, sizeof(resp), sizeof(resp) - 1, "DATA ");
                FormatSensorData(resp + 5, sizeof(resp) - 5, &data);
                send(fd, resp, strlen(resp) + 1, 0);
                printf("[server] response: %s\r\n", resp);
            } else {
                const char *err = "ERROR sensor read failed";
                send(fd, err, strlen(err) + 1, 0);
            }
        } else if (strncmp(recvbuf, "ping", 4) == 0 || strncmp(recvbuf, "PING", 4) == 0) {
            /* 连通性测试 */
            send(fd, "PONG", 5, 0);
        } else {
            const char *unknown = "UNKNOWN CMD, try: query / ping";
            send(fd, unknown, strlen(unknown) + 1, 0);
        }
    }
}

static void SensorServerTask(void)
{
    int sock_fd, new_fd;
    struct sockaddr_in server_sock;
    struct sockaddr_in client_sock;
    int sin_size;
    int reuse = 1;
    osThreadAttr_t hb_attr;

    /* 初始化 E53_IA1 传感器(温湿度 SHT30 + 光照 BH1750) */
    if (E53IA1Init() != 0) {
        printf("[server] E53_IA1 init failed\r\n");
        return;
    }
    printf("[server] E53_IA1 sensor init ok\r\n");

    /* 创建传感器读取互斥锁 */
    g_sensor_mutex = osMutexNew(NULL);
    if (g_sensor_mutex == NULL) {
        printf("[server] create mutex failed\r\n");
        return;
    }

    /* 连接 WiFi */
    printf("[server] connecting wifi: %s ...\r\n", CONFIG_WIFI_SSID);
    if (WifiConnect(CONFIG_WIFI_SSID, CONFIG_WIFI_PWD) != 0) {
        printf("[server] wifi connect failed\r\n");
        return;
    }

    /* 创建 socket */
    if ((sock_fd = socket(AF_INET, SOCK_STREAM, 0)) == -1) {
        perror("[server] socket error");
        return;
    }
    /* 允许端口复用,避免重启后 TIME_WAIT 无法 bind */
    setsockopt(sock_fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    bzero(&server_sock, sizeof(server_sock));
    server_sock.sin_family = AF_INET;
    server_sock.sin_addr.s_addr = htonl(INADDR_ANY);
    server_sock.sin_port = htons(CONFIG_SERVER_PORT);

    if (bind(sock_fd, (struct sockaddr *)&server_sock, sizeof(struct sockaddr)) == -1) {
        perror("[server] bind error");
        close(sock_fd);
        return;
    }
    if (listen(sock_fd, TCP_BACKLOG) == -1) {
        perror("[server] listen error");
        close(sock_fd);
        return;
    }
    printf("[server] sensor TCP server listening on port %d\r\n", CONFIG_SERVER_PORT);

    /* 创建心跳任务(常驻,检测到客户端连接后定时发包) */
    hb_attr.name = "HeartbeatTask";
    hb_attr.attr_bits = 0U;
    hb_attr.cb_mem = NULL;
    hb_attr.cb_size = 0U;
    hb_attr.stack_mem = NULL;
    hb_attr.stack_size = TASK_STACK_SIZE;
    hb_attr.priority = osPriorityNormal;
    if (osThreadNew((osThreadFunc_t)HeartbeatTask, NULL, &hb_attr) == NULL) {
        printf("[server] heartbeat task create failed\r\n");
    }

    /* 循环接收客户端连接 */
    while (1) {
        sin_size = sizeof(struct sockaddr_in);
        new_fd = accept(sock_fd, (struct sockaddr *)&client_sock, (socklen_t *)&sin_size);
        if (new_fd == -1) {
            perror("[server] accept error");
            continue;
        }
        printf("[server] client connected: %s\r\n", inet_ntoa(client_sock.sin_addr));

        g_client_fd = new_fd;
        HandleClientRequest(new_fd);
        close(new_fd);
        g_client_fd = -1;
        printf("[server] waiting for next client...\r\n");
    }
}

static void SensorServerDemo(void)
{
    osThreadAttr_t attr;

    attr.name = "SensorServerTask";
    attr.attr_bits = 0U;
    attr.cb_mem = NULL;
    attr.cb_size = 0U;
    attr.stack_mem = NULL;
    attr.stack_size = TASK_STACK_SIZE;
    attr.priority = osPriorityNormal;

    if (osThreadNew((osThreadFunc_t)SensorServerTask, NULL, &attr) == NULL) {
        printf("[SensorServerDemo] Failed to create SensorServerTask!\n");
    }
}

APP_FEATURE_INIT(SensorServerDemo);
