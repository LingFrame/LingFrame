-- 1. 部门表
CREATE TABLE IF NOT EXISTS t_dept
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL COMMENT '部门名称',
    parent_id  BIGINT       DEFAULT 0 COMMENT '父部门ID',
    sort       INT          DEFAULT 0 COMMENT '排序值',
    status     INT          DEFAULT 1 COMMENT '状态: 0-停用, 1-正常',
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 2. 岗位表
CREATE TABLE IF NOT EXISTS t_post
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(50)  NOT NULL UNIQUE COMMENT '岗位编码',
    name       VARCHAR(100) NOT NULL COMMENT '岗位名称',
    sort       INT          DEFAULT 0 COMMENT '排序值',
    status     INT          DEFAULT 1 COMMENT '状态: 0-停用, 1-正常',
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 3. 用户表
CREATE TABLE IF NOT EXISTS t_user
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(50)  NOT NULL UNIQUE COMMENT '用户名',
    password   VARCHAR(100) NOT NULL COMMENT '密码',
    nickname   VARCHAR(50)  COMMENT '昵称',
    phone      VARCHAR(20)  COMMENT '电话',
    email      VARCHAR(100) COMMENT '邮箱',
    status     INT          DEFAULT 1 COMMENT '状态: 0-禁用, 1-正常',
    dept_id    BIGINT       DEFAULT NULL COMMENT '所属部门ID',
    post_id    BIGINT       DEFAULT NULL COMMENT '所属岗位ID',
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 4. 角色表
CREATE TABLE IF NOT EXISTS t_role
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(50)  NOT NULL UNIQUE COMMENT '角色编码',
    name       VARCHAR(50)  NOT NULL COMMENT '角色名称',
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 5. 用户角色关系表
CREATE TABLE IF NOT EXISTS t_user_role
(
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL
);

-- 6. 菜单与细粒度按钮权限定义表
CREATE TABLE IF NOT EXISTS t_menu
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL COMMENT '菜单或按钮名称',
    perms      VARCHAR(100) DEFAULT NULL COMMENT '权限标识, 比如 sys:user:add, product:admin:add',
    parent_id  BIGINT       DEFAULT 0 COMMENT '父ID',
    type       CHAR(1)      NOT NULL COMMENT '类型: M-目录, C-菜单, F-按钮',
    sort       INT          DEFAULT 0 COMMENT '排序'
);

-- 7. 角色与菜单关系表
CREATE TABLE IF NOT EXISTS t_role_menu
(
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL
);

-- 8. 三方社交用户绑定表
CREATE TABLE IF NOT EXISTS t_social_user
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    platform   VARCHAR(20)  NOT NULL COMMENT '三方平台名称, 如 gitee_mock, github_mock',
    open_id    VARCHAR(100) NOT NULL UNIQUE COMMENT '唯一标识',
    user_id    BIGINT       NOT NULL COMMENT '关联的本地系统用户ID',
    nickname   VARCHAR(50)  COMMENT '三方昵称',
    avatar     VARCHAR(255) COMMENT '三方头像',
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 9. 会员卡表
CREATE TABLE IF NOT EXISTS t_member
(
    user_id    BIGINT PRIMARY KEY COMMENT '关联的用户ID',
    point      INT DEFAULT 0 COMMENT '积分',
    growth     INT DEFAULT 0 COMMENT '成长值',
    vip_level  INT DEFAULT 0 COMMENT '会员等级VIP0/1/2/3...'
);

-- 10. 会员等级配置表
CREATE TABLE IF NOT EXISTS t_member_level
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    vip_level     INT            NOT NULL UNIQUE COMMENT '会员等级',
    name          VARCHAR(50)    NOT NULL COMMENT '等级名称',
    need_growth   INT            NOT NULL COMMENT '升级所需成长值',
    discount_rate DECIMAL(3, 2) NOT NULL DEFAULT 1.00 COMMENT '享有的折扣率, 如 0.95 代表 95折'
);

-- 11. 优惠券基础信息表
CREATE TABLE IF NOT EXISTS t_coupon
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100)   NOT NULL COMMENT '券名称',
    min_point  DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '使用门槛(满多少可用)',
    amount     DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '减免金额',
    start_time TIMESTAMP      NOT NULL COMMENT '开始有效时间',
    end_time   TIMESTAMP      NOT NULL COMMENT '失效时间',
    publish_count INT         NOT NULL COMMENT '发行总量',
    stock_count   INT         NOT NULL COMMENT '剩余券数量'
);

-- 12. 用户领取的优惠券表
CREATE TABLE IF NOT EXISTS t_coupon_user
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    coupon_id   BIGINT NOT NULL COMMENT '优惠券ID',
    user_id     BIGINT NOT NULL COMMENT '所属用户ID',
    status      INT    NOT NULL DEFAULT 0 COMMENT '使用状态: 0-未使用, 1-已使用, 2-已过期',
    order_id    BIGINT DEFAULT NULL COMMENT '绑定的使用订单ID',
    receive_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    use_time    TIMESTAMP DEFAULT NULL
);

-- 13. 商品 SPU 表
CREATE TABLE IF NOT EXISTS t_spu
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL COMMENT '商品名称',
    brand       VARCHAR(100) DEFAULT NULL COMMENT '品牌',
    image_url   VARCHAR(255) DEFAULT NULL COMMENT '商品主图',
    description VARCHAR(1000) DEFAULT NULL COMMENT '商品详情描述',
    category    VARCHAR(100) DEFAULT NULL COMMENT '分类',
    status      INT          DEFAULT 1 COMMENT '状态: 0-下架, 1-上架'
);

