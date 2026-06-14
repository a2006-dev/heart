from flask import Flask, request, jsonify
import time

app = Flask(__name__)

# 存储最新心率数据
latest_hr = {"hr": 0, "device": "未连接", "connected": False, "updated_at": 0}

@app.route("/")
def index():
    return "❤️ 心迹 - HF 心率中转服务已运行"

@app.route("/api/hr", methods=["GET", "POST"])
def hr():
    global latest_hr

    if request.method == "POST":
        # 手机端推送心率到此接口
        data = request.get_json(silent=True)
        if data and data.get("hr"):
            hr_val = int(data["hr"])
            if 0 < hr_val < 250:
                latest_hr = {
                    "hr": hr_val,
                    "device": data.get("device", "手机"),
                    "connected": True,
                    "updated_at": time.time()
                }
                return jsonify({"status": "ok"})
        return jsonify({"status": "error", "msg": "invalid data"}), 400

    # GET 请求 - PC 端拉取心率
    # 如果超过 30 秒没有更新，视为离线
    resp = {
        "hr": latest_hr["hr"],
        "device": latest_hr["device"],
        "connected": latest_hr["connected"] and (time.time() - latest_hr["updated_at"] < 30)
    }
    return jsonify(resp)

@app.route("/api/info")
def info():
    return jsonify({
        "status": "running",
        "version": "1.0",
        "updated_at": latest_hr["updated_at"],
        "last_hr": latest_hr["hr"]
    })

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=7860)