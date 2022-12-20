package com.example.demo;

import feign.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@EnableFeignClients
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

}

@FeignClient("localhostprovider")
interface MyFeignClient {
	@GetMapping("/myfeignreceiver")
	Response getMyValue(@RequestParam("myValue") String myValue);
}


@SuppressWarnings("unused")
@RestController
@RequiredArgsConstructor
@RequestMapping("/demo")
class MyRestController {
	private final MyFeignClient myFeignClient;

	@GetMapping
	public String get(@RequestParam("myParam") String myParam) {
		return "Get Response from demo with param: " + myParam;
	}
	@GetMapping("/feign")
	public Response getFeignResponse(@RequestParam("myParam") String myParam) {
		return myFeignClient.getMyValue(myParam);
	}
	@PostMapping
	public String post(@RequestBody String body) {
		return "Post Body was " + body.length() + " characters long";
	}
	@PutMapping
	public String put() {
		return "Put was succesful";
	}
	@DeleteMapping
	public String delete() {
		return "Delete was succesful";
	}
}