-- 14. 商品 SKU 表
CREATE TABLE IF NOT EXISTS t_sku
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    spu_id      BIGINT         NOT NULL COMMENT 'SPU ID',
    specs_json  VARCHAR(500)   NOT NULL COMMENT '规格JSON字符串, 如 {"color":"白色","memory":"256G"}',
    price       DECIMAL(10, 2) NOT NULL COMMENT '单价',
    image_url   VARCHAR(255)   DEFAULT NULL COMMENT '规格主图',
    status      INT            DEFAULT 1 COMMENT '状态: 0-禁用, 1-正常'
);

-- 15. 商品库存表
CREATE TABLE IF NOT EXISTS t_inventory
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_id     BIGINT NOT NULL UNIQUE COMMENT 'SKU ID',
    stock      INT    NOT NULL DEFAULT 0 COMMENT '可用库存',
    lock_stock INT    NOT NULL DEFAULT 0 COMMENT '锁定库存',
    version    INT    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号'
);

-- 16. 库存变更历史流水表
CREATE TABLE IF NOT EXISTS t_inventory_history
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_id      BIGINT NOT NULL COMMENT 'SKU ID',
    change_num  INT    NOT NULL COMMENT '变更数量 (可正可负)',
    type        INT    NOT NULL COMMENT '类型: 0-下单锁库存, 1-管理员手动调整, 2-支付发货扣减, 3-超时释放, 4-售后回滚',
    operator    VARCHAR(50) DEFAULT 'SYSTEM' COMMENT '操作人',
    remark      VARCHAR(255) COMMENT '备注',
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 17. 秒杀活动配置表
CREATE TABLE IF NOT EXISTS t_seckill_active
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    spu_id      BIGINT         NOT NULL COMMENT 'SPU ID',
    sku_id      BIGINT         NOT NULL UNIQUE COMMENT 'SKU ID',
    seckill_price DECIMAL(10,2) NOT NULL COMMENT '秒杀价格',
    stock       INT            NOT NULL COMMENT '秒杀活动库存数量',
    start_time  TIMESTAMP      NOT NULL COMMENT '秒杀开启时间',
    end_time    TIMESTAMP      NOT NULL COMMENT '秒杀截止时间'
);

-- 18. 订单主表
CREATE TABLE IF NOT EXISTS t_order
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_sn        VARCHAR(64)    NOT NULL UNIQUE COMMENT '订单流水号',
    user_id         BIGINT         NOT NULL,
    total_amount    DECIMAL(10, 2) NOT NULL COMMENT '应付总价 (经会员及优惠券折算后)',
    status          INT            NOT NULL DEFAULT 0 COMMENT '状态: 0-待付款, 1-待发货, 2-待收货, 3-已完成, 4-已取消, 5-退款中, 6-已退款, 7-拒绝退款',
    receiver_name   VARCHAR(50)    NOT NULL,
    receiver_phone  VARCHAR(20)    NOT NULL,
    receiver_address VARCHAR(255)   NOT NULL,
    created_at      TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    paid_at         TIMESTAMP      NULL,
    canceled_at     TIMESTAMP      NULL
);

-- 19. 订单项明细表
CREATE TABLE IF NOT EXISTS t_order_item
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT         NOT NULL,
    spu_id       BIGINT         NOT NULL,
    sku_id       BIGINT         NOT NULL,
    product_name VARCHAR(255)   NOT NULL COMMENT '商品名(SPU+SKU规格串)',
    quantity     INT            NOT NULL,
    price        DECIMAL(10, 2) NOT NULL COMMENT '下单单价'
);

-- 20. 订单售后退款单表
CREATE TABLE IF NOT EXISTS t_order_refund
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT         NOT NULL,
    user_id     BIGINT         NOT NULL,
    amount      DECIMAL(10, 2) NOT NULL COMMENT '退款金额',
    reason      VARCHAR(255)   NOT NULL COMMENT '退款原因',
    reject_reason VARCHAR(255) DEFAULT NULL COMMENT '商家拒绝退款的原因',
    status      INT            NOT NULL DEFAULT 0 COMMENT '退款状态: 0-申请中, 1-已退回, 2-已拒绝',
    created_at  TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    audit_time  TIMESTAMP      NULL
);

-- 21. 物流单及轨迹追踪表
CREATE TABLE IF NOT EXISTS t_logistics
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id         BIGINT      NOT NULL UNIQUE,
    delivery_company VARCHAR(50) COMMENT '物流公司',
    delivery_sn      VARCHAR(64) COMMENT '物流单号',
    status           INT         NOT NULL DEFAULT 0 COMMENT '状态: 0-未发货, 1-运输中, 2-派送中, 3-已签收',
    trace_data       CLOB        COMMENT 'JSON存储的时间线物流轨迹 [{ "time": "...", "content": "..." }]'
);

-- 22. 系统公告与私信通知表
CREATE TABLE IF NOT EXISTS t_notification
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(100) NOT NULL COMMENT '通知标题',
    content     VARCHAR(1000) NOT NULL COMMENT '通知正文',
    type        INT          NOT NULL DEFAULT 0 COMMENT '类型: 0-系统全局公告, 1-用户个人私信',
    receiver_id BIGINT       DEFAULT NULL COMMENT '接收用户ID (公告模式下为NULL)',
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 23. 用户通知已读状态表
CREATE TABLE IF NOT EXISTS t_notification_read
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    notification_id BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    is_read         INT    DEFAULT 1 COMMENT '已读: 1-已读',
    read_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 24. 本地审计日志表
CREATE TABLE IF NOT EXISTS t_audit_log
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator   VARCHAR(50),
    action     VARCHAR(50),
    resource   VARCHAR(50),
    ip         VARCHAR(50),
    status     VARCHAR(20) COMMENT 'SUCCESS, FAIL',
    duration   BIGINT COMMENT '耗时(毫秒)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
