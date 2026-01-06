package com.lingframe.controller;

import com.lingframe.service.HostService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/host")
@RequiredArgsConstructor
public class HostController {

    private final HostService hostService;

    @RequestMapping("/hello")
    public String hello() {
        return hostService.sayHello();
    }
}
