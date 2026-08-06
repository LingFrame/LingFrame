-- 0. 幂等清理：H2 内存库配合 DB_CLOSE_DELAY=-1 时，同 JVM 内多测试上下文共享同一数据库实例，
--    若不清理，data.sql 重复执行会触发主键冲突。schema.sql 已用 CREATE TABLE IF NOT EXISTS 保证幂等，
--    此处对称地清空所有表数据后再插入，使 data.sql 可重复执行。
--    schema 无外键约束，DELETE 顺序无依赖，按子表到主表的逻辑顺序清理仅为可读性。
DELETE FROM t_notification_read;
DELETE FROM t_notification;
DELETE FROM t_logistics;
DELETE FROM t_order_refund;
DELETE FROM t_order_item;
DELETE FROM t_order;
DELETE FROM t_seckill_active;
DELETE FROM t_coupon_user;
DELETE FROM t_coupon;
DELETE FROM t_inventory_history;
DELETE FROM t_inventory;
DELETE FROM t_sku;
DELETE FROM t_spu;
DELETE FROM t_member;
DELETE FROM t_member_level;
DELETE FROM t_social_user;
DELETE FROM t_role_menu;
DELETE FROM t_menu;
DELETE FROM t_user_role;
DELETE FROM t_role;
DELETE FROM t_user;
DELETE FROM t_post;
DELETE FROM t_dept;
DELETE FROM t_audit_log;

-- 1. 插入部门数据
INSERT INTO t_dept (id, name, parent_id, sort, status) VALUES (1, '总经办', 0, 1, 1);
INSERT INTO t_dept (id, name, parent_id, sort, status) VALUES (2, '研发部', 1, 2, 1);
INSERT INTO t_dept (id, name, parent_id, sort, status) VALUES (3, '运营部', 1, 3, 1);
INSERT INTO t_dept (id, name, parent_id, sort, status) VALUES (4, '仓储部', 1, 4, 1);

-- 2. 插入岗位数据
INSERT INTO t_post (id, code, name, sort, status) VALUES (1, 'gm', '总经理', 1, 1);
INSERT INTO t_post (id, code, name, sort, status) VALUES (2, 'sa', '架构师', 2, 1);
INSERT INTO t_post (id, code, name, sort, status) VALUES (3, 'dev', '研发工程师', 3, 1);
INSERT INTO t_post (id, code, name, sort, status) VALUES (4, 'store_manager', '主店长', 4, 1);

-- 3. 插入会员等级折旧配置
INSERT INTO t_member_level (vip_level, name, need_growth, discount_rate) VALUES (0, '普通会员', 0, 1.00);
INSERT INTO t_member_level (vip_level, name, need_growth, discount_rate) VALUES (1, '黄金会员(VIP1)', 100, 0.98);
INSERT INTO t_member_level (vip_level, name, need_growth, discount_rate) VALUES (2, '白金会员(VIP2)', 500, 0.95);
INSERT INTO t_member_level (vip_level, name, need_growth, discount_rate) VALUES (3, '钻石会员(VIP3)', 2000, 0.90);
INSERT INTO t_member_level (vip_level, name, need_growth, discount_rate) VALUES (4, '至尊皇冠(VIP4)', 10000, 0.85);

-- 4. 插入细粒度权限菜单/按钮数据
-- M-目录, C-菜单, F-按钮
INSERT INTO t_menu (id, name, perms, parent_id, type, sort) VALUES (1, '商品管理', NULL, 0, 'M', 1);
INSERT INTO t_menu (id, name, perms, parent_id, type, sort) VALUES (2, '商品添加', 'product:admin:add', 1, 'F', 1);
INSERT INTO t_menu (id, name, perms, parent_id, type, sort) VALUES (3, '商品修改', 'product:admin:update', 1, 'F', 2);
INSERT INTO t_menu (id, name, perms, parent_id, type, sort) VALUES (4, '商品下架', 'product:admin:disable', 1, 'F', 3);
INSERT INTO t_menu (id, name, perms, parent_id, type, sort) VALUES (5, '库存直接调整', 'product:admin:adjustStock', 1, 'F', 4);

INSERT INTO t_menu (id, name, perms, parent_id, type, sort) VALUES (6, '订单管理', NULL, 0, 'M', 2);
INSERT INTO t_menu (id, name, perms, parent_id, type, sort) VALUES (7, '订单发货', 'order:admin:ship', 6, 'F', 1);
INSERT INTO t_menu (id, name, perms, parent_id, type, sort) VALUES (8, '售后审核', 'order:admin:refundAudit', 6, 'F', 2);

