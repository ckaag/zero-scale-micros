package com.example.localhostprovider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class LocalhostproviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalhostproviderApplication.class, args);
    }

}

@SuppressWarnings("unused")
@RestController
@RequestMapping("/myfeignreceiver")
class MyRestReceiverController {
    @GetMapping
    public String get(@RequestParam("myValue") String myValue) {
        return "This was actually delivered by localhostprovider running locally: " + myValue;
    }
}
