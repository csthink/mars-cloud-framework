package com.mars.cloud.sentinel.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.sc.ServerWebExchangeItemParser;
import org.springframework.web.server.ServerWebExchange;

/**
 * 从交换属性读取客户端地址，替换默认的 TCP 对端地址。
 *
 * <p>网关前面有负载均衡或 CDN 时，对端地址是代理的地址；真实客户端地址由部署物自己的入站过滤器核对后写入交换属性。
 * 属性缺失说明那个过滤器没有先执行，这时抛出异常，请求由应用的统一错误处理返回 500，不回退到对端地址。
 *
 * @since 2026-09-25
 */
public final class ClientIpAttributeItemParser extends ServerWebExchangeItemParser {

    private final String attribute;

    public ClientIpAttributeItemParser(String attribute) {
        this.attribute = attribute;
    }

    @Override
    public String getRemoteAddress(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(attribute);
        if (!(value instanceof String address) || address.isBlank()) {
            throw new IllegalStateException("交换属性 " + attribute + " 里没有客户端地址：写入它的入站过滤器必须先于 Sentinel 网关过滤器执行");
        }
        return address;
    }
}