INSERT INTO t_menu (id, name, perms, parent_id, type, sort) VALUES (9, '系统审计', 'audit:admin:list', 0, 'C', 3);

-- 5. 插入角色数据
INSERT INTO t_role (id, code, name) VALUES (1, 'ROLE_ADMIN', '系统管理员');
INSERT INTO t_role (id, code, name) VALUES (2, 'ROLE_USER', '普通消费者');

-- 6. 绑定角色与菜单细粒度权限 (ROLE_ADMIN 绑定所有菜单, ROLE_USER 没有任何后台菜单权限)
INSERT INTO t_role_menu (role_id, menu_id) VALUES (1, 1);
INSERT INTO t_role_menu (role_id, menu_id) VALUES (1, 2);
INSERT INTO t_role_menu (role_id, menu_id) VALUES (1, 3);
INSERT INTO t_role_menu (role_id, menu_id) VALUES (1, 4);
INSERT INTO t_role_menu (role_id, menu_id) VALUES (1, 5);
INSERT INTO t_role_menu (role_id, menu_id) VALUES (1, 6);
INSERT INTO t_role_menu (role_id, menu_id) VALUES (1, 7);
INSERT INTO t_role_menu (role_id, menu_id) VALUES (1, 8);
INSERT INTO t_role_menu (role_id, menu_id) VALUES (1, 9);

-- 7. 插入用户数据 (密码均为 123456，采用 BCrypt 强哈希)
-- admin -> 总经办 (dept_id=1), 岗位为总经理 (post_id=1)
INSERT INTO t_user (id, username, password, nickname, phone, email, status, dept_id, post_id)
VALUES (1, 'admin', '$2a$10$Ruy7aKhy.kM3l17XG5a8n.4jY9i5L/71YyP9fUoVp2lqI2FwKq8wG', '超级管理员', '13800138000', 'admin@lingmall.com', 1, 1, 1);

-- testuser -> 研发部 (dept_id=2), 岗位为研发工程师 (post_id=3)
INSERT INTO t_user (id, username, password, nickname, phone, email, status, dept_id, post_id)
VALUES (2, 'testuser', '$2a$10$Ruy7aKhy.kM3l17XG5a8n.4jY9i5L/71YyP9fUoVp2lqI2FwKq8wG', '体验客户小李', '13800138001', 'xiaoli@gmail.com', 1, 2, 3);

-- 8. 关联用户与角色
INSERT INTO t_user_role (user_id, role_id) VALUES (1, 1); -- admin -> ROLE_ADMIN
INSERT INTO t_user_role (user_id, role_id) VALUES (1, 2); -- admin -> ROLE_USER
INSERT INTO t_user_role (user_id, role_id) VALUES (2, 2); -- testuser -> ROLE_USER

-- 9. 初始化会员卡
INSERT INTO t_member (user_id, point, growth, vip_level) VALUES (1, 5000, 5000, 3); -- admin 预设为 VIP3
INSERT INTO t_member (user_id, point, growth, vip_level) VALUES (2, 0, 0, 0);       -- 普通用户为 VIP0

-- 10. 插入商品 SPU 数据
INSERT INTO t_spu (id, name, brand, image_url, description, category, status)
VALUES (1, 'iPhone 15 Pro Max', 'Apple', 'https://img.lingmall.com/iphone15_spu.jpg', '苹果年度旗舰钛金属5G手机', '手机数码', 1);

INSERT INTO t_spu (id, name, brand, image_url, description, category, status)
VALUES (2, 'MacBook Pro 16', 'Apple', 'https://img.lingmall.com/macbook_spu.jpg', 'M3芯片强劲续航视网膜屏笔记本', '电脑办公', 1);

INSERT INTO t_spu (id, name, brand, image_url, description, category, status)
VALUES (3, 'iPad Pro 11', 'Apple', 'https://img.lingmall.com/ipad_spu.jpg', 'M2芯片极速流畅高刷平板电脑', '电脑办公', 1);

-- 11. 插入规格具体的 SKU 数据
-- SKU 1: iPhone 15 Pro Max - 原色钛金属 - 256G
INSERT INTO t_sku (id, spu_id, specs_json, price, image_url, status)
VALUES (1, 1, '{"color":"原色钛金属","storage":"256G"}', 9999.00, 'https://img.lingmall.com/iphone15_256g_gray.jpg', 1);

