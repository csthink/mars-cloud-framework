package com.mars.cloud.nacos.registration;

import com.mars.cloud.feign.DownstreamFailure;
import com.mars.cloud.feign.DownstreamFailureMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Standalone test process for registration, discovery and graceful shutdown ordering. */
@Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
@EnableFeignClients(clients=RegistrationProcessProbe.Downstream.class)
@Import(RegistrationProcessProbe.Endpoints.class)
public class RegistrationProcessProbe {
    public static void main(String[] args) {SpringApplication.run(RegistrationProcessProbe.class,args);}
    @FeignClient(name="registration-probe-downstream")
    interface Downstream { @GetMapping("/ping") String ping(); }
    static Path control(Environment env) {return Path.of(env.getRequiredProperty("probe.control-directory"));}
    static void waitFor(Path file,Duration maximum) throws Exception {
        long deadline=System.nanoTime()+maximum.toNanos();
        while(!Files.exists(file)) {
            if(System.nanoTime()>=deadline)throw new IllegalStateException("Probe control timed out");
            Thread.sleep(25);
        }
    }
    @Bean ApplicationRunner runner(Environment env) {return args->{
        Files.writeString(control(env).resolve("runner-entered"),"ready");waitFor(control(env).resolve("runner-release"),Duration.ofMinutes(2));
    };}
    @Bean HealthIndicator dependencyHealthIndicator(Environment env) {
        return ()->Files.exists(control(env).resolve("healthy"))?Health.up().build():Health.down().build();
    }
    @Bean DownstreamFailureMapper probeFailureMapper() {return new DownstreamFailureMapper() {
        public String clientName() {return "registration-probe-downstream";}
        public RuntimeException map(DownstreamFailure failure) {return new IllegalStateException(failure.kind().name());}
    };}
    @RestController
    static class Endpoints {
        private final Environment env;
        private final DiscoveryClient discovery;
        private final Downstream downstream;
        Endpoints(Environment env,DiscoveryClient discovery,Downstream downstream) {this.env=env;this.discovery=discovery;this.downstream=downstream;}
        @GetMapping("/ping") Map<String,Object> ping() {return Map.of("success",true,"result","pong");}
        @GetMapping("/discover") Mono<Integer> discover(@RequestParam String service) {
            return Mono.fromCallable(()->discovery.getInstances(service).size()).subscribeOn(Schedulers.boundedElastic());
        }
        @GetMapping("/work") Mono<String> work() {
            return Mono.fromCallable(()->{
                Files.writeString(control(env).resolve("request-entered"),"started");
                waitFor(control(env).resolve("request-release"),Duration.ofSeconds(45));
                String result=env.getProperty("probe.call-downstream",Boolean.class,false)?downstream.ping():"complete";
                Files.writeString(control(env).resolve("request-completed"),"done");return result;
            }).subscribeOn(Schedulers.boundedElastic());
        }
    }
}
