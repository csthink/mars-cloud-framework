package com.mars.cloud.nacos.registration;

import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RegistrationTlsTest {
    @Test void untrustedHttpsCertificateCannotReachTheInstanceHandler(@TempDir Path directory) throws Exception {
        Path store=directory.resolve("test-server.p12");
        String password=java.util.UUID.randomUUID().toString();
        var keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                "-genkeypair","-alias","test-server","-keyalg","RSA","-storetype","PKCS12",
                "-keystore",store.toString(),"-storepass",password,"-keypass",password,
                "-dname","CN=localhost","-ext","SAN=dns:localhost","-validity","1","-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertThat(keytool.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(keytool.exitValue()).isZero();
        KeyStore keys=KeyStore.getInstance("PKCS12");
        try(var input=Files.newInputStream(store)) {keys.load(input,password.toCharArray());}
        KeyManagerFactory managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,password.toCharArray());
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(managers.getKeyManagers(),null,null);
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(tls));
        AtomicInteger requests=new AtomicInteger();
        server.createContext("/",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try(var transport=new NacosHttpTransport()) {
            long started=System.nanoTime();
            assertThatThrownBy(()->transport.call(URI.create("https://localhost:"+server.getAddress().getPort()+"/"),
                    "instance","POST",Map.of(),null,started+Duration.ofSeconds(4).toNanos(),()->true))
                    .isInstanceOf(NacosHttpTransport.Failure.class);
            assertThat(Duration.ofNanos(System.nanoTime()-started).toMillis()).isLessThan(2800);
            assertThat(requests.get()).isZero();
        } finally {server.stop(0);}
    }
}