-- SKU 2: iPhone 15 Pro Max - 原色钛金属 - 512G
INSERT INTO t_sku (id, spu_id, specs_json, price, image_url, status)
VALUES (2, 1, '{"color":"原色钛金属","storage":"512G"}', 11999.00, 'https://img.lingmall.com/iphone15_512g_gray.jpg', 1);

-- SKU 3: iPhone 15 Pro Max - 蓝色钛金属 - 256G
INSERT INTO t_sku (id, spu_id, specs_json, price, image_url, status)
VALUES (3, 1, '{"color":"蓝色钛金属","storage":"256G"}', 9999.00, 'https://img.lingmall.com/iphone15_256g_blue.jpg', 1);

-- SKU 4: MacBook Pro 16 - M3 Max - 36G - 1T
INSERT INTO t_sku (id, spu_id, specs_json, price, image_url, status)
VALUES (4, 2, '{"processor":"M3 Max","memory":"36G","ssd":"1T"}', 19999.00, 'https://img.lingmall.com/macbook_16_m3.jpg', 1);

-- SKU 5: iPad Pro 11 - 深空灰色 - 128G
INSERT INTO t_sku (id, spu_id, specs_json, price, image_url, status)
VALUES (5, 3, '{"color":"深空灰色","storage":"128G"}', 6199.00, 'https://img.lingmall.com/ipad_11_128g.jpg', 1);

-- SKU 6: iPad Pro 11 - 银色 - 256G
INSERT INTO t_sku (id, spu_id, specs_json, price, image_url, status)
VALUES (6, 3, '{"color":"银色","storage":"256G"}', 6999.00, 'https://img.lingmall.com/ipad_11_256g.jpg', 1);

-- 12. 插入初始可用库存
INSERT INTO t_inventory (sku_id, stock, lock_stock, version) VALUES (1, 100, 0, 0);
INSERT INTO t_inventory (sku_id, stock, lock_stock, version) VALUES (2, 50, 0, 0);
INSERT INTO t_inventory (sku_id, stock, lock_stock, version) VALUES (3, 100, 0, 0);
INSERT INTO t_inventory (sku_id, stock, lock_stock, version) VALUES (4, 30, 0, 0);
INSERT INTO t_inventory (sku_id, stock, lock_stock, version) VALUES (5, 200, 0, 0);
INSERT INTO t_inventory (sku_id, stock, lock_stock, version) VALUES (6, 150, 0, 0);

-- 13. 初始化优惠券发布数据 (2026年 - 2036年)
-- 优惠券 1: 新人无门槛 100 元券
INSERT INTO t_coupon (id, name, min_point, amount, start_time, end_time, publish_count, stock_count)
VALUES (1, '新人无门槛 100 元券', 0.00, 100.00, '2026-01-01 00:00:00', '2036-01-01 00:00:00', 1000, 999);

-- 优惠券 2: 数码发烧友 满5000减500券
INSERT INTO t_coupon (id, name, min_point, amount, start_time, end_time, publish_count, stock_count)
VALUES (2, '数码发烧友 满5000减500券', 5000.00, 500.00, '2026-01-01 00:00:00', '2036-01-01 00:00:00', 500, 500);

-- 预置分配一张无门槛优惠券给 testuser (user_id=2, status=0 未使用)
INSERT INTO t_coupon_user (coupon_id, user_id, status, order_id, receive_time, use_time)
VALUES (1, 2, 0, NULL, '2026-07-12 12:00:00', NULL);

-- 14. 插入秒杀活动配置 (针对 SKU 6: iPad 256G 原价6999 -> 秒杀价4999, 库存10)
INSERT INTO t_seckill_active (id, spu_id, sku_id, seckill_price, stock, start_time, end_time)
VALUES (1, 3, 6, 4999.00, 10, '2026-01-01 00:00:00', '2036-01-01 00:00:00');

-- 15. 初始化系统全局公告
INSERT INTO t_notification (id, title, content, type, receiver_id)
VALUES (1, 'LingMall商城正式运营公告', '欢迎来到零侵入微内核示范商城！系统已完美集成了SPU/SKU多规格模型、秒杀削峰队列以及逆向退款。', 0, NULL);
