package com.mars.cloud.mvc.util;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @since 2025-10-30 08:53
 */
public class IpUtil {
    private static final Pattern PATTERN_NON_ALPHANUM = Pattern.compile("[^0-9a-fA-F]");

    private static final Pattern PATTERN_NON_NUM = Pattern.compile("[^0-9]");

    private static final String UNKNOWN = "unknown";

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private static final String PROXY_CLIENT_IP = "Proxy-Client-IP";

    private static final String WL_PROXY_CLIENT_IP = "WL-Proxy-Client-IP";

    private static final String CLIENT_ADDR = "client_addr";

    public static String getIPAddress(HttpServletRequest request) {
        String ip = null;

        String ipAddresses = request.getHeader("X-Forwarded-For");

        if (ipAddresses == null || ipAddresses.length() == 0 || "unknown".equalsIgnoreCase(ipAddresses)) {
            ipAddresses = request.getHeader("Proxy-Client-IP");
        }
        if (ipAddresses == null || ipAddresses.length() == 0 || "unknown".equalsIgnoreCase(ipAddresses)) {
            ipAddresses = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ipAddresses == null || ipAddresses.length() == 0 || "unknown".equalsIgnoreCase(ipAddresses)) {
            ipAddresses = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ipAddresses == null || ipAddresses.length() == 0 || "unknown".equalsIgnoreCase(ipAddresses)) {
            ipAddresses = request.getHeader("X-Real-IP");
        }

        if (ipAddresses != null && ipAddresses.length() != 0) {
            ip = ipAddresses.split(",")[0];
        }

        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ipAddresses)) {
            ip = request.getRemoteAddr();
        }

        return ip.equals("0:0:0:0:0:0:0:1") ? "127.0.0.1" : ip;
    }

    public static boolean isValidIP(String ip) {
        return (isIPV4(ip) || isIPV6(ip));
    }

    /**
     * Validate if the name is a valid ipv6 address [xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx]
     *
     * @param name the address of IPV6
     * @return true or false to indicate if it is a valid one
     */
    public static boolean isIPV6(String name) {
        if (null == name || name.length() == 0) {
            //log.debug("The name is null");
            return false;
        }

        if (name.indexOf("::") == -1) {
            String[] ipseglist = name.split(":");
            if (ipseglist.length != 8 || name.endsWith(":")) {
                return false;
            }

            for (int i = 0; i < 8; i++) {
                Matcher m = PATTERN_NON_ALPHANUM.matcher(ipseglist[i]);
                if (m.find()) {
                    return false;
                } else {
                    if (ipseglist[i].length() > 4 || ipseglist[i].length() < 1) {
                        return false;
                    }
                }
            }
        } else {
            String[] tmpSegList = name.split("::");
            if (tmpSegList.length > 2 || (tmpSegList.length == 2 && name.endsWith("::"))) {
                return false;
            }
            int totalLen = 0;
            for (int i = 0; i < tmpSegList.length; i++) {
                if (i == 0 && tmpSegList[i].length() == 0) continue;
                String[] ipseglist = tmpSegList[i].split(":");
                if (tmpSegList[i].endsWith(":")) {
                    return false;
                }
                for (int j = 0; j < ipseglist.length; j++) {
                    Matcher m = PATTERN_NON_ALPHANUM.matcher(ipseglist[j]);
                    if (m.find()) {
                        return false;
                    } else {
                        if (ipseglist[j].length() > 4 || ipseglist[j].length() < 1) {
                            return false;
                        }
                    }
                    totalLen++;

                }
            }

            if (totalLen > 7 || totalLen < 1) {
                return false;
            }

        }

        return true;
    }

    /**
     * Validate if the name is a valid ipv4 address [255.255.255.255]
     *
     * @param name the address of IPV4
     * @return true or false to indicate if it is a valid one
     */
    public static boolean isIPV4(String name) {
        if (null == name || name.length() == 0) {
            return false;
        }
        String[] seglist = name.split("\\.");
        if (seglist.length != 4 || name.endsWith(".")) {
            return false;
        }
        try {
            for (int i = 0; i < 4; i++) {
                Matcher m = PATTERN_NON_NUM.matcher(seglist[i]);
                if (m.find()) {
                    return false;
                }

                if (Integer.parseInt(String.valueOf(seglist[i])) > 255 || seglist[i].length() > 3) {
                    return false;
                }
            }

        } catch (Exception e) {
            return false;
        }
        return true;
    }
}
