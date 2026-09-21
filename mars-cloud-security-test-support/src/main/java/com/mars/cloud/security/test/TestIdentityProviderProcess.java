package com.mars.cloud.security.test;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;

/** Test-classpath process for shell acceptance tests. Token contents never go to standard output. */
public final class TestIdentityProviderProcess {
    private TestIdentityProviderProcess() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected an existing private output directory");
        Path directory = Path.of(args[0]);
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Output directory must exist");
        var issuer = new TestIdentityProvider();
        Runtime.getRuntime().addShutdownHook(new Thread(issuer::close));
        String[] audiences = {"mars-cloud-sample-service", "mars-cloud-upms-service", "mars-cloud-gateway"};
        var claims = issuer.claims("caller-allow", audiences);
        claims.put("client_id", "mars-cloud-sample-service");
        write(directory.resolve("allow.token"), issuer.sign(claims));
        write(directory.resolve("admin.token"), issuer.token("local-admin", audiences));
        var expired = issuer.claims("local-admin", audiences);
        expired.put("exp", java.util.Date.from(java.time.Instant.now().minusSeconds(120)));
        write(directory.resolve("expired.token"), issuer.sign(expired));
        write(directory.resolve("deny.token"), issuer.token("caller-deny", audiences));
        write(directory.resolve("sample-only.token"), issuer.token("caller-allow", "mars-cloud-sample-service"));
        write(directory.resolve("issuer.properties"), "issuer=" + issuer.issuer() + "\njwks=" + issuer.jwksUri() + "\n");
        write(directory.resolve("ready"), "ready\n");
        new CountDownLatch(1).await();
    }
    private static void write(Path path, String value) throws Exception {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, value);
    }
}
