package com.mars.cloud.common.domain.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.codec.binary.Base64;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * @since 2025-05-07 14:48
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class IDGenerator {

    public static String getId() {
        return random();
    }

    public static String getNumId() {
        String uuid = UUID.randomUUID().toString().replaceAll("-", "").toUpperCase();
        long suffix = Math.abs(uuid.hashCode() % 100000000);
        SimpleDateFormat sdf = new SimpleDateFormat("yyMMdd");
        String time = sdf.format(new Date(System.currentTimeMillis()));
        long prefix = Long.parseLong(time) * 100000000;
        return String.valueOf(prefix + suffix);
    }


    public static String random() {
        byte[] data = toByteArray(UUID.randomUUID());
        String s = Base64.encodeBase64URLSafeString(data);
        return s.split("=")[0];
    }

    public static byte[] toByteArray(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] buffer = new byte[16];

        int i;
        for (i = 0; i < 8; ++i) {
            buffer[i] = (byte) ((int) (msb >>> 8 * (7 - i)));
        }

        for (i = 8; i < 16; ++i) {
            buffer[i] = (byte) ((int) (lsb >>> 8 * (7 - i)));
        }

        return buffer;
    }

}
