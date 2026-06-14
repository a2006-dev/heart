// 简易 MQTT 客户端（仅 QoS 0，仅 CONNECT/SUBSCRIBE/PUBLISH）
// 纯 Go 标准库实现，无外部依赖
package main

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"
)

// MQTT 固定包头类型
const (
	_CONNECT     = 0x10
	_CONNACK     = 0x20
	_PUBLISH     = 0x30
	_PUBACK      = 0x40
	_SUBSCRIBE   = 0x82
	_SUBACK      = 0x90
	_PINGREQ     = 0xC0
	_PINGRESP    = 0xD0
	_DISCONNECT  = 0xE0
)

type MQTTClient struct {
	conn     net.Conn
	mu       sync.Mutex
	broker   string
	clientID string
	username string
	password string
	topics   []string
	onMsg    func(topic string, payload []byte)
	running  bool
	done     chan struct{}
}

func NewMQTTClient(broker, clientID string) *MQTTClient {
	return &MQTTClient{
		broker:   broker,
		clientID: clientID,
		done:     make(chan struct{}),
	}
}

func (m *MQTTClient) SetCredentials(user, pass string) {
	m.username = user
	m.password = pass
}

func (m *MQTTClient) SetCallback(cb func(topic string, payload []byte)) {
	m.onMsg = cb
}
func (m *MQTTClient) Connect() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	addLog(fmt.Sprintf("正在连接 %s ...", m.broker))
	conn, err := net.DialTimeout("tcp", m.broker, 10*time.Second)
	if err != nil {
		return fmt.Errorf("连接失败: %v", err)
	}
	m.conn = conn
	addLog("TCP 连接成功")

	// 发送 CONNECT 报文前短暂等待，确保 TCP 握手完成
	time.Sleep(50 * time.Millisecond)

	// 发送 CONNECT 报文
	// 格式：协议名长度(2) + "MQTT" + 协议级别(1) + 连接标志(1) + keepalive(2) + clientId长度(2) + clientId
	var payload []byte
	payload = append(payload, 0x00, 0x04)                 // 协议名长度
	payload = append(payload, []byte("MQTT")...)          // 协议名
	payload = append(payload, 0x04)                       // 协议级别 (MQTT 3.1.1)
	payload = append(payload, 0x02)                       // 连接标志 (clean session)
	payload = append(payload, 0x00, 0x3C)                 // keepalive 60秒

	// ClientID
	idBytes := []byte(m.clientID)
	payload = append(payload, byte(len(idBytes)>>8), byte(len(idBytes)))
	payload = append(payload, idBytes...)

	// 如有用户名密码
	if m.username != "" {
		userBytes := []byte(m.username)
		passBytes := []byte(m.password)
		// 修改标志位
		payload[7] = 0xC0 | 0x02 // username + password + clean
		payload = append(payload, byte(len(userBytes)>>8), byte(len(userBytes)))
		payload = append(payload, userBytes...)
		payload = append(payload, byte(len(passBytes)>>8), byte(len(passBytes)))
		payload = append(payload, passBytes...)
	}

	// 写入包头
	header := []byte{_CONNECT}
	header = append(header, encodeRemainingLen(len(payload))...)
	packet := append(header, payload...)

	if _, err := m.conn.Write(packet); err != nil {
		m.conn.Close()
		return fmt.Errorf("发送 CONNECT 失败: %v", err)
	}

	// 读取 CONNACK
	resp := make([]byte, 4)
	n, err := io.ReadFull(m.conn, resp)
	if err != nil {
		m.conn.Close()
		return fmt.Errorf("读取 CONNACK 失败 (%d字节): %v", n, err)
	}
	if resp[0] != _CONNACK {
		m.conn.Close()
		return fmt.Errorf("CONNACK 包头错误: 期望 0x20, 收到 0x%02x", resp[0])
	}
	if resp[3] != 0x00 {
		m.conn.Close()
		return fmt.Errorf("连接被拒, 返回码: %d", resp[3])
	}
	addLog("MQTT 已连接成功")

	m.running = true

	// 启动后台读协程
	go m.readLoop()
	go m.keepAlive()

	return nil
}

func (m *MQTTClient) Subscribe(topic string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.conn == nil {
		return fmt.Errorf("未连接")
	}

	packetID := uint16(1)
	var payload []byte
	payload = append(payload, byte(packetID>>8), byte(packetID))

	topicBytes := []byte(topic)
	payload = append(payload, byte(len(topicBytes)>>8), byte(len(topicBytes)))
	payload = append(payload, topicBytes...)
	payload = append(payload, 0x00) // QoS 0

	header := []byte{_SUBSCRIBE}
	header = append(header, encodeRemainingLen(len(payload))...)
	packet := append(header, payload...)

	if _, err := m.conn.Write(packet); err != nil {
		return fmt.Errorf("发送 SUBSCRIBE 失败: %v", err)
	}

	m.topics = append(m.topics, topic)
	return nil
}

func (m *MQTTClient) Publish(topic string, payload []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.conn == nil {
		return fmt.Errorf("未连接")
	}

	topicBytes := []byte(topic)
	var data []byte
	data = append(data, byte(len(topicBytes)>>8), byte(len(topicBytes)))
	data = append(data, topicBytes...)
	data = append(data, payload...)

	header := []byte{_PUBLISH | 0x00} // QoS 0, 不保留
	header = append(header, encodeRemainingLen(len(data))...)
	packet := append(header, data...)

	_, err := m.conn.Write(packet)
	return err
}

