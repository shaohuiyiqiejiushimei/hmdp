package com.hmdp.service.impl;

import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ShopServiceImplTest {
    @Resource
    private ShopServiceImpl shopService;
    @Test
    public void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(2L,10L);
    }
}