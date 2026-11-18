package run.ihalo.ihalo;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Validates a user-provided outbound service base URL before any request is issued.
 *
 * <p>Only http/https URLs are accepted, the host is resolved to every address and loopback / private / link-local /
 * CGNAT / metadata addresses are rejected so the plugin cannot be turned into an SSRF proxy. See the Halo outbound-http
 * docs.
 *
 * @author galaxy-eye
 */
public final class SsrfGuard {

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost",
            "localhost.localdomain",
            "localhost.local",
            "metadata.google.internal",
            "metadata",
            "0.0.0.0",
            "::1",
            "[::1]",
            "127.0.0.1");

    private SsrfGuard() {}

    /**
     * Validate the URL shape (scheme, host, no userinfo, no query/fragment).
     *
     * @return the normalized {@link URI}
     * @throws IllegalArgumentException if the URL is invalid
     */
    public static URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("服务地址不能为空，请先在插件设置中配置");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("服务地址格式不正确");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("仅支持 http/https 协议的服务地址");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("服务地址缺少主机名");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("服务地址不允许携带认证信息");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("服务地址不允许包含查询参数或片段");
        }
        return uri;
    }

    /**
     * Resolve every address of the host and reject blocked ones. This performs a blocking DNS lookup, so it must be run
     * on a bounded elastic scheduler.
     *
     * @param host hostname to verify (already validated as present)
     * @throws IllegalArgumentException if the host is not public
     */
    public static void assertPublicHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTS.contains(h) || h.endsWith(".local") || h.endsWith(".internal")) {
            throw new IllegalArgumentException("该主机地址被禁止访问");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无法解析服务地址中的主机名");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("无法解析服务地址中的主机名");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new IllegalArgumentException("不允许访问内网或本机地址");
            }
        }
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || address.isAnyLocalAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (b == null) {
            return false;
        }
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            if (first == 10 || first == 127) {
                return false;
            }
            if (first == 172 && second >= 16 && second <= 31) {
                return false;
            }
            if (first == 192 && second == 168) {
                return false;
            }
            if (first == 169 && second == 254) {
                return false;
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return false; // 100.64.0.0/10 CGNAT
            }
        } else if (b.length == 16) {
            if ((b[0] & 0xFE) == 0xFC) {
                return false; // fc00::/7 unique local
            }
        }
        return true;
    }
}
