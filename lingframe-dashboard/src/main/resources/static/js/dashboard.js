const { createApp, ref, reactive, computed, watch, onMounted, onUnmounted, nextTick } = Vue;

// API 配置
const API_BASE = '/lingframe/dashboard';

createApp({
    setup() {
        // ==================== 状态 ====================
        const plugins = ref([]);
        const activeId = ref(null);
        const canaryPct = ref(0);
        const isAuto = ref(false);
        const ipcEnabled = ref(true);
        const ipcTarget = ref('user-plugin');
        const logs = ref([]);
        const lastAudit = ref(null);
        const logViewMode = ref('current');
        const logContainer = ref(null);
        const isUserScrolling = ref(false);
        const sidebarOpen = ref(false);
        const currentEnv = ref('dev');
        const currentTime = ref('');
        const sseStatus = ref('disconnected');
        const toasts = ref([]);

        const stats = reactive({ total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 });

        const loading = reactive({
            plugins: false,
            status: false,
            canary: false,
            permissions: false,
            stats: false,
            simulate: false
        });

        const modal = reactive({
            show: false,
            title: '',
            message: '',
            actionText: '',
            loading: false,
            onConfirm: null
        });

        const envLabels = { dev: '开发', test: '测试', prod: '生产' };

        let eventSource = null;
        let timeTimer = null;
        let stressTimer = null;
        let logIdCounter = 0;
        let toastIdCounter = 0;

        // ==================== 计算属性 ====================
        const activePlugin = computed(() => plugins.value.find(p => p.pluginId === activeId.value));
        const canCanary = computed(() => activePlugin.value?.versions?.length >= 2);
        const canOperate = computed(() => activePlugin.value?.status === 'ACTIVE');
        const sseStatusText = computed(() => ({
            connected: 'SSE 已连接',
            connecting: 'SSE 连接中...',
            disconnected: 'SSE 断开'
        }[sseStatus.value]));

        const displayLogs = computed(() => {
            if (logViewMode.value === 'current' && activeId.value) {
                return logs.value.filter(l => l.pluginId === activeId.value);
            }
            return logs.value;
        });

        // ==================== Toast 通知 ====================
        const showToast = (message, type = 'info') => {
            const id = ++toastIdCounter;
            toasts.value.push({ id, message, type });
            setTimeout(() => {
                toasts.value = toasts.value.filter(t => t.id !== id);
            }, 3000);
        };

        // ==================== API 调用 ====================
        const api = {
            async get(path) {
                const res = await fetch(API_BASE + path);
                const data = await res.json();
                if (!data.success) throw new Error(data.message);
                return data.data;
            },
            async post(path, body = {}) {
                const res = await fetch(API_BASE + path, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const data = await res.json();
                if (!data.success) throw new Error(data.message);
                return data.data;
            }
        };

        // ==================== 插件操作 ====================
        const refreshPlugins = async () => {
            loading.plugins = true;
            try {
                plugins.value = await api.get('/plugins');
            } catch (e) {
                showToast('获取插件列表失败: ' + e.message, 'error');
            } finally {
                loading.plugins = false;
            }
        };

        const selectPlugin = async (pluginId) => {
            if (isAuto.value) {
                toggleAuto(); // 停止压测
            }
            activeId.value = pluginId;
            const plugin = plugins.value.find(p => p.pluginId === pluginId);
            if (plugin) {
                canaryPct.value = plugin.canaryPercent || 0;
            }
            // 重置统计
            Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 });
            lastAudit.value = null;

            // 设置 IPC 目标为其他插件
            const otherPlugin = plugins.value.find(p => p.pluginId !== pluginId && p.status === 'ACTIVE');
            if (otherPlugin) {
                ipcTarget.value = otherPlugin.pluginId;
            }
        };

        const updateStatus = async (newStatus) => {
            if (!activeId.value) return;
            loading.status = true;
            try {
                const updated = await api.post(`/plugins/${activeId.value}/status`, { status: newStatus });
                const idx = plugins.value.findIndex(p => p.pluginId === activeId.value);
                if (idx !== -1 && updated) {
                    plugins.value[idx] = updated;
                }
                showToast(`状态已更新为 ${newStatus}`, 'success');
            } catch (e) {
                showToast('状态更新失败: ' + e.message, 'error');
            } finally {
                loading.status = false;
            }
        };

        const requestUnload = () => {
            if (!activePlugin.value) return;
            modal.title = '确认卸载插件';
            modal.message = `即将卸载 "${activeId.value}"，这将中断所有请求。`;
            modal.actionText = '卸载';
            modal.onConfirm = async () => {
                modal.loading = true;
                try {
                    await api.post(`/plugins/uninstall/${activeId.value}`);
                    plugins.value = plugins.value.filter(p => p.pluginId !== activeId.value);
                    activeId.value = null;
                    showToast('插件已卸载', 'success');
                } catch (e) {
                    showToast('卸载失败: ' + e.message, 'error');
                } finally {
                    modal.loading = false;
                    modal.show = false;
                }
            };
            modal.show = true;
        };

        const confirmModalAction = () => {
            if (modal.onConfirm) modal.onConfirm();
        };

        // ==================== 灰度配置 ====================
        const updateCanaryConfig = async () => {
            if (!activeId.value || !canCanary.value) return;
            loading.canary = true;
            try {
                await api.post(`/plugins/${activeId.value}/canary`, {
                    percent: canaryPct.value,
                    canaryVersion: activePlugin.value?.canaryVersion
                });
                showToast(`灰度比例已设置为 ${canaryPct.value}%`, 'success');
            } catch (e) {
                showToast('灰度配置失败: ' + e.message, 'error');
            } finally {
                loading.canary = false;
            }
        };

        // ==================== 权限操作 ====================
        const togglePerm = async (perm) => {
            if (!activePlugin.value) return;

            console.log('========== togglePerm ==========');
            console.log('点击的权限:', perm);
            console.log('当前插件:', activePlugin.value.pluginId);
            console.log('当前权限状态:', JSON.stringify(activePlugin.value.permissions));

            const currentPerms = activePlugin.value.permissions || {};
            const currentValue = currentPerms[perm] !== false;
            const newValue = !currentValue;

            console.log(`${perm}: currentValue=${currentValue}, newValue=${newValue}`);

            // 构建新的权限状态
            const newPerms = {
                dbRead: currentPerms.dbRead !== false,
                dbWrite: currentPerms.dbWrite !== false,
                cacheRead: currentPerms.cacheRead !== false,
                cacheWrite: currentPerms.cacheWrite !== false,
                [perm]: newValue
            };

            console.log('初始 newPerms:', JSON.stringify(newPerms));

            // 权限级联逻辑：WRITE 包含 READ
            // 1. 如果开启"写"权限，自动开启"读"权限
            if (perm === 'dbWrite' && newValue) {
                newPerms.dbRead = true;
                console.log('级联: dbWrite 开启 -> dbRead 也开启');
            }
            if (perm === 'cacheWrite' && newValue) {
                newPerms.cacheRead = true;
                console.log('级联: cacheWrite 开启 -> cacheRead 也开启');
            }

            // 2. 如果关闭"读"权限，自动关闭"写"权限
            if (perm === 'dbRead' && !newValue) {
                newPerms.dbWrite = false;
                console.log('级联: dbRead 关闭 -> dbWrite 也关闭');
            }
            if (perm === 'cacheRead' && !newValue) {
                newPerms.cacheWrite = false;
                console.log('级联: cacheRead 关闭 -> cacheWrite 也关闭');
            }

            console.log('最终 newPerms:', JSON.stringify(newPerms));
            console.log('发送到后端的 API: /governance/' + activeId.value + '/permissions');

            loading.permissions = true;
            try {
                await api.post(`/governance/${activeId.value}/permissions`, newPerms);
                const idx = plugins.value.findIndex(p => p.pluginId === activeId.value);
                if (idx !== -1) {
                    plugins.value[idx].permissions = newPerms;
                }

                // 改进提示信息，说明级联效果
                let message = `${perm} ${newValue ? '已开启' : '已关闭'}`;
                if (perm === 'dbWrite' && newValue && !currentPerms.dbRead) {
                    message += '（同时开启 dbRead）';
                } else if (perm === 'cacheWrite' && newValue && !currentPerms.cacheRead) {
                    message += '（同时开启 cacheRead）';
                } else if (perm === 'dbRead' && !newValue && currentPerms.dbWrite) {
                    message += '（同时关闭 dbWrite）';
                } else if (perm === 'cacheRead' && !newValue && currentPerms.cacheWrite) {
                    message += '（同时关闭 cacheWrite）';
                }

                showToast(message, 'success');
                console.log('权限更新成功');
            } catch (e) {
                showToast('权限更新失败: ' + e.message, 'error');
                console.error('权限更新失败:', e);
            } finally {
                loading.permissions = false;
            }
        };

        // ==================== 功能演练 ====================
        const simulate = async (resourceType) => {
            if (!canOperate.value) {
                showToast('插件未激活', 'error');
                return;
            }

            loading.simulate = true;
            try {
                const result = await api.post(`/simulate/plugins/${activeId.value}/resource`, {
                    resourceType
                });
                lastAudit.value = result;

                if (result.allowed) {
                    showToast(`${resourceType} 访问成功`, 'success');
                } else {
                    showToast(result.message, 'error');
                }
            } catch (e) {
                showToast('模拟失败: ' + e.message, 'error');
            } finally {
                loading.simulate = false;
            }
        };

        const simulateIPC = async () => {
            if (!canOperate.value) {
                showToast('源插件未激活', 'error');
                return;
            }

            loading.simulate = true;
            try {
                const result = await api.post(`/simulate/plugins/${activeId.value}/ipc`, {
                    targetPluginId: ipcTarget.value,
                    ipcEnabled: ipcEnabled.value
                });
                lastAudit.value = result;

                if (result.allowed) {
                    showToast('IPC 调用成功', 'success');
                } else {
                    showToast(result.message, 'error');
                }
            } catch (e) {
                showToast('IPC 模拟失败: ' + e.message, 'error');
            } finally {
                loading.simulate = false;
            }
        };

        // ==================== 压测模式 ====================
        const toggleAuto = () => {
            if (!canOperate.value) {
                showToast('插件未激活', 'error');
                return;
            }

            isAuto.value = !isAuto.value;

            if (isAuto.value) {
                // 开始压测
                stressTimer = setInterval(async () => {
                    try {
                        const result = await api.post(`/simulate/plugins/${activeId.value}/stress`);
                        // 更新统计
                        stats.total += result.totalRequests;
                        stats.v1 += result.v1Requests;
                        stats.v2 += result.v2Requests;
                        stats.v1Pct = stats.total > 0 ? ((stats.v1 / stats.total) * 100).toFixed(1) : 0;
                        stats.v2Pct = stats.total > 0 ? ((stats.v2 / stats.total) * 100).toFixed(1) : 0;
                    } catch (e) {
                        console.error('Stress test error', e);
                    }
                }, 1000);
            } else {
                // 停止压测
                if (stressTimer) {
                    clearInterval(stressTimer);
                    stressTimer = null;
                }
            }
        };

        const resetStats = () => {
            Object.assign(stats, { total: 0, v1: 0, v2: 0, v1Pct: 0, v2Pct: 0 });
            lastAudit.value = null;
        };

        // ==================== SSE 日志流 ====================
        const connectSSE = () => {
            if (eventSource) {
                eventSource.close();
            }

            sseStatus.value = 'connecting';
            eventSource = new EventSource(API_BASE + '/stream');

            eventSource.onopen = () => {
                sseStatus.value = 'connected';
                console.log(new Date(), 'SSE connected');  // 🔥 调试
            };

            // 🔥 添加通用消息监听器
            eventSource.onmessage = (e) => {
                console.log('SSE onmessage:', e);
            };

            eventSource.addEventListener('log-event', (e) => {
                console.log('SSE log-event received:', e.data);  // 🔥 调试
                try {
                    const data = JSON.parse(e.data);
                    console.log('Parsed data:', data);  // 🔥 调试
                    addLog(data);
                } catch (err) {
                    console.warn('Failed to parse log event', err);
                }
            });

            eventSource.addEventListener('ping', () => {
                // 心跳
                console.log('SSE ping received');  // 🔥 调试
            });

            eventSource.onerror = () => {
                sseStatus.value = 'disconnected';
                console.log('SSE disconnected, reconnecting...');
                setTimeout(connectSSE, 3000);
            };
        };

        const addLog = (data) => {
            const log = {
                id: ++logIdCounter,
                traceId: data.traceId,
                pluginId: data.pluginId,
                content: data.content,
                type: data.type,
                tag: data.tag,
                depth: data.depth || 0,
                timestamp: data.timestamp
            };

            logs.value.unshift(log);
            if (logs.value.length > 1000) {
                logs.value.pop();
            }

            // 自动滚动
            if (!isUserScrolling.value && logContainer.value) {
                nextTick(() => {
                    logContainer.value.scrollTop = 0;
                });
            }
        };

        const clearLogs = () => {
            if (logViewMode.value === 'current' && activeId.value) {
                logs.value = logs.value.filter(l => l.pluginId !== activeId.value);
            } else {
                logs.value = [];
            }
        };

        // ==================== 辅助函数 ====================
        const handleLogScroll = () => {
            if (logContainer.value) {
                isUserScrolling.value = logContainer.value.scrollTop > 50;
            }
        };

        const scrollToTop = () => {
            if (logContainer.value) {
                logContainer.value.scrollTo({ top: 0, behavior: 'smooth' });
                isUserScrolling.value = false;
            }
        };

        const updateTime = () => {
            currentTime.value = new Date().toLocaleTimeString('zh-CN', { hour12: false });
        };

        const formatDrift = (val) => {
            const v = val || 0;
            return (v >= 0 ? '+' : '') + v.toFixed(1) + '%';
        };

        const formatTime = (ts) => {
            if (!ts) return '--:--:--';
            const d = new Date(ts);
            return d.toLocaleTimeString('zh-CN', { hour12: false });
        };

        const getStatusClass = (status) => ({
            'ACTIVE': 'status-active',
            'LOADED': 'status-loaded',
            'UNLOADED': 'status-unloaded',
            'LOADING': 'status-loading',
            'STARTING': 'status-loading',
            'ERROR': 'status-error'
        }[status] || 'status-unloaded');

        const getPluginShortName = (pid) => {
            if (!pid) return '---';
            const parts = pid.split('-');
            return parts[0]?.toUpperCase() || pid.toUpperCase();
        };

        const getPluginTagClass = (pid) => {
            const colors = [
                'bg-blue-500/20 text-blue-400',
                'bg-amber-500/20 text-amber-400',
                'bg-green-500/20 text-green-400',
                'bg-purple-500/20 text-purple-400',
                'bg-pink-500/20 text-pink-400'
            ];
            const idx = plugins.value.findIndex(p => p.pluginId === pid);
            return colors[idx % colors.length] || colors[0];
        };

        const getLogColor = (log) => {
            if (log.tag === 'FAIL' || log.tag === 'ERROR') return 'text-red-400';
            if (log.tag === 'OK' || log.tag === 'COMPLETE') return 'text-green-400';
            if (log.type === 'AUDIT') return 'text-indigo-400';
            if (log.tag === 'IN') return 'text-blue-400';
            if (log.tag === 'OUT') return 'text-amber-400';
            if (log.tag === 'CANARY') return 'text-amber-400';
            if (log.tag === 'STABLE') return 'text-blue-400';
            if (log.tag === 'START' || log.tag === 'SUMMARY') return 'text-purple-400';
            return 'text-slate-400';
        };

        // ==================== 生命周期 ====================
        onMounted(() => {
            updateTime();
            timeTimer = setInterval(updateTime, 1000);

            refreshPlugins();
            console.log(new Date(), 'start connecting sse')
            connectSSE();

            // 初始化同步：默认触发一次以确保后端与前端一致
            // 也可以选择先获取后端配置，这里为了演示简单直接覆写后端
            updateEnvMode(currentEnv.value);
        });

        // ==================== 监听环境切换 ====================
        watch(currentEnv, (newVal) => {
            updateEnvMode(newVal);
        });

        const updateEnvMode = async (env) => {
            try {
                // 如果是 prod，则 devMode = false；否则 true
                // 这里为了演示，只有显式选 prod 才是生产模式
                await api.post('/simulate/config/mode', { testEnv: env });

                const isProd = env === 'prod';
                const color = isProd ? 'success' : 'info';
                const modeText = isProd ? '生产模式 (拒绝无权限访问)' : '开发模式 (仅警告无权限访问)';

                showToast(`环境已切换: ${modeText}`, color);
            } catch (e) {
                showToast('环境切换失败: ' + e.message, 'error');
            }
        };

        onUnmounted(() => {
            if (timeTimer) clearInterval(timeTimer);
            if (stressTimer) clearInterval(stressTimer);
            if (eventSource) eventSource.close();
        });

        return {
            // 状态
            plugins, activeId, canaryPct, isAuto, ipcEnabled, ipcTarget,
            logs, lastAudit, logViewMode, logContainer, isUserScrolling, sidebarOpen,
            currentEnv, currentTime, sseStatus, sseStatusText,
            stats, loading, modal, toasts, envLabels,

            // 计算属性
            activePlugin, canCanary, canOperate, displayLogs,

            // 方法
            refreshPlugins, selectPlugin, updateStatus, requestUnload,
            confirmModalAction, updateCanaryConfig, togglePerm,
            simulate, simulateIPC, toggleAuto, resetStats, clearLogs,
            handleLogScroll, scrollToTop,
            formatDrift, formatTime,
            getStatusClass, getPluginShortName, getPluginTagClass, getLogColor
        };
    }
}).mount('#app');
