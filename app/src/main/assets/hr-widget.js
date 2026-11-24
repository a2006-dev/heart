(function (global) {
  var HR_MIN = 20;
  var HR_MAX = 250;
  var STALE_MS = 10000;
  var TEXT = {
    CONNECTED: '已连接',
    WAITING: '等待数据',
    DISCONNECTED: '连接断开',
    STALE: '数据已过期',
    ABNORMAL: '数值异常',
    IDLE: '待机'
  };

  function isValid(v) {
    return typeof v === 'number' && v >= HR_MIN && v <= HR_MAX;
  }

  function isStale(ts, now) {
    var n = typeof now === 'number' ? now : Date.now();
    return !ts || n - ts > STALE_MS;
  }

  function textOf(connected, hr, ts, now) {
    if (isStale(ts, now)) return connected ? TEXT.STALE : TEXT.DISCONNECTED;
    if (!connected) return TEXT.DISCONNECTED;
    if (!hr || hr <= 0) return TEXT.WAITING;
    if (!isValid(hr)) return TEXT.ABNORMAL;
    return TEXT.CONNECTED;
  }

  var api = {
    HR_MIN: HR_MIN,
    HR_MAX: HR_MAX,
    STALE_MS: STALE_MS,
    TEXT: TEXT,
    isValid: isValid,
    isStale: isStale,
    textOf: textOf
  };

  global.HRWidget = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(this);