func (m *MQTTClient) Disconnect() {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.running {
		m.running = false
		close(m.done)
	}
	if m.conn != nil {
		m.conn.Write([]byte{_DISCONNECT, 0x00})
		m.conn.Close()
		m.conn = nil
	}
}

func (m *MQTTClient) readLoop() {
	defer func() {
		m.mu.Lock()
		m.running = false
		if m.conn != nil {
			m.conn.Close()
			m.conn = nil
		}
		m.mu.Unlock()
	}()

	buf := make([]byte, 4096)
	leftover := make([]byte, 0, 4096) // 缓存跨包数据
	for {
		select {
		case <-m.done:
			return
		default:
		}

		m.conn.SetReadDeadline(time.Now().Add(30 * time.Second))
		n, err := m.conn.Read(buf)
		if err != nil {
			return
		}

		// 新分配 buffer，避免 slice 底层数组复用问题
		data := make([]byte, len(leftover)+n)
		copy(data, leftover)
		copy(data[len(leftover):], buf[:n])
		leftover = leftover[:0]
		pos := 0
		dataLen := len(data)

		for pos < dataLen {
			if pos >= dataLen {
				break
			}
			packetType := data[pos]
			pos++
			if pos >= dataLen {
				break
			}

			// 解析剩余长度
			remaining, bytesUsed := decodeRemainingLen(data[pos:])
			pos += bytesUsed
			if bytesUsed == 0 || pos+remaining > dataLen {
				// 数据不完整，保留到下次
				pos -= (1 + bytesUsed) // 回退到包头
				leftover = append(leftover[:0], data[pos:]...)
				break
			}

			switch packetType & 0xF0 {
			case _PUBLISH:
				// 解析 topic
				if remaining < 2 {
					pos += remaining
					break
				}
				topicLen := int(binary.BigEndian.Uint16(data[pos : pos+2]))
				pos += 2
				if topicLen > remaining-2 {
					pos += remaining - 2
					break
				}
				topic := string(data[pos : pos+topicLen])
				pos += topicLen

				payload := make([]byte, remaining-(topicLen+2))
				copy(payload, data[pos:pos+len(payload)])
				pos += len(payload)

				if m.onMsg != nil {
					m.onMsg(topic, payload)
				}

			case _PINGRESP:
				pos += remaining

			case _SUBACK:
				pos += remaining

			default:
				pos += remaining
			}
		}
	}
}

func (m *MQTTClient) keepAlive() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-m.done:
			return
		case <-ticker.C:
			m.mu.Lock()
			if m.conn != nil {
				m.conn.Write([]byte{_PINGREQ, 0x00})
			}
			m.mu.Unlock()
		}
	}
}

// 编码 MQTT 剩余长度
func encodeRemainingLen(length int) []byte {
	var buf []byte
	for {
		digit := byte(length % 128)
		length /= 128
		if length > 0 {
			digit |= 0x80
		}
		buf = append(buf, digit)
		if length == 0 {
			break
		}
	}
	return buf
}

// 解码 MQTT 剩余长度
func decodeRemainingLen(data []byte) (int, int) {
	value := 0
	multiplier := 1
	bytesUsed := 0
	for i := 0; i < len(data); i++ {
		bytesUsed++
		digit := int(data[i] & 0x7F)
		value += digit * multiplier
		multiplier *= 128
		if data[i]&0x80 == 0 {
			break
		}
	}
	return value, bytesUsed
}

// ─── 心迹 MQTT 工具函数 ───

type HRData struct {
	HR        int    `json:"hr"`
	Device    string `json:"device"`
	Connected bool   `json:"connected"`
}

var mqttClient *MQTTClient

func startMQTT(broker, topic string) error {
	if mqttClient != nil {
		mqttClient.Disconnect()
	}

	client := NewMQTTClient(broker, fmt.Sprintf("heart_pc_%d", time.Now().UnixNano()))
	// 提取前缀用于通配符匹配
	topicPrefix := topic
	if idx := strings.LastIndex(topicPrefix, "/"); idx >= 0 {
		topicPrefix = topicPrefix[:idx] + "/"
	}
	client.SetCallback(func(t string, payload []byte) {
		// 精确匹配 或 前缀匹配（兼容通配符订阅）
		if t != topic && !strings.HasPrefix(t, topicPrefix) {
			return
		}
		var data HRData
		if err := json.Unmarshal(payload, &data); err != nil {
			return
		}
		if data.HR > 0 && data.HR < 250 {
			mu.Lock()
			currentHR = data.HR
			deviceName = data.Device + " (MQTT)"
			connected = data.Connected
			mu.Unlock()
			updateUIHR()
			// MQTT 数据强制刷新一次 UI（绕过频率限制）
			forceUpdateHR()
		}
	})

	if err := client.Connect(); err != nil {
		return err
	}
	// 订阅具体 topic
	if err := client.Subscribe(topic); err != nil {
		client.Disconnect()
		return err
	}
	// 也订阅通配符 +，兼容设备 tag 变更的情况
	wildTopic := topic
	if idx := strings.LastIndex(wildTopic, "/"); idx >= 0 {
		wildTopic = wildTopic[:idx] + "/+"
	}
	if wildTopic != topic {
		_ = client.Subscribe(wildTopic)
	}

	mqttClient = client
	return nil
}

func stopMQTT() {
	if mqttClient != nil {
		mqttClient.Disconnect()
		mqttClient = nil
	}
	// 切回本地模式提示
	mu.Lock()
	deviceName = strings.Replace(deviceName, " (MQTT)", "", -1)
	mu.Unlock()
	addLog("MQTT 已断开，切回本地模式")
}

func init() {
	// 确保 binary 包被使用
	_ = binary.MaxVarintLen16
}