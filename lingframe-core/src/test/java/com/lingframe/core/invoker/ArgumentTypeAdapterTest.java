package com.lingframe.core.invoker;

import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ArgumentTypeAdapterTest {

    public enum StatusEnum {
        PENDING, SUCCESS, FAILED
    }

    @Data
    public static class AddressDTO {
        private String city;
        private String street;
    }

    @Data
    public static class ItemDTO {
        private String itemId;
        private Integer quantity;
        private BigDecimal price;
    }

    @Data
    public static class OrderRequestDTO {
        private String orderId;
        private Long customerId;
        private BigDecimal totalAmount;
        private Boolean urgent;
        private StatusEnum status;
        private LocalDateTime createTime;
        private LocalDate orderDate;
        private AddressDTO address;
        private List<ItemDTO> items;
    }

    public static class SampleService {
        public String handleOrder(OrderRequestDTO request) {
            return request.getOrderId();
        }

        public String handleMultiple(String prefix, OrderRequestDTO request, int code) {
            return prefix + ":" + request.getOrderId() + ":" + code;
        }

        public String handleList(List<ItemDTO> items) {
            return String.valueOf(items.size());
        }
    }

    @Test
    @DisplayName("完整生产级 DTO 递归与泛型集合装配测试")
    void testComplexPojoPopulation() throws Exception {
        Method method = SampleService.class.getMethod("handleOrder", OrderRequestDTO.class);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("orderId", "ORD-1001");
        map.put("customer_id", "88888"); // 下划线命名自适应
        map.put("totalAmount", "199.99"); // 字符串转 BigDecimal
        map.put("urgent", "true");
        map.put("status", "success"); // 小写枚举转换
        map.put("createTime", "2026-08-25T19:30:00");
        map.put("orderDate", "2026-08-25");

        Map<String, Object> addrMap = new LinkedHashMap<>();
        addrMap.put("city", "Beijing");
        addrMap.put("street", "Chaoyang Road");
        map.put("address", addrMap);

        List<Map<String, Object>> itemsList = new ArrayList<>();
        Map<String, Object> item1 = new LinkedHashMap<>();
        item1.put("itemId", "ITEM-001");
        item1.put("quantity", 2);
        item1.put("price", "99.99");
        itemsList.add(item1);
        map.put("items", itemsList);

        Object[] args = new Object[]{map};
        Object[] adapted = ArgumentTypeAdapter.adapt(method, args, getClass().getClassLoader());

        assertNotNull(adapted);
        assertEquals(1, adapted.length);
        assertTrue(adapted[0] instanceof OrderRequestDTO, "入参应成功转换为强类型 OrderRequestDTO 实例");

        OrderRequestDTO dto = (OrderRequestDTO) adapted[0];
        assertEquals("ORD-1001", dto.getOrderId());
        assertEquals(88888L, dto.getCustomerId());
        assertEquals(new BigDecimal("199.99"), dto.getTotalAmount());
        assertTrue(dto.getUrgent());
        assertEquals(StatusEnum.SUCCESS, dto.getStatus());
        assertEquals(LocalDateTime.parse("2026-08-25T19:30:00"), dto.getCreateTime());
        assertEquals(LocalDate.parse("2026-08-25"), dto.getOrderDate());

        assertNotNull(dto.getAddress());
        assertEquals("Beijing", dto.getAddress().getCity());

        assertNotNull(dto.getItems());
        assertEquals(1, dto.getItems().size());
        assertTrue(dto.getItems().get(0) instanceof ItemDTO);
        assertEquals("ITEM-001", dto.getItems().get(0).getItemId());
        assertEquals(2, dto.getItems().get(0).getQuantity());
        assertEquals(new BigDecimal("99.99"), dto.getItems().get(0).getPrice());
    }

    @Test
    @DisplayName("MethodHandle 类型自适应与 invokeWithArguments 验证")
    void testMethodHandleAdaptation() throws Throwable {
        SampleService service = new SampleService();
        Method method = SampleService.class.getMethod("handleOrder", OrderRequestDTO.class);
        MethodHandle handle = MethodHandles.lookup().unreflect(method);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("orderId", "MH-9999");
        map.put("customer_id", "12345");

        // 模拟 TerminalInvokerFilter concatArgs [serviceBean, map]
        Object[] argsWithTarget = new Object[]{service, map};
        Object[] adapted = ArgumentTypeAdapter.adapt(handle.type(), argsWithTarget, getClass().getClassLoader());

        assertNotNull(adapted);
        assertEquals(2, adapted.length);
        assertSame(service, adapted[0]);
        assertTrue(adapted[1] instanceof OrderRequestDTO);

        // 执行 MethodHandle.invokeWithArguments 验证绝无 ClassCastException
        Object result = handle.invokeWithArguments(adapted);
        assertEquals("MH-9999", result);
    }

    @Test
    @DisplayName("多参数混合调用自适应转换测试")
    void testMultipleArgsAdaptation() throws Exception {
        Method method = SampleService.class.getMethod("handleMultiple", String.class, OrderRequestDTO.class, int.class);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("orderId", "ORD-2002");

        Object[] args = new Object[]{"REQ", map, "404"};
        Object[] adapted = ArgumentTypeAdapter.adapt(method, args, getClass().getClassLoader());

        assertEquals("REQ", adapted[0]);
        assertTrue(adapted[1] instanceof OrderRequestDTO);
        assertEquals(404, adapted[2]);
    }

    @Test
    @DisplayName("顶级泛型 List<ItemDTO> 转换测试")
    void testTopLevelGenericList() throws Exception {
        Method method = SampleService.class.getMethod("handleList", List.class);

        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("itemId", "I-1");
        item.put("quantity", 10);
        list.add(item);

        Object[] adapted = ArgumentTypeAdapter.adapt(method, new Object[]{list}, getClass().getClassLoader());
        assertNotNull(adapted);
        assertTrue(adapted[0] instanceof List);
        List<?> resList = (List<?>) adapted[0];
        assertEquals(1, resList.size());
        assertTrue(resList.get(0) instanceof ItemDTO);
        assertEquals("I-1", ((ItemDTO) resList.get(0)).getItemId());
    }
}