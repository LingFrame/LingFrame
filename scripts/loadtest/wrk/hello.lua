-- 灵珑（LingFrame）压测脚本：GET /lingcore/hello
-- 用法：
--   wrk -t8 -c100 -d30s --latency -s scripts/loadtest/wrk/hello.lua http://localhost:8888
--
-- 说明：本脚本走 Web 治理链入口（GOVERN_ONLY），用于量化进程入口吞吐；
-- 灵元服务调用的完整 NORMAL 治理链基线由 lingframe-benchmark 的 JMH 基准覆盖。

-- 请求路径（默认；可用环境变量覆盖）
local path = os.getenv("LINGFRAME_LOADTEST_PATH") or "/lingcore/hello"

request = function()
    return wrk.format("GET", path)
end

-- 每请求后回调（空实现，保持 wrk 默认统计）
response = function(status, headers, body)
end
