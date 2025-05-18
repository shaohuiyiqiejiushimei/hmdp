package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class FanoutConfigTest {
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Test
    public void testFanoutExchange(){
        //交换机名称
        String exchangeName = "fanout.exchange";
        //消息
        String message = "hello,everyone，xixi";
        rabbitTemplate.convertAndSend(exchangeName,"",message);
    }
}