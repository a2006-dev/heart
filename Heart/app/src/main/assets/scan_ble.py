# -*- coding: utf-8 -*-
"""
心迹 - BLE 心率设备扫描工具
扫描周围支持标准心率服务（0x180D）的蓝牙设备
"""
import asyncio
from bleak import BleakScanner

async def scan():
    print("🔍 正在扫描 BLE 设备（10秒）...")
    print("=" * 60)
    
    devices = await BleakScanner.discover(timeout=10, return_adv=True)
    
    print(f"\n📱 找到 {len(devices)} 个设备:\n")
    
    for addr, (device, adv_data) in devices.items():
        name = device.name or "未命名"
        rssi = adv_data.rssi if adv_data else "?"
        
        # 检查是否有标准心率服务 UUID
        has_heart = False
        if adv_data and adv_data.service_uuids:
            has_heart = "0000180d" in [u.lower().replace("-", "") for u in adv_data.service_uuids]
        
        heart_icon = "❤️ " if has_heart else "   "
        print(f"{heart_icon}[{addr}] {name}  (信号强度: {rssi})")
        
        if adv_data and adv_data.service_uuids:
            print(f"     服务UUID: {adv_data.service_uuids}")
        if adv_data and adv_data.manufacturer_data:
            print(f"     厂商数据: {dict(adv_data.manufacturer_data)}")
        print()
    
    print("=" * 60)
    print("❤️ = 支持标准心率服务的设备")
    print("💡 请确保手表已开启「心率广播」模式（一般在蓝牙设置里）")
    print("💡 如果没有找到设备，试试靠近电脑或延长扫描时间")

asyncio.run(scan())
